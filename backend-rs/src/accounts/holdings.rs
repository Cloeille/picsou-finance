//! The holdings read model (AccountService.getHoldings / toHoldingResponse) and the two edits
//! a member makes on a line: its quantity and buy-in, or its removal.

use std::collections::{BTreeSet, HashMap};

use axum::extract::State;
use axum::http::StatusCode;
use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use sqlx::PgConnection;

use super::scale::column;
use crate::AppState;
use crate::accounts::analysis::math::jmul;
use crate::accounts::instrument_logo::{self, LogoUrls};
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::json::{Errors, Json, Path, Validate};
use crate::ledger::decimal::{HALF_UP, divide};
use crate::ledger::model::{
    Account, AccountHolding, AccountType, HOLDING_COLUMNS, HoldingDraft, WealthTier,
};
use crate::ledger::prices::{PriceRead, Quote};
use crate::ledger::sync_values::java_is_blank;
use crate::ledger::{accounts, holdings, providers, valuation};
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes
        .get("/api/accounts/{id}/holdings", list)
        .put("/api/accounts/{id}/holdings/{ticker}", update)
        .delete("/api/accounts/{id}/holdings/{ticker}", remove);
}

/// HoldingResponse.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HoldingResponse {
    pub ticker: String,
    pub name: Option<String>,
    pub logo_url: Option<String>,
    pub logo_url_dark: Option<String>,
    #[serde(with = "crate::accounts::jdecimal")]
    pub quantity: Decimal,
    #[serde(with = "crate::accounts::jdecimal::option")]
    pub average_buy_in: Option<Decimal>,
    #[serde(with = "crate::accounts::jdecimal::option")]
    pub current_price: Option<Decimal>,
    pub quote_currency: Option<String>,
    #[serde(with = "crate::accounts::jdecimal::option")]
    pub current_value_eur: Option<Decimal>,
    #[serde(with = "crate::accounts::jdecimal::option")]
    pub cost_basis_eur: Option<Decimal>,
    #[serde(with = "crate::accounts::jdecimal::option")]
    pub pnl_eur: Option<Decimal>,
    #[serde(with = "crate::accounts::jdecimal::option")]
    pub pnl_percent: Option<Decimal>,
    pub price_updated_at: Option<DateTime<Utc>>,
    pub price_as_of: Option<NaiveDate>,
    pub price_stale: bool,
    pub provider_wealth_tier: Option<WealthTier>,
}

/// HoldingRequest.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HoldingRequest {
    quantity: Option<Decimal>,
    average_buy_in: Option<Decimal>,
}

impl Validate for HoldingRequest {
    fn validate(&self, errors: &mut Errors) {
        errors.not_null("quantity", self.quantity.as_ref());
    }
}

/// getHoldings: the lines of an account the member owns (a co-owner gets the 404, as in Java).
pub async fn owned_holdings(
    conn: &mut PgConnection,
    prices: &dyn PriceRead,
    crypto_logos: &dyn crate::accounts::positions::CryptoLogoRead,
    account_id: i64,
    member_id: i64,
    today: NaiveDate,
) -> AppResult<Vec<HoldingResponse>> {
    let account = accounts::get_or_throw(&mut *conn, account_id, member_id).await?;
    let lines = by_price_desc(&mut *conn, account.id).await?;
    let quotes = valuation::quotes_for(prices, &account, &lines, today).await;
    let logos = logos_for(&mut *conn, crypto_logos, &account, &lines).await?;
    Ok(lines
        .iter()
        .map(|h| to_response(&account, h, &quotes, &logos))
        .collect())
}

/// findByAccountIdOrderByCurrentPriceDesc as PostgreSQL answers it for Java: the
/// (account_id, ticker) index is walked, so equal prices come out in ticker order.
async fn by_price_desc(
    conn: &mut PgConnection,
    account_id: i64,
) -> sqlx::Result<Vec<AccountHolding>> {
    let mut rows: Vec<AccountHolding> = sqlx::query_as(&format!(
        "SELECT {HOLDING_COLUMNS} FROM account_holding h WHERE h.account_id = $1 \
         ORDER BY h.current_price DESC, h.ticker"
    ))
    .bind(account_id)
    .fetch_all(conn)
    .await?;
    rows.iter_mut().for_each(fix_holding);
    Ok(rows)
}

fn fix_holding(h: &mut AccountHolding) {
    h.quantity = column(h.quantity, 8);
    for field in [
        &mut h.average_buy_in,
        &mut h.current_price,
        &mut h.provider_value_eur,
        &mut h.provider_pnl_eur,
    ] {
        *field = field.map(|v| column(v, 8));
    }
}

/// logosFor: a crypto account asks the crypto logo source, any other one reads the stored marks.
async fn logos_for(
    conn: &mut PgConnection,
    crypto_logos: &dyn crate::accounts::positions::CryptoLogoRead,
    account: &Account,
    lines: &[AccountHolding],
) -> sqlx::Result<HashMap<String, LogoUrls>> {
    let tickers: BTreeSet<String> = valuation::tickers_of(lines);
    if tickers.is_empty() {
        return Ok(HashMap::new());
    }
    if account.account_type != AccountType::Crypto {
        return instrument_logo::stored_urls(&mut *conn, &tickers).await;
    }
    Ok(crypto_logos
        .logo_urls(&tickers)
        .await
        .into_iter()
        .map(|(ticker, url)| {
            (
                ticker,
                LogoUrls {
                    light: Some(url),
                    dark: None,
                },
            )
        })
        .collect())
}

/// toHoldingResponse.
fn to_response(
    account: &Account,
    holding: &AccountHolding,
    quotes: &HashMap<String, Quote>,
    logos: &HashMap<String, LogoUrls>,
) -> HoldingResponse {
    let mut current_price = holding.current_price;
    let quantity = holding.quantity;
    let mut quote_currency = holding.quote_currency.clone();
    let mut current_price_eur = None;
    let mut price_updated_at = None;
    let mut price_as_of = None;
    let mut price_stale = false;

    let has_ticker = !java_is_blank(&holding.ticker);
    let ticker_key = has_ticker.then(|| holding.ticker.to_uppercase());
    let insurer = providers::is_insurer_contract(account);
    let has_provider_value = holding.provider_value_eur.is_some();
    let provider_value = holding
        .provider_value_eur
        .filter(|v| !(insurer && *v <= Decimal::ZERO));
    if let Some(key) = &ticker_key {
        let quote = quotes.get(key);
        let usable = quote.filter(|q| !insurer || q.price > Decimal::ZERO);
        if let Some(q) = usable {
            current_price_eur = Some(q.price);
            if insurer {
                current_price = Some(q.price);
                quote_currency = Some("EUR".to_owned());
            }
            price_as_of = Some(q.as_of);
            price_stale = !q.live;
        } else if insurer && (quote.is_some() || has_provider_value) {
            price_stale = true;
        }
        price_updated_at = holding.last_synced_at;
    } else if insurer && has_provider_value {
        price_stale = true;
    }

    if insurer
        && current_price_eur.is_none()
        && let Some(value) = provider_value
    {
        // The bank's line valuation is the fallback; the unit price stays aligned with it.
        current_price = (!quantity.is_zero()).then(|| divide(value, quantity, 8, HALF_UP));
        quote_currency = Some("EUR".to_owned());
        price_as_of = holding.provider_price_date;
        price_stale = true;
    }

    let average_buy_in = holding.average_buy_in;
    let cost_basis = provider_value
        .and_then(|_| valuation::provider_cost_basis_eur(holding))
        .or(average_buy_in.map(|b| jmul(b, quantity)));
    let current_value_eur = current_price_eur
        .map(|p| jmul(p, quantity))
        .or(provider_value);
    let pnl_eur = match (current_value_eur, cost_basis) {
        (Some(value), Some(cost)) => Some(value - cost),
        _ => holding.provider_pnl_eur,
    };
    // abs(): a short's cost basis is negative, and the percentage keeps the P&L's own sign.
    let pnl_percent = match (pnl_eur, cost_basis) {
        (Some(pnl), Some(cost)) if !cost.is_zero() => Some(jmul(
            divide(pnl, cost.abs(), 4, HALF_UP),
            Decimal::ONE_HUNDRED,
        )),
        _ => None,
    };

    let logo = ticker_key.as_ref().and_then(|k| logos.get(k));
    HoldingResponse {
        ticker: holding.ticker.clone(),
        name: holding.name.clone(),
        logo_url: logo.and_then(|l| l.light.clone()),
        logo_url_dark: logo.and_then(|l| l.dark.clone()),
        quantity,
        average_buy_in,
        current_price,
        quote_currency,
        current_value_eur,
        cost_basis_eur: cost_basis,
        pnl_eur,
        pnl_percent,
        price_updated_at,
        price_as_of,
        price_stale,
        provider_wealth_tier: holding.provider_wealth_tier,
    }
}

async fn list(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<Vec<HoldingResponse>>> {
    let mut conn = state.db.acquire().await?;
    let lines = owned_holdings(
        &mut conn,
        &*state.ledger.prices,
        &*state.accounts.crypto_logos,
        id,
        auth.member_id,
        state.clock.today(),
    )
    .await?;
    Ok(axum::Json(lines))
}

fn holding_not_found() -> AppError {
    AppError::NotFound("Holding not found".to_owned())
}

async fn update(
    State(state): State<AppState>,
    auth: Auth,
    Path((id, ticker)): Path<(i64, String)>,
    Json(request): Json<HoldingRequest>,
) -> AppResult<axum::Json<HoldingResponse>> {
    let mut tx = state.db.begin().await?;
    let account = accounts::get_or_throw(&mut *tx, id, auth.member_id).await?;
    let holding = holdings::find_by_account_and_ticker(&mut *tx, id, &ticker)
        .await?
        .ok_or_else(holding_not_found)?;
    let mut draft = HoldingDraft::from(&holding);
    // A synced account owns its quantity: the chain or the exchange reports it.
    let quantity = request.quantity.filter(|_| account.is_manual);
    if let Some(q) = quantity {
        draft.quantity = q;
    }
    if let Some(buy_in) = request.average_buy_in {
        draft.average_buy_in = Some(buy_in);
    }
    // An edit invalidates the broker's value and P&L as a pair, except where the provider's
    // value is the line's only valuation (CORUM Life, an insurer contract).
    if !account.is_synced_corum_life() && !providers::is_insurer_contract(&account) {
        draft.provider_value_eur = None;
    }
    draft.provider_pnl_eur = None;
    let mut saved = holdings::update(&mut *tx, holding.id, &draft, state.clock.now()).await?;
    tx.commit().await?;
    fix_holding(&mut saved);
    // Java answers with the entity it set, so the request's scale, not the column's.
    if let Some(q) = quantity {
        saved.quantity = q;
    }
    if let Some(buy_in) = request.average_buy_in {
        saved.average_buy_in = Some(buy_in);
    }
    let lines = std::slice::from_ref(&saved);
    let today = state.clock.today();
    let quotes = valuation::quotes_for(&*state.ledger.prices, &account, lines, today).await;
    let mut conn = state.db.acquire().await?;
    let logos = logos_for(&mut conn, &*state.accounts.crypto_logos, &account, lines).await?;
    Ok(axum::Json(to_response(&account, &saved, &quotes, &logos)))
}

async fn remove(
    State(state): State<AppState>,
    auth: Auth,
    Path((id, ticker)): Path<(i64, String)>,
) -> AppResult<StatusCode> {
    let mut tx = state.db.begin().await?;
    accounts::get_or_throw(&mut *tx, id, auth.member_id).await?;
    let holding = holdings::find_by_account_and_ticker(&mut *tx, id, &ticker)
        .await?
        .ok_or_else(holding_not_found)?;
    sqlx::query("DELETE FROM account_holding WHERE id = $1")
        .bind(holding.id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}
