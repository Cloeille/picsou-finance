//! Crypto exchanges: CryptoExchangeController, CryptoExchangeSyncService and the exchange
//! adapters (Binance, Meria; KRAKEN is listed but, as in Java, has no adapter).

mod binance;
mod meria;
pub mod model;
pub mod port;
pub mod seams;
pub mod service;
mod store;

use std::sync::Arc;

use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde::{Deserialize, Deserializer};
use serde_json::Value;

pub use binance::sign as binance_signature;
pub use service::{
    SuppliedCredentials, add_exchange, get_positions, get_status, remove_exchange,
    resync_all_reporting, sync,
};

use self::model::ExchangeType;
use self::seams::{AccountResponder, CryptoLogos, LedgerAccountResponder, NoCryptoLogos};
use crate::AppState;
use crate::auth::Auth;
use crate::error::AppResult;
use crate::http::json::{Errors, Json, Path, Validate};
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes
        .post("/api/crypto/exchange", add)
        .post("/api/crypto/exchange/{id}/sync", sync_one)
        .get("/api/crypto/exchange/status", status)
        .delete("/api/crypto/exchange/{id}", remove);
}

/// The exchange sync's cross-track seams, held as state.sync.exchanges.
pub struct Hooks {
    /// AccountService.toResponse (track T2).
    pub accounts: Arc<dyn AccountResponder>,
    /// CryptoLogoService.getLogoUrls (track T4).
    pub logos: Arc<dyn CryptoLogos>,
}

impl Hooks {
    pub fn new() -> Self {
        Self {
            accounts: Arc::new(LedgerAccountResponder),
            logos: Arc::new(NoCryptoLogos),
        }
    }
}

impl Default for Hooks {
    fn default() -> Self {
        Self::new()
    }
}

/// Jackson's String coercion: a number or boolean reads as its text.
fn lenient_string<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<String>, D::Error> {
    use serde::de::Error;
    port::scalar_text(&Value::deserialize(deserializer)?)
        .map_err(|()| D::Error::custom("not a string"))
}

/// AddExchangeRequest. Its Debug never shows the credentials, nor do validation errors.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct AddExchangeRequest {
    #[serde(default, rename = "type")]
    exchange_type: Option<ExchangeType>,
    #[serde(default, deserialize_with = "lenient_string")]
    api_key: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    api_secret: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    api_passphrase: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    region: Option<String>,
}

impl std::fmt::Debug for AddExchangeRequest {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "AddExchangeRequest[type={:?}, credentials=redacted]",
            self.exchange_type
        )
    }
}

impl Validate for AddExchangeRequest {
    // No @NotBlank on apiKey: the service's 400 carries a detail the frontend shows.
    fn validate(&self, errors: &mut Errors) {
        errors
            .not_null("type", self.exchange_type.as_ref())
            .size("apiKey", self.api_key.as_deref(), 0, 200)
            .size("apiSecret", self.api_secret.as_deref(), 0, 300)
            .size("apiPassphrase", self.api_passphrase.as_deref(), 0, 200)
            .size("region", self.region.as_deref(), 0, 20);
    }
}

async fn add(
    State(state): State<AppState>,
    auth: Auth,
    Json(request): Json<AddExchangeRequest>,
) -> AppResult<Response> {
    let Some(exchange) = request.exchange_type else {
        return Err(crate::error::AppError::BadRequest(
            "An exchange type is required.".into(),
        ));
    };
    let supplied = SuppliedCredentials {
        api_key: request.api_key,
        api_secret: request.api_secret,
        api_passphrase: request.api_passphrase,
        region: request.region,
    };
    let account = add_exchange(&state, exchange, supplied, auth.member_id).await?;
    Ok(axum::Json(account).into_response())
}

async fn sync_one(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<Response> {
    let account = sync(&state, id, auth.member_id).await?;
    Ok(axum::Json(account).into_response())
}

async fn status(State(state): State<AppState>, auth: Auth) -> AppResult<Response> {
    Ok(axum::Json(get_status(&state, auth.member_id).await?).into_response())
}

async fn remove(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<Response> {
    remove_exchange(&state, id, auth.member_id).await?;
    Ok(StatusCode::NO_CONTENT.into_response())
}
