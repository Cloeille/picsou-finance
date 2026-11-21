//! IbkrFlexClient: the two-step Flex Web Service flow (SendRequest, then GetStatement polled
//! until the statement is generated) and the Open Positions parser.

use std::str::FromStr;
use std::time::Duration;

use axum::http::header::USER_AGENT;
use rust_decimal::Decimal;

use super::sync_error;
use super::xml::{self, Element};
use crate::AppState;
use crate::error::{AppError, AppResult};
use crate::ledger::sync_values::{java_is_blank, java_trim};

const VERSION: &str = "3";
const TIMEOUT: Duration = Duration::from_secs(30);
/// How many times GetStatement is polled while IBKR is still generating the report.
const MAX_POLLS: u32 = 8;
/// Base wait between polls; IBKR needs a few seconds and rate-limits to about one request a second.
const POLL_INTERVAL: Duration = Duration::from_secs(3);

/// One open position as IBKR reports it; amounts in the security's native currency, which
/// fx_rate_to_base converts to the account's IBKR base currency.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IbkrPosition {
    pub account_id: Option<String>,
    pub isin: Option<String>,
    pub symbol: Option<String>,
    pub description: Option<String>,
    pub currency: Option<String>,
    pub asset_category: Option<String>,
    /// "SUMMARY" or "LOT".
    pub level_of_detail: Option<String>,
    pub position: Option<Decimal>,
    pub mark_price: Option<Decimal>,
    pub cost_basis_price: Option<Decimal>,
    pub fx_rate_to_base: Option<Decimal>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IbkrAccountData {
    pub account_id: String,
    pub positions: Vec<IbkrPosition>,
    /// From the optional AccountInformation section; None when the query does not enable it.
    pub base_currency: Option<String>,
}

/// Runs the Open Positions Flex Query and returns the positions grouped by IBKR account.
pub async fn fetch_open_positions(
    state: &AppState,
    token: &str,
    query_id: &str,
) -> AppResult<Vec<IbkrAccountData>> {
    let base = state.config.sync_url("ibkr-flex");
    let reference_code = send_request(state, base, token, query_id).await?;
    let statement = poll_statement(state, base, token, &reference_code).await?;
    parse_open_positions(&statement)
}

async fn send_request(
    state: &AppState,
    base: &str,
    token: &str,
    query_id: &str,
) -> AppResult<String> {
    let url = format!(
        "{base}/SendRequest?t={}&q={}&v={VERSION}",
        url_encode(token),
        url_encode(query_id)
    );
    let root = parse(&get(state, &url).await?)?;
    let status = child_text(&root, "Status");
    if status
        .as_deref()
        .is_some_and(|s| s.eq_ignore_ascii_case("Success"))
    {
        let reference_code = child_text(&root, "ReferenceCode")
            .filter(|code| !java_is_blank(code))
            .ok_or_else(|| {
                sync_error("IBKR did not return a reference code. Check your token and query id.")
            })?;
        tracing::info!("IBKR Flex SendRequest OK");
        return Ok(reference_code);
    }
    Err(fail_from_error(
        &root,
        &format!(
            "IBKR rejected the Flex request (root={}, status={})",
            root.name,
            status.as_deref().unwrap_or("null")
        ),
    ))
}

async fn poll_statement(
    state: &AppState,
    base: &str,
    token: &str,
    reference_code: &str,
) -> AppResult<String> {
    let url = format!(
        "{base}/GetStatement?t={}&q={}&v={VERSION}",
        url_encode(token),
        url_encode(reference_code)
    );
    for attempt in 0..MAX_POLLS {
        let body = get(state, &url).await?;
        let root = parse(&body)?;
        if root.name == "FlexQueryResponse" {
            return Ok(body);
        }
        if is_generation_in_progress(&root) {
            tracing::info!(
                "IBKR statement not ready yet (attempt {}/{MAX_POLLS}), waiting {}s",
                attempt + 1,
                POLL_INTERVAL.as_secs()
            );
            tokio::time::sleep(POLL_INTERVAL * (1 + attempt / 2)).await;
            continue;
        }
        let status = child_text(&root, "Status");
        return Err(fail_from_error(
            &root,
            &format!(
                "IBKR could not deliver the statement (root={}, status={})",
                root.name,
                status.as_deref().unwrap_or("null")
            ),
        ));
    }
    Err(sync_error(format!(
        "IBKR statement was still generating after {MAX_POLLS} attempts. Please try again in a minute."
    )))
}

/// IBKR signals a statement still being generated with error code 1019; the message is matched
/// too so a renumbered code does not turn a transient wait into a hard failure.
fn is_generation_in_progress(root: &Element) -> bool {
    if child_text(root, "ErrorCode").as_deref() == Some("1019") {
        return true;
    }
    child_text(root, "ErrorMessage").is_some_and(|message| {
        let lower = message.to_lowercase();
        lower.contains("in progress") || lower.contains("try again") || lower.contains("generat")
    })
}

fn fail_from_error(root: &Element, context: &str) -> AppError {
    let code = child_text(root, "ErrorCode");
    let message = child_text(root, "ErrorMessage");
    tracing::error!(
        "IBKR Flex error (code={}, message={})",
        code.as_deref().unwrap_or("null"),
        message.as_deref().unwrap_or("null")
    );
    let detail = message.as_deref().unwrap_or(context);
    let suffix = code.map(|c| format!(" (code {c})")).unwrap_or_default();
    sync_error(format!("Interactive Brokers: {detail}{suffix}"))
}

async fn get(state: &AppState, url: &str) -> AppResult<String> {
    let unreachable = |error: reqwest::Error| {
        let reason = if error.is_timeout() {
            "request timed out".to_owned()
        } else {
            // without_url: the URL carries the Flex token.
            error.without_url().to_string()
        };
        sync_error(format!("Unable to reach Interactive Brokers: {reason}"))
    };
    let response = state
        .http
        .get(url)
        .timeout(TIMEOUT)
        .header(USER_AGENT, "picsou-finance")
        .send()
        .await
        .map_err(unreachable)?;
    let status = response.status();
    if status.as_u16() >= 400 {
        return Err(sync_error(format!(
            "Interactive Brokers returned HTTP {}. Please check your token and try again.",
            status.as_u16()
        )));
    }
    let body = response.text().await.map_err(unreachable)?;
    if java_is_blank(&body) {
        return Err(sync_error(
            "Interactive Brokers returned an empty response. Please try again.",
        ));
    }
    Ok(body)
}

fn parse(body: &str) -> AppResult<Element> {
    xml::parse(body).map_err(|_| {
        // The length only: a statement carries the member's positions and account ids.
        tracing::error!(
            "Failed to parse IBKR Flex response as XML ({} chars)",
            body.encode_utf16().count()
        );
        sync_error("Interactive Brokers returned an unexpected (non-XML) response.")
    })
}

/// The trimmed text of the first descendant with that tag.
fn child_text(parent: &Element, tag: &str) -> Option<String> {
    parent
        .descendants(tag)
        .first()
        .map(|element| java_trim(&element.text_content()).to_owned())
}

fn attr(element: &Element, name: &str) -> Option<String> {
    element.attribute(name).map(|v| java_trim(v).to_owned())
}

fn non_blank(element: &Element, name: &str) -> Option<String> {
    attr(element, name).filter(|v| !java_is_blank(v))
}

/// A numeric attribute; absent, blank or unparseable is None.
fn decimal(element: &Element, name: &str) -> Option<Decimal> {
    let raw = attr(element, name).filter(|v| !java_is_blank(v))?;
    let parsed = parse_big_decimal(&raw);
    if parsed.is_none() {
        tracing::warn!("IBKR position: non-numeric {name}='{raw}', treating as null");
    }
    parsed
}

/// new BigDecimal(String): an optional sign, digits with an optional point, an optional exponent.
fn parse_big_decimal(raw: &str) -> Option<Decimal> {
    let (mantissa, exponent) = match raw.find(['e', 'E']) {
        Some(at) => (&raw[..at], Some(&raw[at + 1..])),
        None => (raw, None),
    };
    let unsigned = mantissa.strip_prefix(['+', '-']).unwrap_or(mantissa);
    let (whole, fraction) = unsigned.split_once('.').unwrap_or((unsigned, ""));
    let digits_only = |s: &str| s.bytes().all(|b| b.is_ascii_digit());
    if (whole.is_empty() && fraction.is_empty()) || !digits_only(whole) || !digits_only(fraction) {
        return None;
    }
    let mut normalized = String::with_capacity(mantissa.len() + 1);
    if mantissa.starts_with('-') {
        normalized.push('-');
    }
    normalized.push_str(if whole.is_empty() { "0" } else { whole });
    if !fraction.is_empty() {
        normalized.push('.');
        normalized.push_str(fraction);
    }
    let value = Decimal::from_str_exact(&normalized).ok()?;
    match exponent {
        None => Some(value),
        Some(exponent) => {
            let unsigned = exponent.strip_prefix(['+', '-']).unwrap_or(exponent);
            if unsigned.is_empty() || !digits_only(unsigned) {
                return None;
            }
            let shift = i64::from_str(exponent.strip_prefix('+').unwrap_or(exponent)).ok()?;
            let scale = i64::from(value.scale()) - shift;
            if scale >= 0 {
                let mut shifted = value;
                shifted.set_scale(u32::try_from(scale).ok()?).ok()?;
                Some(shifted)
            } else {
                let factor = Decimal::from_i128_with_scale(
                    10_i128.checked_pow(u32::try_from(-scale).ok()?)?,
                    0,
                );
                let mut unscaled = value;
                unscaled.set_scale(0).ok()?;
                unscaled.checked_mul(factor)
            }
        }
    }
}

/// URLEncoder.encode(s, UTF_8): form encoding, space as +.
fn url_encode(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for byte in value.bytes() {
        match byte {
            b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'.' | b'-' | b'*' | b'_' => {
                out.push(char::from(byte));
            }
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}

/// The OpenPosition rows of a FlexQueryResponse grouped by account, in document order. Accounts
/// come from the FlexStatement elements too, so a fully liquidated account is still returned
/// (with no position) and its stale holdings get purged.
pub fn parse_open_positions(statement: &str) -> AppResult<Vec<IbkrAccountData>> {
    let root = parse(statement)?;
    let base_currencies: Vec<(String, String)> = root
        .self_and_descendants("AccountInformation")
        .into_iter()
        .filter_map(|info| Some((non_blank(info, "accountId")?, non_blank(info, "currency")?)))
        .collect();

    let mut accounts: Vec<(String, Vec<IbkrPosition>)> = Vec::new();
    for statement in root.self_and_descendants("FlexStatement") {
        let Some(account_id) = non_blank(statement, "accountId") else {
            continue;
        };
        if accounts.iter().any(|(id, _)| *id == account_id) {
            // One section per day of a multi-day query: merging them would multiply every
            // quantity by the number of days.
            return Err(sync_error(format!(
                "The Flex statement contains several sections for account {account_id} — set the \
                 Flex Query period to 'Last Business Day' (and disable any day-by-day breakout) so \
                 positions are a single snapshot."
            )));
        }
        accounts.push((account_id, Vec::new()));
    }

    let rows = root.self_and_descendants("OpenPosition");
    for row in &rows {
        let position = IbkrPosition {
            account_id: attr(row, "accountId"),
            isin: non_blank(row, "isin"),
            symbol: non_blank(row, "symbol"),
            description: non_blank(row, "description"),
            currency: non_blank(row, "currency"),
            asset_category: non_blank(row, "assetCategory"),
            level_of_detail: non_blank(row, "levelOfDetail"),
            position: decimal(row, "position"),
            mark_price: decimal(row, "markPrice"),
            cost_basis_price: decimal(row, "costBasisPrice"),
            fx_rate_to_base: decimal(row, "fxRateToBase"),
        };
        let key = position.account_id.clone().unwrap_or_default();
        match accounts.iter_mut().find(|(id, _)| *id == key) {
            Some((_, positions)) => positions.push(position),
            None => accounts.push((key, vec![position])),
        }
    }

    let result: Vec<IbkrAccountData> = accounts
        .into_iter()
        .map(|(account_id, positions)| {
            let base_currency = base_currencies
                .iter()
                .rev()
                .find(|(id, _)| *id == account_id)
                .map(|(_, currency)| currency.clone());
            IbkrAccountData {
                account_id,
                positions,
                base_currency,
            }
        })
        .collect();
    tracing::info!(
        "IBKR Flex parsed {} open positions across {} account(s)",
        rows.len(),
        result.len()
    );
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(value: &str) -> Decimal {
        value.parse().expect("decimal")
    }

    /// Two SUMMARY rows and a LOT duplicate, with IBKR's own attribute names.
    const FIXTURE: &str = r#"
        <FlexQueryResponse queryName="OpenPositions" type="AF">
          <FlexStatements count="1">
            <FlexStatement accountId="U1234567" fromDate="20260101" toDate="20260718"
                           period="LastBusinessDay" whenGenerated="20260719;050000">
              <OpenPositions>
                <OpenPosition accountId="U1234567" currency="USD" fxRateToBase="0.92"
                    assetCategory="STK" symbol="AAPL" description="APPLE INC" conid="265598"
                    isin="US0378331005" position="10" markPrice="200.00"
                    costBasisPrice="150.00" costBasisMoney="1500.00" levelOfDetail="SUMMARY" />
                <OpenPosition accountId="U1234567" currency="EUR" fxRateToBase="1"
                    assetCategory="STK" symbol="IWDA" description="ISHARES CORE MSCI WORLD"
                    conid="100292928" isin="IE00B4L5Y983" position="5" markPrice="90.00"
                    costBasisPrice="80.00" costBasisMoney="400.00" levelOfDetail="SUMMARY" />
                <OpenPosition accountId="U1234567" currency="USD" fxRateToBase="0.92"
                    assetCategory="STK" symbol="AAPL" description="APPLE INC" conid="265598"
                    isin="US0378331005" position="10" markPrice="200.00"
                    costBasisPrice="150.00" levelOfDetail="LOT" />
              </OpenPositions>
            </FlexStatement>
          </FlexStatements>
        </FlexQueryResponse>"#;

    #[test]
    fn groups_positions_by_account_and_reads_every_attribute() {
        let accounts = parse_open_positions(FIXTURE).expect("parsed");
        assert_eq!(accounts.len(), 1);
        assert_eq!(accounts[0].account_id, "U1234567");
        assert_eq!(accounts[0].base_currency, None);
        let positions = &accounts[0].positions;
        assert_eq!(positions.len(), 3);
        let aapl = &positions[0];
        assert_eq!(aapl.account_id.as_deref(), Some("U1234567"));
        assert_eq!(aapl.isin.as_deref(), Some("US0378331005"));
        assert_eq!(aapl.symbol.as_deref(), Some("AAPL"));
        assert_eq!(aapl.description.as_deref(), Some("APPLE INC"));
        assert_eq!(aapl.currency.as_deref(), Some("USD"));
        assert_eq!(aapl.asset_category.as_deref(), Some("STK"));
        assert_eq!(aapl.level_of_detail.as_deref(), Some("SUMMARY"));
        assert_eq!(aapl.position, Some(d("10")));
        assert_eq!(
            aapl.mark_price.map(|p| p.to_string()),
            Some("200.00".into())
        );
        assert_eq!(aapl.cost_basis_price, Some(d("150.00")));
        assert_eq!(aapl.fx_rate_to_base, Some(d("0.92")));
        let lots = positions
            .iter()
            .filter(|p| p.level_of_detail.as_deref() == Some("LOT"))
            .count();
        assert_eq!(lots, 1);
    }

    #[test]
    fn an_emptied_account_is_still_returned() {
        let xml = r#"<FlexQueryResponse><FlexStatements count="1">
            <FlexStatement accountId="U1234567"><OpenPositions/></FlexStatement>
            </FlexStatements></FlexQueryResponse>"#;
        let accounts = parse_open_positions(xml).expect("parsed");
        assert_eq!(accounts.len(), 1);
        assert!(accounts[0].positions.is_empty());
    }

    #[test]
    fn several_sections_for_one_account_are_refused() {
        let xml = r#"<FlexQueryResponse><FlexStatements count="2">
            <FlexStatement accountId="U1234567"><OpenPositions/></FlexStatement>
            <FlexStatement accountId="U1234567"><OpenPositions/></FlexStatement>
            </FlexStatements></FlexQueryResponse>"#;
        let error = parse_open_positions(xml).expect_err("refused");
        let detail = super::super::sync_error_message(&error).expect("sync error");
        assert!(detail.contains("U1234567"), "{detail}");
        assert!(detail.contains("Last Business Day"), "{detail}");
    }

    #[test]
    fn groups_several_accounts_and_reads_short_and_sparse_rows() {
        let xml = r#"<FlexQueryResponse><FlexStatements count="1"><FlexStatement accountId="U1">
            <AccountInformation accountId="U1" currency="USD" />
            <OpenPositions>
              <OpenPosition accountId="U1" currency="USD" fxRateToBase="0.90" assetCategory="STK"
                  symbol="AAPL" isin="US0378331005" position="10" markPrice="200"
                  costBasisPrice="150" levelOfDetail="SUMMARY" />
              <OpenPosition accountId="U2" currency="EUR" assetCategory="STK"
                  symbol="SHORTY" position="-5" markPrice="12" levelOfDetail="SUMMARY" />
            </OpenPositions></FlexStatement></FlexStatements></FlexQueryResponse>"#;
        let accounts = parse_open_positions(xml).expect("parsed");
        let ids: Vec<&str> = accounts.iter().map(|a| a.account_id.as_str()).collect();
        assert_eq!(ids, ["U1", "U2"]);
        assert_eq!(accounts[0].base_currency.as_deref(), Some("USD"));
        let shorty = &accounts[1].positions[0];
        assert_eq!(shorty.position, Some(d("-5")));
        assert_eq!(shorty.isin, None);
        assert_eq!(shorty.cost_basis_price, None);
        assert_eq!(shorty.fx_rate_to_base, None);
    }

    #[test]
    fn numbers_parse_as_big_decimal_does() {
        assert_eq!(
            parse_big_decimal("200.00").map(|v| v.to_string()),
            Some("200.00".into())
        );
        assert_eq!(parse_big_decimal("+5"), Some(d("5")));
        assert_eq!(parse_big_decimal("-.5"), Some(d("-0.5")));
        assert_eq!(parse_big_decimal("1.5E2"), Some(d("150")));
        assert_eq!(parse_big_decimal("15e-1"), Some(d("1.5")));
        for bad in ["", ".", "abc", "1,5", "1e", "--1", " 1"] {
            assert_eq!(parse_big_decimal(bad), None, "{bad}");
        }
    }

    #[test]
    fn encodes_like_url_encoder() {
        assert_eq!(url_encode("a b&c=d/é*~"), "a+b%26c%3Dd%2F%C3%A9*%7E");
    }
}
