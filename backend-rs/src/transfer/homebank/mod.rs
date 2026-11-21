//! HomeBank import (HomeBankImportController, HomeBankImportService, HomeBankFileParser): a
//! multipart preview decodes an iOS .hbk / .hbexport or a desktop .qif file into a normalized
//! source kept under a member-bound token; the JSON confirmation applies the reviewed account
//! and category mappings in one transaction.

mod currencies;
mod import;
mod ios;
mod java_hash;
mod json;
mod qif;
pub mod source;
#[cfg(test)]
mod tests;

use std::sync::Arc;
use std::time::Duration;

use axum::extract::{DefaultBodyLimit, State};
use axum::handler::Handler;
use axum::response::{IntoResponse, Response};
use http::StatusCode;
use tokio::sync::Semaphore;

use self::import::{ImportRequest, PreviewResponse};
use self::source::{ParsedHomeBankData, jlen};
use crate::AppState;
use crate::auth::Auth;
use crate::clock::AppClock;
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::http::json::Json;
use crate::routes::Routes;
use crate::transfer::import_rate_limit;
use crate::transfer::preview_store::PreviewStore;
use crate::transfer::upload::{MAX_UPLOAD_BYTES, MultipartForm};

pub fn register(routes: &mut Routes) {
    routes.post(
        "/api/homebank/import/preview",
        preview.layer(DefaultBodyLimit::max(MAX_UPLOAD_BYTES)),
    );
    routes.post("/api/homebank/import", execute);
}

const MAX_FILE_BYTES: usize = 10 * 1024 * 1024;
const MAX_PASSWORD_CHARS: usize = 1024;

pub struct Stores {
    previews: PreviewStore<ParsedHomeBankData>,
    /// HomeBankFileParser.DECODE_SLOTS: at most two files decoded at once.
    decode_slots: Arc<Semaphore>,
}

impl Stores {
    pub fn new(clock: &AppClock) -> Self {
        Self {
            previews: PreviewStore::new(clock.clone(), Duration::from_secs(30 * 60), 8, 3),
            decode_slots: Arc::new(Semaphore::new(2)),
        }
    }

    pub fn purge_expired(&self) {
        self.previews.purge_expired();
    }

    /// HomeBankFileParser.parse: the extension picks the decoder; every failure is a 400 whose
    /// message never carries source data, crypto or parser details.
    async fn decode(
        &self,
        bytes: bytes::Bytes,
        filename: Option<&str>,
        password: Option<&str>,
        currency: Option<&str>,
    ) -> AppResult<ParsedHomeBankData> {
        let bad = |m: &str| AppError::BadRequest(m.to_owned());
        if bytes.is_empty() || bytes.len() > MAX_FILE_BYTES {
            return Err(bad("HomeBank file size is invalid"));
        }
        let lower = filename.map(str::to_lowercase).unwrap_or_default();
        let kind = if lower.ends_with(".hbk") {
            Kind::Clear
        } else if lower.ends_with(".hbexport") {
            Kind::Encrypted
        } else if lower.ends_with(".qif") {
            Kind::Qif
        } else {
            return Err(bad("Unsupported HomeBank file extension"));
        };
        if password.is_some_and(|p| jlen(p) > MAX_PASSWORD_CHARS) {
            return Err(bad("HomeBank password is too long"));
        }
        let permit = Arc::clone(&self.decode_slots)
            .try_acquire_owned()
            .map_err(|_| bad("Too many HomeBank files are being decoded; retry shortly"))?;
        let password = password.map(str::to_owned);
        let currency = currency.map(str::to_owned);
        let parsed = tokio::task::spawn_blocking(move || {
            let _permit = permit;
            match kind {
                Kind::Qif => qif::parse(&bytes, currency.as_deref()),
                Kind::Clear => ios::parse(&bytes, false, None),
                Kind::Encrypted => ios::parse(&bytes, true, password.as_deref()),
            }
        })
        .await
        .map_err(AppError::internal)?;
        parsed.map_err(AppError::BadRequest)
    }
}

#[derive(Clone, Copy)]
enum Kind {
    Clear,
    Encrypted,
    Qif,
}

async fn preview(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    form: MultipartForm,
) -> AppResult<axum::Json<PreviewResponse>> {
    let file = form.file("file")?;
    import_rate_limit(&state, &ip)?;
    let stores = &state.transfer.homebank;
    let parsed = stores
        .decode(
            file.bytes.clone(),
            file.filename.as_deref(),
            form.param("password"),
            form.param("currency"),
        )
        .await?;
    import::preview(&state, &stores.previews, auth.member_id, parsed)
        .await
        .map(axum::Json)
}

async fn execute(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(request): Json<ImportRequest>,
) -> AppResult<Response> {
    import_rate_limit(&state, &ip)?;
    let result = import::execute(
        &state,
        &state.transfer.homebank.previews,
        auth.member_id,
        &request,
    )
    .await?;
    Ok((StatusCode::CREATED, axum::Json(result)).into_response())
}
