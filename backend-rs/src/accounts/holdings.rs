//! The holdings read model (AccountService.getHoldings / toHoldingResponse) and the two edits
//! a member makes on a line: its quantity and buy-in, or its removal.

use std::cmp::Ordering;
use std::collections::{BTreeSet, HashMap};

use crate::db::DbHandle;
use axum::extract::State;
use axum::http::StatusCode;
use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

use super::scale::column;
use crate::AppState;
use crate::accounts::analysis::math::jmul;
use crate::accounts::instrument_logo::{self, LogoUrls};
use crate::auth::Auth;
use crate::db::{self, DbExec};
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
        if let (Some(quantity), Some(buy_in)) = (self.quantity, self.average_buy_in)
            && quantity.checked_mul(buy_in).is_none()
        {
            let message = errors.default_message(
                "numeric value out of bounds",
                "valeur numérique hors limites",
            );
            errors.add("averageBuyIn", message);
        }
    }
}

/// The valued lines of an account the caller has already authorised for reading.
pub async fn holdings_of(
    conn: &mut DbHandle<'_>,
    prices: &dyn PriceRead,
    crypto_logos: &dyn crate::accounts::positions::CryptoLogoRead,
    account: &Account,
    today: NaiveDate,
) -> sqlx::Result<Vec<HoldingResponse>> {
    let lines = by_price_desc(&mut *conn, account.id).await?;
    let quotes = valuation::quotes_for(prices, account, &lines, today).await;
    let logos = logos_for(&mut *conn, crypto_logos, account, &lines).await?;
    Ok(lines
        .iter()
        .map(|h| to_response(account, h, &quotes, &logos))
        .collect())
}

/// getHoldings: the lines of an account the member owns (a co-owner gets the 404, as in Java).
pub async fn owned_holdings(
    conn: &mut DbHandle<'_>,
    prices: &dyn PriceRead,
    crypto_logos: &dyn crate::accounts::positions::CryptoLogoRead,
    account_id: i64,
    member_id: i64,
    today: NaiveDate,
) -> AppResult<Vec<HoldingResponse>> {
    let account = accounts::get_or_throw(&mut *conn, account_id, member_id).await?;
    Ok(holdings_of(conn, prices, crypto_logos, &account, today).await?)
}

/// findByAccountIdOrderByCurrentPriceDesc as PostgreSQL answers it for Java:
/// `ORDER BY current_price DESC NULLS FIRST, ticker ASC`, sorted in Rust with exact `Decimal`
/// comparison so SQLite (which stores `NUMERIC` as canonical `TEXT`) produces identical ordering.
async fn by_price_desc(db: impl DbExec<'_>, account_id: i64) -> sqlx::Result<Vec<AccountHolding>> {
    let mut rows: Vec<AccountHolding> = db::query_as(&format!(
        "SELECT {HOLDING_COLUMNS} FROM account_holding h WHERE h.account_id = $1 \
         ORDER BY h.ticker"
    ))
    .bind(account_id)
    .fetch_all(db)
    .await?;
    rows.iter_mut().for_each(fix_holding);
    sort_by_price_desc(&mut rows);
    Ok(rows)
}

fn sort_by_price_desc(rows: &mut [AccountHolding]) {
    rows.sort_by(|a, b| match (a.current_price, b.current_price) {
        (None, None) => a.ticker.cmp(&b.ticker),
        (None, Some(_)) => Ordering::Less,
        (Some(_), None) => Ordering::Greater,
        (Some(pa), Some(pb)) => pb.cmp(&pa).then_with(|| a.ticker.cmp(&b.ticker)),
    });
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
    db: impl DbExec<'_>,
    crypto_logos: &dyn crate::accounts::positions::CryptoLogoRead,
    account: &Account,
    lines: &[AccountHolding],
) -> sqlx::Result<HashMap<String, LogoUrls>> {
    let tickers: BTreeSet<String> = valuation::tickers_of(lines);
    if tickers.is_empty() {
        return Ok(HashMap::new());
    }
    if account.account_type != AccountType::Crypto {
        return instrument_logo::stored_urls(db, &tickers).await;
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
        let overruled = usable.is_some_and(|q| {
            valuation::provider_overrules_quote(
                account,
                quantity,
                q.price,
                holding.provider_value_eur,
            )
        });
        if overruled {
            // The line falls back to the provider value below. Unlike a missing quote it is
            // flagged stale and dated by the provider: the figure shown is the bank's last
            // valuation, not a market price.
            price_as_of = holding.provider_price_date;
            price_stale = true;
        } else if let Some(q) = usable {
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
    let mut conn = state.db.pool_handle();
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
    let account = accounts::get_or_throw(&mut tx, id, auth.member_id).await?;
    let holding = holdings::find_by_account_and_ticker(&mut tx, id, &ticker)
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
    let mut saved = holdings::update(&mut tx, holding.id, &draft, state.clock.now()).await?;
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
    let mut conn = state.db.pool_handle();
    let logos = logos_for(&mut conn, &*state.accounts.crypto_logos, &account, lines).await?;
    Ok(axum::Json(to_response(&account, &saved, &quotes, &logos)))
}

async fn remove(
    State(state): State<AppState>,
    auth: Auth,
    Path((id, ticker)): Path<(i64, String)>,
) -> AppResult<StatusCode> {
    let mut tx = state.db.begin().await?;
    accounts::get_or_throw(&mut tx, id, auth.member_id).await?;
    let holding = holdings::find_by_account_and_ticker(&mut tx, id, &ticker)
        .await?
        .ok_or_else(holding_not_found)?;
    db::query("DELETE FROM account_holding WHERE id = $1")
        .bind(holding.id)
        .execute(&mut tx)
        .await?;
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}

#[cfg(test)]
mod tests {
    use chrono::DateTime;
    use rust_decimal::Decimal;

    use super::*;
    use crate::db::Db;
    use crate::db::sqlite::{SqliteDb, migrate};

    fn stub_holding(ticker: &str, price: Option<&str>) -> AccountHolding {
        let epoch = DateTime::from_timestamp(0, 0).unwrap();
        AccountHolding {
            id: 1,
            account_id: 1,
            ticker: ticker.to_owned(),
            name: None,
            quantity: Decimal::ONE,
            average_buy_in: None,
            current_price: price.map(|p| p.parse().unwrap()),
            quote_currency: None,
            provider_value_eur: None,
            provider_pnl_eur: None,
            provider_price_date: None,
            provider_wealth_tier: None,
            last_synced_at: None,
            created_at: epoch,
            updated_at: epoch,
        }
    }

    #[test]
    fn sort_by_price_desc_matches_pg_nulls_first_and_exact_decimal() {
        let mut rows = vec![
            stub_holding("B_NINE", Some("9.5")),
            stub_holding("Z_NONE", None),
            stub_holding("A_NINE", Some("9.50")),
            stub_holding("HUNDRED", Some("100")),
            stub_holding("TWENTY", Some("20")),
            stub_holding("A_NONE", None),
        ];
        sort_by_price_desc(&mut rows);
        let tickers: Vec<&str> = rows.iter().map(|h| h.ticker.as_str()).collect();
        assert_eq!(
            tickers,
            vec!["A_NONE", "Z_NONE", "HUNDRED", "TWENTY", "A_NINE", "B_NINE"]
        );
    }

    #[tokio::test]
    async fn by_price_desc_on_sqlite_sorts_exact_decimal_with_nulls_first() {
        let sq = SqliteDb::open_in_memory().unwrap();
        sq.with_conn(|c| Ok(migrate::migrate(c).unwrap())).unwrap();
        let db = Db::from(sq);

        let member_id: i64 =
            db::query_scalar("INSERT INTO family_member (display_name) VALUES ($1) RETURNING id")
                .bind("Alice")
                .fetch_one(&db)
                .await
                .unwrap();
        let account_id: i64 = db::query_scalar(
            "INSERT INTO account (name, type, member_id) VALUES ($1, 'PEA', $2) RETURNING id",
        )
        .bind("PEA")
        .bind(member_id)
        .fetch_one(&db)
        .await
        .unwrap();

        for (ticker, price) in [
            ("B_NINE", Some("9.5")),
            ("Z_NONE", None),
            ("A_NINE", Some("9.50")),
            ("HUNDRED", Some("100")),
            ("TWENTY", Some("20")),
            ("A_NONE", None),
        ] {
            db::query(
                "INSERT INTO account_holding (account_id, ticker, quantity, current_price) \
                 VALUES ($1, $2, $3, $4)",
            )
            .bind(account_id)
            .bind(ticker)
            .bind(Decimal::ONE)
            .bind(price.map(|p| p.parse::<Decimal>().unwrap()))
            .execute(&db)
            .await
            .unwrap();
        }

        let rows = by_price_desc(&db, account_id).await.unwrap();
        let tickers: Vec<&str> = rows.iter().map(|h| h.ticker.as_str()).collect();
        assert_eq!(
            tickers,
            vec!["A_NONE", "Z_NONE", "HUNDRED", "TWENTY", "A_NINE", "B_NINE"]
        );
    }
}
