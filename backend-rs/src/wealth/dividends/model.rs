//! dividend_source / dividend_event rows and the cadence a history implies.

use std::collections::HashMap;

use chrono::NaiveDate;
use rust_decimal::Decimal;
use serde::Serialize;
use sqlx::PgPool;

use crate::db::DbFromRow;

/// DividendCadence: (months, min gap days, max gap days), fastest first.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Cadence {
    Monthly,
    Quarterly,
    SemiAnnual,
    Annual,
}

impl Cadence {
    pub const ALL: [Cadence; 4] = [
        Self::Monthly,
        Self::Quarterly,
        Self::SemiAnnual,
        Self::Annual,
    ];

    pub fn months(self) -> u32 {
        match self {
            Self::Monthly => 1,
            Self::Quarterly => 3,
            Self::SemiAnnual => 6,
            Self::Annual => 12,
        }
    }

    fn gap_bounds(self) -> (i64, i64) {
        match self {
            Self::Monthly => (20, 40),
            Self::Quarterly => (75, 105),
            Self::SemiAnnual => (160, 200),
            Self::Annual => (330, 400),
        }
    }

    /// Annual needs two ex-dates; anything faster three, so one odd gap cannot pass for it.
    pub fn min_events(self) -> usize {
        if self == Self::Annual { 2 } else { 3 }
    }

    pub fn accepts_gap(self, days: i64) -> bool {
        let (min, max) = self.gap_bounds();
        days >= min && days <= max
    }
}

#[derive(Debug, Clone, PartialEq, DbFromRow)]
pub struct DividendEvent {
    pub source_id: i64,
    pub ex_date: NaiveDate,
    pub amount: Decimal,
    pub amount_eur: Decimal,
    pub payment_date: Option<NaiveDate>,
    pub declared: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct DividendSource {
    pub id: i64,
    pub ticker: String,
    pub currency: Option<String>,
    pub events: Vec<DividendEvent>,
}

/// findAllWithEventsByTickerIn, keyed by upper-cased ticker.
pub async fn sources_by_ticker(
    db: &PgPool,
    tickers: &[String],
) -> sqlx::Result<HashMap<String, DividendSource>> {
    let upper: Vec<String> = tickers
        .iter()
        .filter(|t| !t.trim().is_empty())
        .map(|t| t.to_uppercase())
        .collect();
    if upper.is_empty() {
        return Ok(HashMap::new());
    }
    let rows: Vec<(i64, String, Option<String>)> =
        crate::db::query_as::<(i64, String, Option<String>)>(
            "SELECT id, ticker, currency FROM dividend_source WHERE ticker = ANY($1) ORDER BY id",
        )
        .bind(&upper)
        .fetch_all(db)
        .await?;
    let ids: Vec<i64> = rows.iter().map(|r| r.0).collect();
    let events: Vec<DividendEvent> = crate::db::query_as::<DividendEvent>(
        "SELECT source_id, ex_date, amount, amount_eur, payment_date, declared FROM dividend_event \
         WHERE source_id = ANY($1) ORDER BY source_id, ex_date",
    )
    .bind(&ids)
    .fetch_all(db)
    .await?;
    let mut out = HashMap::new();
    for (id, ticker, currency) in rows {
        let key = ticker.to_uppercase();
        if out.contains_key(&key) {
            continue;
        }
        let source_events = events
            .iter()
            .filter(|e| e.source_id == id)
            .cloned()
            .collect();
        out.insert(
            key,
            DividendSource {
                id,
                ticker,
                currency,
                events: source_events,
            },
        );
    }
    Ok(out)
}
