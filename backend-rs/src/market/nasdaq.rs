//! Nasdaq dividend calendar and history (NasdaqDividendProvider): declared future dividends
//! and payment dates for US listings, which Yahoo's history lacks.

use std::time::Duration;

use chrono::NaiveDate;
use rust_decimal::Decimal;
use serde_json::Value;

use super::yahoo::{PATH_SEGMENT, transport_message};

const TIMEOUT: Duration = Duration::from_secs(10);
const MAX_BODY_BYTES: usize = 512 * 1024;
const MAX_ROWS: usize = 200;
const USER_AGENT: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) \
     Chrome/120.0.0.0 Safari/537.36";

/// NasdaqDividend.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NasdaqDividend {
    pub ex_date: NaiveDate,
    pub payment_date: Option<NaiveDate>,
    pub record_date: Option<NaiveDate>,
    pub declaration_date: Option<NaiveDate>,
    pub amount: Decimal,
    pub currency: Option<String>,
}

/// fetchDividends: the symbol's dividend rows; an error for a failed call or an unusable body
/// (the caller keeps what it already knows), an empty list for a symbol with none.
pub async fn fetch_dividends(
    http: &reqwest::Client,
    base_url: &str,
    symbol: &str,
) -> Result<Vec<NasdaqDividend>, String> {
    if symbol.trim().is_empty() {
        return Ok(Vec::new());
    }
    let url = format!(
        "{}/api/quote/{}/dividends?assetclass=stocks",
        base_url.trim_end_matches('/'),
        percent_encoding::utf8_percent_encode(symbol, PATH_SEGMENT)
    );
    let response = http
        .get(url)
        .header("Accept", "application/json, text/plain, */*")
        .header("Origin", "https://www.nasdaq.com")
        .header("Referer", "https://www.nasdaq.com/")
        .header("User-Agent", USER_AGENT)
        .timeout(TIMEOUT)
        .send()
        .await
        .map_err(|e| transport_message(&e))?;
    if !response.status().is_success() {
        return Err(format!("nasdaq HTTP {}", response.status().as_u16()));
    }
    let body = response.bytes().await.map_err(|e| transport_message(&e))?;
    if body.len() > MAX_BODY_BYTES {
        return Err("nasdaq body too large".to_owned());
    }
    parse(&String::from_utf8_lossy(&body))
}

pub fn parse(body: &str) -> Result<Vec<NasdaqDividend>, String> {
    if body.trim().is_empty() {
        return Err("nasdaq returned an empty body".to_owned());
    }
    let root: Value = serde_json::from_str(body)
        .map_err(|_| "nasdaq returned a body that is not JSON".to_owned())?;
    if let Some(code) = root
        .get("status")
        .and_then(|s| s.get("rCode"))
        .and_then(Value::as_i64)
        && code != 200
    {
        return Err(format!("nasdaq rCode {code}"));
    }
    let Some(rows) = root
        .get("data")
        .and_then(|d| d.get("dividends"))
        .and_then(|d| d.get("rows"))
        .and_then(Value::as_array)
    else {
        return Ok(Vec::new());
    };
    let mut parsed: Vec<NasdaqDividend> = rows.iter().filter_map(row_of).collect();
    if parsed.len() > MAX_ROWS {
        parsed.sort_by_key(|d| d.ex_date);
        parsed.drain(..parsed.len() - MAX_ROWS);
    }
    Ok(parsed)
}

fn text(row: &Value, key: &str) -> Option<String> {
    let value = match row.get(key)? {
        Value::String(s) => s.trim().to_owned(),
        Value::Null => return None,
        other => other.to_string(),
    };
    (!value.is_empty()).then_some(value)
}

fn date(row: &Value, key: &str) -> Option<NaiveDate> {
    let value = text(row, key).filter(|v| !v.eq_ignore_ascii_case("N/A"))?;
    NaiveDate::parse_from_str(&value, "%m/%d/%Y")
        .ok()
        .filter(|_| value.len() == 10)
}

fn row_of(row: &Value) -> Option<NasdaqDividend> {
    let ex_date = date(row, "exOrEffDate")?;
    let raw = text(row, "amount").filter(|v| !v.eq_ignore_ascii_case("N/A"))?;
    let number = raw.replace(['$', ','], "");
    let number = number.trim();
    let amount = if number.contains(['e', 'E']) {
        Decimal::from_scientific(number).ok()?
    } else {
        number.parse().ok()?
    };
    Some(NasdaqDividend {
        ex_date,
        payment_date: date(row, "paymentDate"),
        record_date: date(row, "recordDate"),
        declaration_date: date(row, "declarationDate"),
        amount,
        currency: text(row, "currency"),
    })
}
