//! On-chain wallets (WalletController, WalletSyncService): the EVM, Bitcoin and Solana adapters
//! behind /api/crypto/wallet, and the per-member resync the scheduler and MCP tool run.

mod bitcoin;
mod bitcoin_keys;
mod evm;
mod prices;
mod repo;
mod response;
mod rpc;
mod service;
mod solana;

use std::sync::{Arc, RwLock};

use axum::extract::{Request, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use chrono::{DateTime, Utc};
use serde::Serialize;
use serde_json::Value;

pub use self::prices::{NoLivePrices, WalletPrices};
pub use self::response::AccountResponse;
pub use self::service::{DEFAULT_LOGO_KEY, ResyncSummary, remove_wallet, resync_all};
use crate::AppState;
use crate::auth::Auth;
use crate::db::AsDbHandle;
use crate::error::{AppError, AppResult};
use crate::http::json::{Path, read_json};
use crate::ledger::sync_values::java_trim;
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes
        .post("/api/crypto/wallet", add_wallet)
        .post("/api/crypto/wallet/{id}/sync", sync)
        .get("/api/crypto/wallet", list_wallets)
        .delete("/api/crypto/wallet/{id}", remove);
}

/// The wallets' cross-track seam, held in state.sync.wallets.
pub struct Hooks {
    prices: RwLock<Arc<dyn WalletPrices>>,
}

impl Hooks {
    pub fn new() -> Self {
        Self {
            prices: RwLock::new(Arc::new(NoLivePrices)),
        }
    }

    pub fn prices(&self) -> Arc<dyn WalletPrices> {
        self.prices
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
    }

    /// Track T4 plugs PriceService.refreshPrices in here.
    pub fn set_prices(&self, prices: Arc<dyn WalletPrices>) {
        *self.prices.write().unwrap_or_else(|e| e.into_inner()) = prices;
    }
}

impl Default for Hooks {
    fn default() -> Self {
        Self::new()
    }
}

/// com.picsou.model.Chain, in declaration order (Jackson binds an index to it).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize)]
pub enum Chain {
    #[serde(rename = "SOLANA")]
    Solana,
    #[serde(rename = "EVM")]
    Evm,
    #[serde(rename = "BITCOIN")]
    Bitcoin,
}

impl Chain {
    pub const ALL: [Chain; 3] = [Chain::Solana, Chain::Evm, Chain::Bitcoin];

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Solana => "SOLANA",
            Self::Evm => "EVM",
            Self::Bitcoin => "BITCOIN",
        }
    }

    /// Jackson's enum binding: the exact name (after a trim), or an index given as a number or
    /// as digits.
    fn from_json(value: &Value) -> Result<Option<Self>, ()> {
        let by_index = |index: Option<u64>| {
            index
                .and_then(|i| usize::try_from(i).ok())
                .and_then(|i| Self::ALL.get(i).copied())
                .ok_or(())
        };
        match value {
            Value::Null => Ok(None),
            Value::Number(n) => by_index(n.as_u64()).map(Some),
            Value::String(s) => {
                let name = java_trim(s);
                if let Ok(chain) = name.parse() {
                    Ok(Some(chain))
                } else if !name.is_empty() && name.bytes().all(|b| b.is_ascii_digit()) {
                    by_index(name.parse().ok()).map(Some)
                } else {
                    Err(())
                }
            }
            _ => Err(()),
        }
    }
}

impl std::str::FromStr for Chain {
    type Err = ();

    fn from_str(value: &str) -> Result<Self, ()> {
        Self::ALL
            .into_iter()
            .find(|c| c.as_str() == value)
            .ok_or(())
    }
}

impl std::fmt::Display for Chain {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_str())
    }
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WalletStatusResponse {
    pub id: i64,
    pub chain: Chain,
    pub address: String,
    pub label: Option<String>,
    pub last_synced_at: Option<DateTime<Utc>>,
}

fn unreadable() -> AppError {
    AppError::BadRequest("Failed to read request".into())
}

/// A String field as Jackson binds it: scalars coerced to their text, containers refused.
fn string_field(value: Option<&Value>) -> AppResult<Option<String>> {
    match value {
        None | Some(Value::Null) => Ok(None),
        Some(Value::String(s)) => Ok(Some(s.clone())),
        Some(Value::Number(n)) => Ok(Some(n.to_string())),
        Some(Value::Bool(b)) => Ok(Some(b.to_string())),
        Some(_) => Err(unreadable()),
    }
}

/// AddWalletRequest(chain, address, label): no bean validation, the service checks it.
fn bind_request(body: &Value) -> AppResult<service::NewWallet> {
    let Value::Object(fields) = body else {
        return Err(unreadable());
    };
    let chain = match fields.get("chain") {
        None => None,
        Some(value) => Chain::from_json(value).map_err(|()| unreadable())?,
    };
    Ok(service::NewWallet {
        chain,
        address: string_field(fields.get("address"))?,
        label: string_field(fields.get("label"))?,
    })
}

async fn add_wallet(
    State(state): State<AppState>,
    auth: Auth,
    request: Request,
) -> AppResult<axum::Json<AccountResponse>> {
    let body: Value = read_json(request, &state).await?;
    let request = bind_request(&body)?;
    Ok(axum::Json(
        service::add_wallet(&state, request, auth.member_id).await?,
    ))
}

async fn sync(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<AccountResponse>> {
    Ok(axum::Json(service::sync(&state, id, auth.member_id).await?))
}

async fn list_wallets(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<Vec<WalletStatusResponse>>> {
    let wallets = service::list_wallets(&state, auth.member_id).await?;
    Ok(axum::Json(
        wallets
            .into_iter()
            .map(|w| WalletStatusResponse {
                id: w.id,
                chain: w.chain,
                address: w.address,
                label: w.label,
                last_synced_at: w.last_synced_at,
            })
            .collect(),
    ))
}

async fn remove(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<Response> {
    let mut tx = state.db.begin().await?;
    remove_wallet(&mut tx.as_handle(), state.clock.now(), id, auth.member_id).await?;
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT.into_response())
}
