//! The CSV fallbacks: account balances from a hand-made file, and TR's full transaction export
//! turned into double-entry transactions (also what the sync imports).

use std::collections::HashSet;
use std::str::FromStr;

use crate::db::AsDbHandle;
use crate::db::DbHandle;
use chrono::NaiveDate;
use rust_decimal::Decimal;
use serde::Serialize;

use super::adapter::TrAccountData;
use super::sync::{PROVIDER, upsert_account};
use crate::AppState;
use crate::error::{AppError, AppResult};
use crate::ledger::model::{Account, AccountType, NewTransaction, TransactionType};
use crate::ledger::sync_values::java_trim;
use crate::ledger::{accounts, categorize, holdings, transactions};

/// Slug of the EXPENSE category imported securities purchases are booked under.
const INVESTMENT_PURCHASE_SLUG: &str = "investissement-titres";
const TR_CRYPTO_ISIN_PREFIX: &str = "XF000";

/// `non_eur`: rows the export states in a currency other than EUR, left out.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportResult {
    pub inserted: i32,
    pub skipped: i32,
    pub non_eur: i32,
}

/// BufferedReader.readLine: lines split on \n, \r or \r\n.
fn lines(text: &str) -> impl Iterator<Item = &str> {
    let mut rest = text;
    std::iter::from_fn(move || {
        if rest.is_empty() {
            return None;
        }
        match rest.find(['\n', '\r']) {
            Some(end) => {
                let line = &rest[..end];
                let skip = if rest[end..].starts_with("\r\n") {
                    2
                } else {
                    1
                };
                rest = &rest[end + skip..];
                Some(line)
            }
            None => {
                let line = rest;
                rest = "";
                Some(line)
            }
        }
    })
}

fn big_decimal(text: &str) -> Option<Decimal> {
    if text.is_empty() || text.trim() != text {
        return None;
    }
    Decimal::from_str_exact(text)
        .or_else(|_| Decimal::from_str(text))
        .or_else(|_| Decimal::from_scientific(text))
        .ok()
}

/// "tr_csv_" and the lower-cased name with every run of other characters as one "_".
fn csv_external_id(name: &str) -> String {
    let mut id = String::from("tr_csv_");
    let mut in_run = false;
    for c in name.to_lowercase().chars() {
        if c.is_ascii_lowercase() || c.is_ascii_digit() {
            id.push(c);
            in_run = false;
        } else if !in_run {
            id.push('_');
            in_run = true;
        }
    }
    id
}

/// importCsv: `name,type,balance` lines (header optional), an unknown type read as OTHER. The
/// accounts saved, in file order; a failure is the 422 "Failed to parse CSV: ...".
pub async fn import_accounts(
    state: &AppState,
    csv: &str,
    member_id: i64,
) -> AppResult<Vec<Account>> {
    let mut tx = state.db.begin().await?;
    let mut saved = Vec::new();
    let mut first_line = true;
    for line in lines(csv) {
        let line = java_trim(line);
        if line.is_empty() {
            continue;
        }
        if first_line {
            first_line = false;
            if line.to_lowercase().starts_with("name") {
                continue;
            }
        }
        let parts: Vec<&str> = line.splitn(3, ',').collect();
        if parts.len() < 3 {
            tracing::warn!("TR CSV: skipping malformed line");
            continue;
        }
        let name = java_trim(parts[0]);
        let type_text = java_trim(parts[1]).to_uppercase();
        let account_type = AccountType::from_str(&type_text).unwrap_or_else(|_| {
            tracing::warn!("TR CSV: unknown account type, using OTHER");
            AccountType::Other
        });
        let Some(balance) = big_decimal(java_trim(parts[2])) else {
            tracing::warn!("TR CSV: invalid balance, line skipped");
            continue;
        };
        let data = TrAccountData {
            external_id: csv_external_id(name),
            name: name.to_owned(),
            account_type,
            balance_eur: balance,
            positions: Vec::new(),
            cash_eur: None,
        };
        let now = state.clock.now();
        match upsert_account(state, &mut tx.as_handle(), &data, member_id, None, now).await {
            Ok(Some(account)) => saved.push(account),
            Ok(None) => {}
            Err(AppError::NotFound(message)) => {
                return Err(AppError::status(
                    http::StatusCode::UNPROCESSABLE_ENTITY,
                    format!("Failed to parse CSV: {message}"),
                ));
            }
            Err(e) => return Err(e),
        }
    }
    tx.commit().await?;
    tracing::info!("TR CSV import complete: {} accounts processed", saved.len());
    Ok(saved)
}

/// importTransactionsCsv: the triggering account must be the member's (a spoofed id is a 404).
pub async fn import_transactions_csv(
    state: &AppState,
    account_id: i64,
    csv: &str,
    member_id: i64,
) -> AppResult<ImportResult> {
    let mut tx = state.db.begin().await?;
    accounts::get_or_throw(&mut tx, account_id, member_id).await?;
    let result = import_transactions(state, &mut tx.as_handle(), csv, member_id).await?;
    tx.commit().await?;
    Ok(result)
}

/// RFC 4180 fields, trimmed; a doubled quote inside quotes is a literal quote.
fn parse_csv_line(line: &str) -> Vec<String> {
    let mut result = Vec::new();
    let mut current = String::new();
    let mut in_quotes = false;
    let mut chars = line.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '"' {
            if in_quotes && chars.peek() == Some(&'"') {
                current.push('"');
                chars.next();
            } else {
                in_quotes = !in_quotes;
            }
        } else if c == ',' && !in_quotes {
            result.push(java_trim(&current).to_owned());
            current.clear();
        } else {
            current.push(c);
        }
    }
    result.push(java_trim(&current).to_owned());
    result
}

/// OpenFigiIsinConverter.isTrCryptoIsin: XF000, letters, then digits.
fn is_tr_crypto_isin(isin: &str) -> bool {
    let upper = java_trim(isin).to_uppercase();
    let Some(rest) = upper.strip_prefix(TR_CRYPTO_ISIN_PREFIX) else {
        return false;
    };
    let letters = rest.chars().take_while(char::is_ascii_uppercase).count();
    let digits = &rest[letters..];
    letters > 0 && !digits.is_empty() && digits.chars().all(|c| c.is_ascii_digit())
}

fn is_crypto_trading_row(
    account_type: &str,
    isin: &str,
    stock_name: &str,
    crypto_asset_names: &HashSet<String>,
) -> bool {
    if account_type.eq_ignore_ascii_case("CRYPTO")
        || account_type.eq_ignore_ascii_case("CRYPTO_WALLET")
    {
        return true;
    }
    if !isin.trim().is_empty() {
        // An explicit ISIN is authoritative: a crypto ETF/ETC carries a market ISIN.
        return is_tr_crypto_isin(isin);
    }
    !stock_name.trim().is_empty()
        && crypto_asset_names.contains(&java_trim(stock_name).to_lowercase())
}

async fn ensure_investment_purchase_category<'e>(
    db: impl crate::db::DbExec<'e>,
    member_id: i64,
    now: chrono::DateTime<chrono::Utc>,
) -> sqlx::Result<i64> {
    let id = crate::db::query_scalar(
        "INSERT INTO category (member_id, slug, name, kind, color, icon, is_default, archived, sort_order, \
         created_at, updated_at) VALUES ($1, $2, 'Investissement', 'EXPENSE'::category_kind, '#7c3aed', \
         'trending-up', true, false, 0, $3, $3) RETURNING id",
    )
    .bind(member_id)
    .bind(INVESTMENT_PURCHASE_SLUG)
    .bind(now)
    .fetch_one(db)
    .await?;
    tracing::info!(
        "TR CSV Import: created the 'Investissement' expense category for member {member_id}."
    );
    Ok(id)
}

#[allow(clippy::too_many_arguments)]
fn leg(
    account_id: i64,
    date: NaiveDate,
    amount: Decimal,
    description: &str,
    row_type: &str,
    category: &str,
    category_id: Option<i64>,
    external_id: String,
) -> NewTransaction {
    let mut tx = NewTransaction::new(account_id, date, description, amount);
    tx.merchant_label = Some(description.to_owned());
    tx.kind = Some(row_type.to_owned());
    tx.category = Some(category.to_owned());
    tx.category_id = category_id;
    tx.tx_type = Some(if amount < Decimal::ZERO {
        TransactionType::Withdrawal
    } else {
        TransactionType::Deposit
    });
    tx.external_id = Some(external_id);
    tx.native_currency = "EUR".to_owned();
    tx
}

/// The parsing and insertion shared by the manual upload and the sync, in the caller's
/// transaction. TRADING rows: a cash leg on TR Cash (the investissement-titres expense) and its
/// mirror on the PEA, the securities or the crypto account (the investissement transfer). Other
/// rows: one uncategorized leg on TR Cash. TRANSFER_IN/OUT are covered by the TRADING legs.
pub async fn import_transactions(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    csv: &str,
    member_id: i64,
) -> AppResult<ImportResult> {
    let tr_accounts: Vec<Account> = accounts::find_all_by_member(&mut *conn, member_id)
        .await?
        .into_iter()
        .filter(|a| a.provider.as_deref() == Some(PROVIDER))
        .collect();
    let first_of = |t: AccountType| tr_accounts.iter().find(|a| a.account_type == t);
    let Some(tr_cash) = first_of(AccountType::Checking) else {
        tracing::warn!(
            "TR CSV Import: No CHECKING account (TR Cash) found for member {member_id}. Create TR accounts first by syncing or importing the accounts CSV."
        );
        return Ok(ImportResult::default());
    };
    let tr_pea = first_of(AccountType::Pea).map(|a| a.id);
    let tr_titres = first_of(AccountType::CompteTitres).map(|a| a.id);
    let tr_crypto = first_of(AccountType::Crypto).map(|a| a.id);
    let tr_cash = tr_cash.id;

    let mut crypto_asset_names = HashSet::new();
    if let Some(crypto) = tr_crypto {
        for holding in holdings::find_by_account(&mut *conn, crypto).await? {
            if let Some(name) = holding.name.as_deref().filter(|n| !n.trim().is_empty()) {
                crypto_asset_names.insert(java_trim(name).to_lowercase());
            }
            if !holding.ticker.trim().is_empty() {
                crypto_asset_names.insert(java_trim(&holding.ticker).to_lowercase());
            }
        }
    }

    let now = state.clock.now();
    let by_slug = categorize::categories_by_slug_on(&mut *conn, member_id, now).await?;
    let transfer_category = by_slug.get("investissement").map(|c| c.id);
    if transfer_category.is_none() {
        tracing::warn!(
            "TR CSV Import: 'investissement' (transfer) category not found for member {member_id}; investment legs may skew cashflow."
        );
    }
    // Created on the first TRADING row only, so a cash-only import creates nothing.
    let mut purchase_category = by_slug.get(INVESTMENT_PURCHASE_SLUG).map(|c| c.id);

    let mut result = ImportResult::default();
    let mut first_line = true;
    let mut currency_column: Option<usize> = None;
    let mut isin_column: Option<usize> = None;
    for line in lines(csv) {
        let line = java_trim(line);
        if line.is_empty() {
            continue;
        }
        if first_line {
            first_line = false;
            if line.to_lowercase().contains("datetime") {
                let headers = parse_csv_line(&line.to_lowercase());
                currency_column = headers.iter().position(|h| h == "currency");
                isin_column = headers.iter().position(|h| h == "isin");
                if currency_column.is_none() {
                    tracing::warn!(
                        "TR CSV import: no currency column in the header, amounts read as EUR"
                    );
                }
                continue;
            }
        }
        let parts = parse_csv_line(line);
        if parts.len() < 19 {
            continue;
        }
        let date_text = &parts[1];
        let account_type = &parts[2];
        let category = &parts[3];
        let row_type = &parts[4];
        let isin = isin_column
            .and_then(|c| parts.get(c))
            .map_or("", String::as_str);
        let stock_name = &parts[6];
        let amount_text = &parts[10];
        let description = &parts[17];
        let external_id = &parts[18];

        // A blank id would collide on "_cash"/"_inv" with every other blank one.
        if external_id.trim().is_empty() {
            continue;
        }
        if row_type.eq_ignore_ascii_case("TRANSFER_IN")
            || row_type.eq_ignore_ascii_case("TRANSFER_OUT")
        {
            continue;
        }
        // Every amount is stored as EUR; original_currency only names what a card payment
        // abroad was charged in.
        if let Some(currency) = currency_column.and_then(|c| parts.get(c))
            && !currency.trim().is_empty()
            && !currency.eq_ignore_ascii_case("EUR")
        {
            result.non_eur += 1;
            continue;
        }
        let Some(amount) = big_decimal(&amount_text.replace(',', ".")) else {
            continue;
        };
        let date_part: String = date_text.chars().take(10).collect();
        let Ok(date) = NaiveDate::parse_from_str(&date_part, "%Y-%m-%d") else {
            continue;
        };
        if date_part.len() != 10 {
            continue;
        }
        let mut final_description = if description.trim().is_empty() {
            row_type.clone()
        } else {
            description.clone()
        };
        if row_type.eq_ignore_ascii_case("BUY") || row_type.eq_ignore_ascii_case("SELL") {
            final_description = if stock_name.trim().is_empty() {
                row_type.clone()
            } else {
                format!("{row_type} {stock_name}")
            };
        }
        let is_trading = category.eq_ignore_ascii_case("TRADING");

        let cash_id = format!("{external_id}_cash");
        let mut cash_skipped = false;
        if !transactions::exists_by_account_and_external_id(&mut *conn, tr_cash, &cash_id).await? {
            if is_trading && purchase_category.is_none() {
                purchase_category =
                    Some(ensure_investment_purchase_category(&mut *conn, member_id, now).await?);
            }
            let cash_leg = leg(
                tr_cash,
                date,
                amount,
                &final_description,
                row_type,
                category,
                if is_trading { purchase_category } else { None },
                cash_id,
            );
            transactions::insert(&mut *conn, &cash_leg, now).await?;
            result.inserted += 1;
        } else {
            cash_skipped = true;
        }

        if !is_trading {
            if cash_skipped {
                result.skipped += 1;
            }
            continue;
        }
        let target = if account_type.eq_ignore_ascii_case("PEA") {
            tr_pea
        } else if is_crypto_trading_row(account_type, isin, stock_name, &crypto_asset_names) {
            tr_crypto.or(tr_titres)
        } else {
            tr_titres
        };
        let Some(target) = target else {
            if cash_skipped {
                result.skipped += 1;
            }
            continue;
        };
        let inv_id = format!("{external_id}_inv");
        // A historical crypto leg may already live on TR Titres from before TR Crypto existed.
        let already = transactions::exists_by_account_and_external_id(&mut *conn, target, &inv_id)
            .await?
            || (Some(target) == tr_crypto
                && match tr_titres {
                    Some(titres) => {
                        transactions::exists_by_account_and_external_id(&mut *conn, titres, &inv_id)
                            .await?
                    }
                    None => false,
                });
        if !already {
            // The mirror of the cash leg: a buy enters the investments as a positive DEPOSIT.
            let inv_leg = leg(
                target,
                date,
                -amount,
                &final_description,
                row_type,
                category,
                transfer_category,
                inv_id,
            );
            transactions::insert(&mut *conn, &inv_leg, now).await?;
            result.inserted += 1;
        } else if cash_skipped {
            result.skipped += 1;
        }
    }
    if result.non_eur > 0 {
        tracing::warn!("TR CSV import: {} rows not in EUR left out", result.non_eur);
    }
    tracing::info!(
        "TR CSV import complete: {} inserted, {} skipped (already present)",
        result.inserted,
        result.skipped
    );
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn csv_fields_follow_rfc_4180() {
        assert_eq!(
            parse_csv_line(r#"a, "b,c" ,"d""e""#),
            vec!["a", "b,c", "d\"e"]
        );
    }

    #[test]
    fn native_crypto_isins_only() {
        assert!(is_tr_crypto_isin("XF000BTC0017"));
        assert!(!is_tr_crypto_isin("XF0000017"));
        assert!(!is_tr_crypto_isin("DE000A27Z304"));
    }

    #[test]
    fn external_ids_collapse_runs_of_other_characters() {
        assert_eq!(
            csv_external_id("PEA  Trade-Républic"),
            "tr_csv_pea_trade_r_public"
        );
    }

    #[test]
    fn lines_split_on_every_line_break() {
        assert_eq!(
            lines("a\r\nb\rc\n\nd").collect::<Vec<_>>(),
            vec!["a", "b", "c", "", "d"]
        );
    }
}
