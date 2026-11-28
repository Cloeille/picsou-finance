//! `POST /api/admin/archive`: the encrypted archive of issue #865 (database, encryption key,
//! Enable Banking key) as a download, to move an instance to another install.

use axum::body::Body;
use axum::extract::State;
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{IntoResponse, Response};
use serde::Deserialize;
use tokio::io::AsyncReadExt;

use crate::AppState;
use crate::auth::Auth;
use crate::config::{DatabaseConfig, SqliteTarget};
use crate::db::archive::{self, ArchiveError, ExportSource, Scratch};
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Validate};
use crate::http::rate_limit::policies;
use crate::routes::Routes;
use crate::setup_auth::desktop::DesktopSessionGate;
use crate::transfer::me_export::reauth::{self, ReAuth};

pub const CONTENT_TYPE: &str = "application/octet-stream";
const CHUNK_BYTES: usize = 64 * 1024;

pub fn register(routes: &mut Routes) {
    routes.post("/api/admin/archive", export);
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ExportRequest {
    passphrase: Option<String>,
    re_auth: Option<ReAuth>,
}

impl Validate for ExportRequest {
    fn validate(&self, _: &mut Errors) {}
}

fn failed(error: ArchiveError) -> AppError {
    match error {
        ArchiveError::PassphraseMissing | ArchiveError::PassphraseTooShort => {
            AppError::coded(StatusCode::BAD_REQUEST, error.code(), error.to_string())
        }
        ArchiveError::NoCryptoKey | ArchiveError::SourceMissing(_) => AppError::coded(
            StatusCode::CONFLICT,
            "EXPORT_UNAVAILABLE",
            "This instance has no encryption key it can archive.",
        ),
        other => AppError::Internal(format!("archive export: {other}")),
    }
}

/// The file in `scratch`, as a body that removes the scratch folder when it ends or is dropped
/// (a client that leaves included).
fn stream_then_remove(file: tokio::fs::File, scratch: Scratch) -> Body {
    let chunks = futures::stream::unfold(Some((file, scratch)), |held| async move {
        let (mut file, scratch) = held?;
        let mut chunk = vec![0u8; CHUNK_BYTES];
        match file.read(&mut chunk).await {
            Ok(0) => None,
            Ok(read) => {
                chunk.truncate(read);
                Some((Ok(bytes::Bytes::from(chunk)), Some((file, scratch))))
            }
            Err(error) => Some((Err(error), None)),
        }
    });
    Body::from_stream(chunks)
}

async fn export(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    headers: HeaderMap,
    own_window: Result<DesktopSessionGate, AppError>,
    Json(req): Json<ExportRequest>,
) -> AppResult<Response> {
    let user = &auth.user;
    if !state
        .limits
        .try_consume(&policies::EXPORT, &user.id.to_string())
    {
        tracing::warn!(user_id = user.id, ip = %ip.as_java_string(), "archive.export.rate_limited");
        return Ok(StatusCode::TOO_MANY_REQUESTS.into_response());
    }
    reauth::verify(
        &state,
        user,
        req.re_auth.as_ref(),
        &headers,
        own_window.is_ok(),
    )
    .await?;
    let passphrase = req.passphrase.unwrap_or_default();
    archive::check_passphrase(&passphrase).map_err(failed)?;
    let Some(data_dir) = state.config.data_dir() else {
        return Err(AppError::coded(
            StatusCode::CONFLICT,
            "EXPORT_UNAVAILABLE",
            "This instance has no data folder to build an archive in.",
        ));
    };
    tracing::warn!(
        user_id = user.id,
        ip = %ip.as_java_string(),
        "archive.export.requested"
    );

    let scratch = Scratch::create(&data_dir).map_err(failed)?;
    let output = scratch.path().join("archive");
    let env = state.config.archive_env.env();
    let report = match (&state.config.database, &state.db) {
        (DatabaseConfig::Sqlite(SqliteTarget::File(path)), _) => {
            archive::export_archive(ExportSource::Sqlite(path), &output, &passphrase, env).await
        }
        (_, crate::db::Db::Postgres(pool)) => {
            archive::export_archive(ExportSource::Postgres(pool), &output, &passphrase, env).await
        }
        _ => {
            return Err(AppError::coded(
                StatusCode::CONFLICT,
                "EXPORT_UNAVAILABLE",
                "An in-memory database cannot be archived.",
            ));
        }
    }
    .map_err(failed)?;
    let file = tokio::fs::File::open(&output)
        .await
        .map_err(|e| AppError::Internal(format!("archive export: cannot read the archive: {e}")))?;
    let length = file.metadata().await.map(|m| m.len()).ok();
    tracing::info!(
        "archive.export.built tables={} rows={}",
        report.tables,
        report.rows
    );

    let filename = format!("picsou-{}.picsou-archive", state.clock.today());
    let mut response = (
        [
            (header::CONTENT_TYPE, CONTENT_TYPE.to_owned()),
            (
                header::CONTENT_DISPOSITION,
                format!("attachment; filename=\"{filename}\""),
            ),
            (header::CACHE_CONTROL, "no-store".to_owned()),
        ],
        stream_then_remove(file, scratch),
    )
        .into_response();
    if let Some(length) = length {
        response
            .headers_mut()
            .insert(header::CONTENT_LENGTH, length.into());
    }
    Ok(response)
}
