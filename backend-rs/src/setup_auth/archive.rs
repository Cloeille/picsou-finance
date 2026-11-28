//! `POST /api/setup/import-archive`: restores an encrypted archive (issue #865) from the first
//! screen of a fresh instance.
//!
//! The running server holds its database and its encryption key, so the route does not place
//! anything. It runs every check that needs no access to the target, stages the decrypted
//! archive in `<data dir>/import-pending/` and asks `run` to stop with `Exit::Restart`; the next
//! start applies it (`db::archive::apply_pending`).
//!
//! In Desktop Mode `http::desktop_setup_guard` already requires the loopback peer and the gate of
//! the app's own window for every POST under `/api/setup/`, before the body is read.

use axum::extract::{DefaultBodyLimit, FromRequest, Multipart, Request, State};
use axum::handler::Handler;
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{IntoResponse, Response};
use serde::Serialize;

use crate::AppState;
use crate::auth::users;
use crate::db::archive::{self, ArchiveError, ImportOutcome};
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::http::rate_limit::policies;
use crate::routes::Routes;
use crate::setup_auth::audit;
use crate::setup_auth::setup::{self, SetupState};

pub const IMPORT_PATH: &str = "/api/setup/import-archive";
const ARCHIVE_PART: &str = "archive";
const PASSPHRASE_PART: &str = "passphrase";
const MAX_PASSPHRASE_BYTES: usize = 1024;

pub fn register(routes: &mut Routes) {
    // The body is read by the handler, which applies `archive_max_upload_bytes` itself, after its
    // own checks: the 2 MiB default of the extractor and the 1 MiB cap of the anonymous
    // endpoints (http::anonymous_body_limit) both exempt this route.
    routes.post(IMPORT_PATH, import.layer(DefaultBodyLimit::disable()));
}

/// What `GET /api/setup/status` says about an archive import.
#[derive(Debug, Serialize, PartialEq, Eq)]
#[serde(tag = "state", rename_all = "lowercase")]
pub enum ImportStatus {
    /// Staged: this process is about to stop and the next one applies it.
    Pending,
    /// Applied by this process at start.
    Done { tables: usize, rows: u64 },
    /// Not applied, the previous data is kept; `code` is one of `ArchiveError::code`.
    Failed { code: &'static str },
}

/// The import status for `/api/setup/status`: pending once the restart is requested, else the
/// outcome of the import this process applied at start. While setup is not complete it is
/// reported by every read. Once it is complete (the usual case: the archive came from a working
/// instance) the first read takes it, so it is shown once and not to every later visitor.
pub fn status(state: &AppState, setup_complete: bool) -> Option<ImportStatus> {
    if state.restart.is_requested() {
        return Some(ImportStatus::Pending);
    }
    let outcome = if setup_complete {
        state.take_archive_import_outcome()
    } else {
        state.archive_import_outcome()
    };
    outcome.map(|outcome| match outcome {
        ImportOutcome::Done { tables, rows } => ImportStatus::Done { tables, rows },
        ImportOutcome::Failed { code } => ImportStatus::Failed { code },
    })
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Accepted {
    restarting: bool,
    tables: usize,
    rows: u64,
}

fn refused(error: ArchiveError) -> AppError {
    let status = match &error {
        ArchiveError::PassphraseMissing
        | ArchiveError::PassphraseTooShort
        | ArchiveError::Decrypt
        | ArchiveError::NotAnArchive => StatusCode::BAD_REQUEST,
        ArchiveError::Corrupt(_)
        | ArchiveError::NewerFormat(_)
        | ArchiveError::NewerSchema(_)
        | ArchiveError::Verification(_) => StatusCode::UNPROCESSABLE_ENTITY,
        ArchiveError::KeyOverridden
        | ArchiveError::NoKeyDestination
        | ArchiveError::StagingUnavailable
        | ArchiveError::TargetNotEmpty(_)
        | ArchiveError::DatabaseNotEmpty => StatusCode::CONFLICT,
        other => return AppError::Internal(format!("archive import: {other}")),
    };
    AppError::coded(status, error.code(), error.to_string())
}

fn too_large() -> AppError {
    AppError::coded(
        StatusCode::PAYLOAD_TOO_LARGE,
        "ARCHIVE_TOO_LARGE",
        "The archive is larger than this server accepts.",
    )
}

/// The first screen only: setup not complete and no account yet. Once an account exists the
/// instance holds data an import would have to overwrite.
async fn require_fresh_instance(state: &AppState) -> AppResult<()> {
    setup::require_not_complete(state).await?;
    let fresh = setup::current_state(state).await? == SetupState::PendingAdmin
        && users::count(&state.db).await? == 0;
    if !fresh {
        return Err(AppError::coded(
            StatusCode::CONFLICT,
            "SETUP_ALREADY_STARTED",
            "An archive can only be imported before the first account is created.",
        ));
    }
    Ok(())
}

struct Upload {
    archive: Vec<u8>,
    passphrase: String,
}

/// Reads the `archive` file part and the `passphrase` text part, refusing more than `max` bytes
/// in all, whether the request announces its length or not.
async fn read_upload(state: &AppState, request: Request) -> AppResult<Upload> {
    let max = state.config.archive_max_upload_bytes;
    let announced = request
        .headers()
        .get(header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse::<u64>().ok());
    if announced.is_some_and(|len| len > max.saturating_add(64 * 1024)) {
        return Err(too_large());
    }
    let bad = || AppError::BadRequest("Expected a multipart/form-data body.".into());
    let mut form = Multipart::from_request(request, state)
        .await
        .map_err(|_| bad())?;
    let (mut archive, mut passphrase) = (None::<Vec<u8>>, None::<Vec<u8>>);
    let mut total: u64 = 0;
    while let Some(mut field) = form.next_field().await.map_err(|_| bad())? {
        let target = match field.name() {
            Some(ARCHIVE_PART) => &mut archive,
            Some(PASSPHRASE_PART) => &mut passphrase,
            _ => continue,
        };
        let mut bytes = Vec::new();
        while let Some(chunk) = field.chunk().await.map_err(|_| bad())? {
            total += chunk.len() as u64;
            if total > max
                || (field.name() == Some(PASSPHRASE_PART) && bytes.len() > MAX_PASSPHRASE_BYTES)
            {
                return Err(too_large());
            }
            bytes.extend_from_slice(&chunk);
        }
        *target = Some(bytes);
    }
    let passphrase = passphrase
        .map(String::from_utf8)
        .transpose()
        .map_err(|_| AppError::BadRequest("The passphrase must be UTF-8 text.".into()))?
        .unwrap_or_default();
    Ok(Upload {
        archive: archive.ok_or_else(|| {
            AppError::BadRequest(format!("Required part '{ARCHIVE_PART}' is not present."))
        })?,
        passphrase,
    })
}

async fn import(
    State(state): State<AppState>,
    ip: ClientIp,
    headers: HeaderMap,
    request: Request,
) -> AppResult<Response> {
    let ip = ip.as_java_string();
    if !state.limits.try_consume(&policies::SETUP, &ip) {
        return Ok(setup::rate_limited());
    }
    require_fresh_instance(&state).await?;
    if state.restart.is_requested() {
        return Err(AppError::coded(
            StatusCode::CONFLICT,
            "IMPORT_ALREADY_STAGED",
            "An archive is already staged: the server is restarting.",
        ));
    }
    let Some(data_dir) = state.config.data_dir() else {
        return Err(refused(ArchiveError::StagingUnavailable));
    };
    // One upload at a time: the file is held in memory while it is opened.
    let Ok(_one_at_a_time) = state.archive_upload.try_lock() else {
        return Err(AppError::coded(
            StatusCode::CONFLICT,
            "IMPORT_IN_PROGRESS",
            "Another archive is being checked.",
        ));
    };
    let upload = read_upload(&state, request).await?;
    let summary = archive::stage_import(
        &data_dir,
        upload.archive,
        upload.passphrase,
        state.config.archive_env.env(),
    )
    .await
    .map_err(refused)?;
    tracing::info!(
        "setup.archive.staged tables={} rows={}",
        summary.tables,
        summary.rows
    );
    audit::record(
        &state,
        "setup.archive.staged",
        None,
        &setup::audit_ctx(&ip, &headers),
        Some(&format!("tables={} rows={}", summary.tables, summary.rows)),
    )
    .await;
    state.restart.request();
    Ok((
        StatusCode::ACCEPTED,
        axum::Json(Accepted {
            restarting: true,
            tables: summary.tables,
            rows: summary.rows,
        }),
    )
        .into_response())
}
