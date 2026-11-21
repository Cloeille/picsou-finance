//! Savings books and their interest (SavingsController, SavingsService, SavingsInterestService,
//! SavingsAutoConfigService).

pub mod detector;
pub mod interest;
pub mod model;

use std::sync::{Arc, OnceLock};

use async_trait::async_trait;
use axum::extract::State as AxumState;
use axum::http::StatusCode;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use sqlx::PgExecutor;

use self::model::{RateBasis, RegulatedRates, SavingsConfig, SavingsProduct};
use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::json::{Errors, Json, Path, Validate};
use crate::ledger::accounts;
use crate::ledger::model::{Account, AccountType};
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes.get("/api/savings/suggestions", suggestions);
    routes.put("/api/accounts/{id}/savings-config", upsert_config);
    routes.delete("/api/accounts/{id}/savings-config", delete_config);
    routes.get("/api/accounts/{id}/savings-interest", projection);
}

/// AccountService.toResponse(account) (track T2): one account's AccountResponse JSON, as its
/// owner sees it. The savings-config upsert and every GoalProgressResponse embed it.
#[async_trait]
pub trait AccountResponses: Send + Sync {
    async fn to_response(
        &self,
        state: &AppState,
        account: &Account,
    ) -> AppResult<serde_json::Value>;
}

/// The savings domain's state: the AccountResponses seam, wired once at start (until then the
/// endpoints that embed an AccountResponse answer 501 before writing anything).
#[derive(Default)]
pub struct State {
    account_responses: OnceLock<Arc<dyn AccountResponses>>,
}

impl State {
    /// Plugs in track T2's AccountService.toResponse; false when one was already wired.
    pub fn wire_account_responses(&self, responses: Arc<dyn AccountResponses>) -> bool {
        self.account_responses.set(responses).is_ok()
    }

    pub fn account_responses(&self) -> AppResult<&dyn AccountResponses> {
        self.account_responses
            .get()
            .map(|r| &**r)
            .ok_or(AppError::NotImplemented)
    }
}

/// SavingsSuggestionResponse.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SavingsSuggestionResponse {
    pub account_id: i64,
    pub account_name: String,
    pub suggested_product: SavingsProduct,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub default_annual_rate: Option<Decimal>,
    pub uncertain: bool,
}

/// SavingsConfigDto as the PUT body (no bean validation on it in Java).
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SavingsConfigRequest {
    pub product: Option<SavingsProduct>,
    pub annual_rate: Option<Decimal>,
    pub rate_basis: Option<RateBasis>,
    pub tax_rate_pct: Option<Decimal>,
    pub ceiling: Option<Decimal>,
}

impl Validate for SavingsConfigRequest {
    fn validate(&self, _errors: &mut Errors) {}
}

/// The visible bank-synced accounts with no config whose name looks like a savings book.
pub async fn suggestions(
    AxumState(state): AxumState<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<Vec<SavingsSuggestionResponse>>> {
    let candidates: Vec<Account> = accounts::find_visible_by_member(&state.db, auth.member_id)
        .await?
        .into_iter()
        .filter(|a| !a.is_manual)
        .collect();
    let ids: Vec<i64> = candidates.iter().map(|a| a.id).collect();
    let configured = model::configured_accounts(&state.db, &ids).await?;
    Ok(axum::Json(
        candidates
            .into_iter()
            .filter(|a| !configured.contains(&a.id))
            .filter_map(|a| {
                detector::suggest(Some(&a.name)).map(|s| SavingsSuggestionResponse {
                    account_id: a.id,
                    account_name: a.name,
                    suggested_product: s.product,
                    default_annual_rate: s.default_annual_rate,
                    uncertain: s.uncertain,
                })
            })
            .collect(),
    ))
}

/// Saves the config, and the account takes the matching savings type (LEP, else SAVINGS): the
/// config is the user's classification of the book.
async fn upsert_config(
    AxumState(state): AxumState<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    Json(req): Json<SavingsConfigRequest>,
) -> AppResult<axum::Json<serde_json::Value>> {
    let mut tx = state.db.begin().await?;
    let mut account = accounts::get_or_throw(&mut *tx, id, auth.member_id).await?;
    let rate_basis = req.rate_basis.unwrap_or(RateBasis::Net);
    if let Some(product) = req.product {
        interest::validate(product, rate_basis)?;
    }
    // Columns the entity maps NOT NULL: Hibernate refuses the flush, a 500 in Java.
    let (Some(product), Some(annual_rate)) = (req.product, req.annual_rate) else {
        return Err(AppError::internal(
            "savings config without a product or a rate",
        ));
    };
    let responder = state.wealth.savings.account_responses()?;
    model::upsert(
        &mut *tx,
        &SavingsConfig {
            account_id: id,
            product,
            annual_rate,
            rate_basis,
            tax_rate_pct: req.tax_rate_pct,
            ceiling: req.ceiling,
        },
    )
    .await?;
    account.account_type = if product == SavingsProduct::Lep {
        AccountType::Lep
    } else {
        AccountType::Savings
    };
    sqlx::query("UPDATE account SET type = $2::account_type, updated_at = $3 WHERE id = $1")
        .bind(id)
        .bind(account.account_type.as_str())
        .bind(state.clock.now())
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(axum::Json(responder.to_response(&state, &account).await?))
}

/// Idempotent once the account is the member's own.
async fn delete_config(
    AxumState(state): AxumState<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<StatusCode> {
    accounts::get_or_throw(&state.db, id, auth.member_id).await?;
    model::delete_by_account(&state.db, id).await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn projection(
    AxumState(state): AxumState<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<interest::SavingsInterestProjection>> {
    let mut conn = state.db.acquire().await?;
    let account = accounts::get_or_throw(&mut *conn, id, auth.member_id).await?;
    let config = model::find_by_account(&mut *conn, id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("No savings config for account {id}")))?;
    Ok(axum::Json(
        interest::compute_projection(&mut conn, &account, &config, state.clock.today()).await?,
    ))
}

/// SavingsAutoConfigService.configureIfMissing, for the bank syncs: a regulated livret
/// (LIVRET_A, LDDS, LEP) gets its legal rate and ceiling once, never over a saved config. True
/// when a row was created. Safe inside the sync's transaction: a conflict does not abort it.
pub async fn configure_if_missing(
    db: impl PgExecutor<'_>,
    account: &Account,
    account_type: AccountType,
) -> sqlx::Result<bool> {
    let (product, rate, ceiling) = match account_type {
        AccountType::LivretA => (
            SavingsProduct::LivretA,
            RegulatedRates::LIVRET_A,
            RegulatedRates::LIVRET_A_CEILING,
        ),
        AccountType::Ldds => (
            SavingsProduct::Ldds,
            RegulatedRates::LDDS,
            RegulatedRates::LDDS_CEILING,
        ),
        AccountType::Lep => (
            SavingsProduct::Lep,
            RegulatedRates::LEP,
            RegulatedRates::LEP_CEILING,
        ),
        _ => return Ok(false),
    };
    model::insert_if_absent(
        db,
        &SavingsConfig {
            account_id: account.id,
            product,
            annual_rate: rate.parse().expect("a rate literal"),
            rate_basis: RateBasis::Net,
            tax_rate_pct: None,
            ceiling: Some(ceiling.parse().expect("a ceiling literal")),
        },
    )
    .await
}
