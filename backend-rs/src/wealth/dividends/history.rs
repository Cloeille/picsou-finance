//! DividendHistoryService.refreshStale: fills dividend_source from Yahoo's two-year history,
//! complemented by Nasdaq's declared dividends and payment dates for US symbols. Track T8
//! schedules it; one ticker at a time, paced, and stopped by the Yahoo cooldown.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::time::Duration;

use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;

use crate::AppState;
use crate::db::{AsDbHandle, DbFromRow, DbHandle};
use crate::error::AppResult;
use crate::ledger::decimal::{HALF_UP, set_scale};
use crate::market::{metals, nasdaq};

/// A source refreshed less than this ago is fresh.
pub const REFRESH_AFTER: chrono::Duration = chrono::Duration::days(6);
/// A failed source is retried after this.
pub const RETRY_AFTER_FAILURE: chrono::Duration = chrono::Duration::days(3);
const PACING: Duration = Duration::from_millis(500);
const MONEY_SCALE: u32 = 8;

#[derive(Debug, Clone, PartialEq)]
struct Event {
    ex_date: NaiveDate,
    amount: Decimal,
    amount_eur: Decimal,
    payment_date: Option<NaiveDate>,
    declared: bool,
}

enum NasdaqFetch {
    NotConsulted,
    Failed,
    Rows(Vec<nasdaq::NasdaqDividend>),
}

/// What the providers said about one ticker, every one of them already asked: writing it takes
/// no further request.
enum Fetched {
    Failed(String),
    Ok {
        currency: Option<String>,
        /// Whether Yahoo itself listed a dividend, before Nasdaq's declared ones were merged in.
        had_history: bool,
        events: Vec<Event>,
        keep_future_declared: bool,
    },
}

#[derive(DbFromRow)]
struct SourceRow {
    ticker: String,
    status: String,
    refreshed_at: Option<DateTime<Utc>>,
    last_attempt_at: Option<DateTime<Utc>>,
}

/// Refreshes at most limit of the tickers whose history is due (never fetched, older than six
/// days, or failed more than three days ago), oldest first; returns how many succeeded.
pub async fn refresh_stale(state: &AppState, tickers: &[String], limit: usize) -> AppResult<usize> {
    if tickers.is_empty() || limit == 0 {
        return Ok(0);
    }
    let cooldown = state.market.cooldown();
    if cooldown.active() {
        tracing::info!(
            "Skipping dividend history pass: Yahoo cooldown is active until {}",
            cooldown.until()
        );
        return Ok(0);
    }
    let mut wanted: Vec<String> = Vec::new();
    for ticker in tickers {
        let upper = ticker.to_uppercase();
        if !metals::supports(&upper) && !wanted.contains(&upper) {
            wanted.push(upper);
        }
    }
    if wanted.is_empty() {
        return Ok(0);
    }
    let known: HashMap<String, SourceRow> = crate::db::query_as::<SourceRow>(
        "SELECT ticker, status, refreshed_at, last_attempt_at FROM dividend_source WHERE ticker = ANY($1)",
    )
    .bind(&wanted)
    .fetch_all(&state.db)
    .await?
    .into_iter()
    .map(|s| (s.ticker.to_uppercase(), s))
    .collect();
    let now = state.clock.now();
    let mut due: Vec<String> = wanted
        .into_iter()
        .filter(|t| is_due(known.get(t), now))
        .collect();
    due.sort_by(|a, b| {
        let at = |t: &String| known.get(t).and_then(|s| s.refreshed_at);
        at(a).cmp(&at(b)).then_with(|| a.cmp(b))
    });
    due.truncate(limit);

    let mut refreshed = 0;
    for (attempt, ticker) in due.iter().enumerate() {
        if cooldown.active() {
            tracing::info!("Stopping dividend history pass: Yahoo cooldown armed mid-pass");
            break;
        }
        if attempt > 0 {
            tokio::time::sleep(PACING).await;
        }
        let fetched = fetch(state, ticker, state.clock.today()).await;
        let mut tx = state.db.begin().await?;
        match persist(&mut tx.as_handle(), state, ticker, fetched).await {
            Ok(ok) => {
                tx.commit().await?;
                if ok {
                    refreshed += 1;
                }
            }
            Err(e) => tracing::warn!("Dividend history refresh failed for {ticker}: {e}"),
        }
    }
    Ok(refreshed)
}

fn is_due(source: Option<&SourceRow>, now: DateTime<Utc>) -> bool {
    let Some(source) = source else {
        return true;
    };
    if source.status == "FAILED" {
        return source
            .last_attempt_at
            .is_none_or(|at| at < now - RETRY_AFTER_FAILURE);
    }
    source
        .refreshed_at
        .is_none_or(|at| at <= now - REFRESH_AFTER)
}

/// US symbols only: a dotted Yahoo symbol is listed elsewhere.
fn nasdaq_eligible(ticker: &str) -> bool {
    !ticker.trim().is_empty() && !ticker.contains('.')
}

async fn fetch_nasdaq(state: &AppState, ticker: &str) -> NasdaqFetch {
    if !nasdaq_eligible(ticker) {
        return NasdaqFetch::NotConsulted;
    }
    match nasdaq::fetch_dividends(&state.http, &state.config.nasdaq_base_url, ticker).await {
        Ok(rows) => NasdaqFetch::Rows(rows),
        Err(e) => {
            tracing::warn!("Nasdaq dividend complement failed for {ticker}: {e}");
            NasdaqFetch::Failed
        }
    }
}

async fn fetch(state: &AppState, ticker: &str, today: NaiveDate) -> Fetched {
    let history = match state.market.yahoo().fetch_dividend_history(ticker).await {
        Ok(history) => history,
        Err(e) => return Fetched::Failed(e.to_string()),
    };
    let (currency, events, rate) = if history.events.is_empty() {
        (history.currency, Vec::new(), None)
    } else {
        let Some(currency) = history.currency.clone().filter(|c| !c.trim().is_empty()) else {
            return Fetched::Failed("dividend currency missing".to_owned());
        };
        let Some(rate) = state.market.yahoo().fx_rate_to_eur(Some(&currency)).await else {
            return Fetched::Failed(format!("FX rate unavailable for {currency}"));
        };
        let mut by_date: BTreeMap<NaiveDate, Decimal> = BTreeMap::new();
        for point in &history.events {
            by_date.insert(point.ex_date, point.amount);
        }
        let events: Vec<Event> = by_date
            .into_iter()
            .map(|(ex_date, amount)| Event {
                ex_date,
                amount,
                amount_eur: set_scale(amount * rate, MONEY_SCALE, HALF_UP),
                payment_date: None,
                declared: false,
            })
            .collect();
        (Some(currency), events, Some(rate))
    };
    let had_history = !events.is_empty();
    let nasdaq = fetch_nasdaq(state, ticker).await;
    // Merged here, not while writing: a Nasdaq row in another currency asks for its rate.
    let (events, keep_future_declared) = complement(
        state,
        ticker,
        events,
        rate,
        currency.as_deref(),
        nasdaq,
        today,
    )
    .await;
    Fetched::Ok {
        currency,
        had_history,
        events,
        keep_future_declared,
    }
}

/// Writes the outcome; true unless the source ended FAILED.
async fn persist(
    conn: &mut DbHandle<'_>,
    state: &AppState,
    ticker: &str,
    fetched: Fetched,
) -> AppResult<bool> {
    let now = state.clock.now();
    let today = state.clock.today();
    let source_id: i64 = crate::db::query_scalar::<i64>(
        "INSERT INTO dividend_source (ticker, status, created_at, updated_at) VALUES ($1, 'NEVER_FETCHED', $2, $2) \
         ON CONFLICT (ticker) DO UPDATE SET updated_at = $2 RETURNING id",
    )
    .bind(ticker)
    .bind(now)
    .fetch_one(&mut *conn)
    .await?;
    let (currency, had_history, incoming, keep_future_declared) = match fetched {
        Fetched::Failed(message) => {
            let message: String = message.chars().take(200).collect();
            tracing::warn!("Dividend history lookup failed for {ticker}: {message}");
            crate::db::query("UPDATE dividend_source SET status = 'FAILED', last_error = $2, last_attempt_at = $3 WHERE id = $1")
                .bind(source_id)
                .bind(&message)
                .bind(now)
                .execute(&mut *conn)
                .await?;
            return Ok(false);
        }
        Fetched::Ok {
            currency,
            had_history,
            events,
            keep_future_declared,
        } => (currency, had_history, events, keep_future_declared),
    };
    write_events(conn, source_id, &incoming, keep_future_declared, today, now).await?;
    let count: i64 =
        crate::db::query_scalar::<i64>("SELECT count(*) FROM dividend_event WHERE source_id = $1")
            .bind(source_id)
            .fetch_one(&mut *conn)
            .await?;
    let status = if had_history || count > 0 {
        "OK"
    } else {
        "NO_DATA"
    };
    crate::db::query(
        "UPDATE dividend_source SET status = $2, currency = COALESCE($3, currency), last_error = NULL, \
         refreshed_at = $4, last_attempt_at = $4, updated_at = $4 WHERE id = $1",
    )
    .bind(source_id)
    .bind(status)
    .bind(currency.filter(|c| !c.trim().is_empty()))
    .bind(now)
    .execute(&mut *conn)
    .await?;
    Ok(true)
}

/// Merges Nasdaq into Yahoo's events: its payment dates, and its declared future dividends.
/// The flag says whether a stored declared future event absent from this pass survives.
async fn complement(
    state: &AppState,
    ticker: &str,
    mut events: Vec<Event>,
    rate: Option<Decimal>,
    currency: Option<&str>,
    nasdaq: NasdaqFetch,
    today: NaiveDate,
) -> (Vec<Event>, bool) {
    mark_future_declared(&mut events, today);
    let rows = match nasdaq {
        NasdaqFetch::NotConsulted => return (events, false),
        NasdaqFetch::Failed => return (events, true),
        NasdaqFetch::Rows(rows) if rows.is_empty() => return (events, true),
        NasdaqFetch::Rows(rows) => rows,
    };
    for row in rows {
        let needed = row.currency.clone().or_else(|| currency.map(str::to_owned));
        if let Some(existing) = events.iter_mut().find(|e| e.ex_date == row.ex_date) {
            if row.payment_date.is_some() {
                existing.payment_date = row.payment_date;
            }
            if row.ex_date > today {
                existing.declared = true;
                match rate_for(state, needed.as_deref(), rate, currency).await {
                    Some(r) => {
                        existing.amount = row.amount;
                        existing.amount_eur = set_scale(row.amount * r, MONEY_SCALE, HALF_UP);
                    }
                    None => tracing::warn!(
                        "Nasdaq amount for {ticker} on {} left unchanged: no FX rate",
                        row.ex_date
                    ),
                }
            }
            continue;
        }
        if row.ex_date <= today {
            continue;
        }
        let Some(r) = rate_for(state, needed.as_deref(), rate, currency).await else {
            tracing::warn!("Nasdaq future dividend for {ticker} skipped: no FX rate");
            continue;
        };
        events.push(Event {
            ex_date: row.ex_date,
            amount: row.amount,
            amount_eur: set_scale(row.amount * r, MONEY_SCALE, HALF_UP),
            payment_date: row.payment_date,
            declared: true,
        });
    }
    mark_future_declared(&mut events, today);
    (events, true)
}

fn mark_future_declared(events: &mut [Event], today: NaiveDate) {
    for event in events.iter_mut().filter(|e| e.ex_date > today) {
        event.declared = true;
    }
}

async fn rate_for(
    state: &AppState,
    needed: Option<&str>,
    known: Option<Decimal>,
    known_currency: Option<&str>,
) -> Option<Decimal> {
    let needed = needed.filter(|n| !n.trim().is_empty())?;
    if needed.eq_ignore_ascii_case("EUR") {
        return Some(Decimal::ONE);
    }
    if let (Some(rate), Some(currency)) = (known, known_currency)
        && needed.eq_ignore_ascii_case(currency)
    {
        return Some(rate);
    }
    state.market.yahoo().fx_rate_to_eur(Some(needed)).await
}

/// Upserts the incoming events by ex-date and drops the stored ones this pass no longer shows,
/// keeping a declared future one when Nasdaq could not be read.
async fn write_events(
    conn: &mut DbHandle<'_>,
    source_id: i64,
    incoming: &[Event],
    keep_future_declared: bool,
    today: NaiveDate,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    let stored: Vec<(NaiveDate, bool)> = crate::db::query_as::<(NaiveDate, bool)>(
        "SELECT ex_date, declared FROM dividend_event WHERE source_id = $1",
    )
    .bind(source_id)
    .fetch_all(&mut *conn)
    .await?;
    let incoming_dates: HashSet<NaiveDate> = incoming.iter().map(|e| e.ex_date).collect();
    for (ex_date, declared) in stored {
        let keep = incoming_dates.contains(&ex_date)
            || (ex_date > today && keep_future_declared && declared);
        if !keep {
            crate::db::query("DELETE FROM dividend_event WHERE source_id = $1 AND ex_date = $2")
                .bind(source_id)
                .bind(ex_date)
                .execute(&mut *conn)
                .await?;
        }
    }
    for event in incoming {
        let declared = event.declared || event.ex_date > today;
        crate::db::query(
            "INSERT INTO dividend_event (source_id, ex_date, amount, amount_eur, payment_date, declared, created_at, updated_at) \
             VALUES ($1, $2, $3, $4, $5, $6, $7, $7) ON CONFLICT (source_id, ex_date) DO UPDATE SET \
             amount = EXCLUDED.amount, amount_eur = EXCLUDED.amount_eur, \
             payment_date = COALESCE(EXCLUDED.payment_date, dividend_event.payment_date), \
             declared = EXCLUDED.declared, updated_at = EXCLUDED.updated_at",
        )
        .bind(source_id)
        .bind(event.ex_date)
        .bind(event.amount)
        .bind(event.amount_eur)
        .bind(event.payment_date)
        .bind(declared)
        .bind(now)
        .execute(&mut *conn)
        .await?;
    }
    Ok(())
}
