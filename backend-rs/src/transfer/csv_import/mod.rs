//! CSV import of transactions into one account (TransactionImportController,
//! TransactionImportService, TransactionRowMapper, LedgerRowMapper). Phase one parses the upload,
//! guesses the dialect and the column mapping and keeps the file under a token bound to the
//! account; phase two re-reads it with the confirmed dialect. The account type picks the mode: a
//! manual investment account gets one BUY/SELL row per line, every other account the cash lines
//! of a bank or card statement.

pub mod csv;
mod ledger_lines;

use std::time::Duration;

use axum::extract::{DefaultBodyLimit, State};
use axum::handler::Handler;
use axum::response::{IntoResponse, Response};
use chrono::NaiveDate;
use http::StatusCode;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

use self::csv::{CsvDialect, DatePattern, DecimalStyle};
use crate::AppState;
use crate::auth::Auth;
use crate::clock::AppClock;
use crate::db::AsDbHandle;
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Path, Validate};
use crate::ledger::decimal::{HALF_UP, set_scale};
use crate::ledger::manual_transactions::ManualEntryContext;
use crate::ledger::model::{Account, AccountType, NewTransaction, TransactionType};
use crate::ledger::sync_values::{java_is_blank, java_trim};
use crate::ledger::{
    accounts, categorize, holding_compute, holdings, instrument, manual_transactions, transactions,
};
use crate::routes::Routes;
use crate::transfer::import_rate_limit;
use crate::transfer::preview_store::PreviewStore;
use crate::transfer::upload::{MAX_UPLOAD_BYTES, MultipartForm, Upload};

pub fn register(routes: &mut Routes) {
    routes.post(
        "/api/accounts/{id}/transactions/import/preview",
        preview.layer(DefaultBodyLimit::max(MAX_UPLOAD_BYTES)),
    );
    routes.post("/api/accounts/{id}/transactions/import", execute);
}

const SAMPLE_ROWS: usize = 15;
const PREVIEW_EXPIRED: &str = "Preview expired or invalid -- please re-upload the file";

pub struct CachedCsv {
    account_id: i64,
    content: String,
}

pub struct Stores {
    previews: PreviewStore<CachedCsv>,
}

impl Stores {
    pub fn new(clock: &AppClock) -> Self {
        Self {
            previews: PreviewStore::new(clock.clone(), Duration::from_secs(30 * 60), 32, 4),
        }
    }

    /// The scheduled cleanupExpiredCache.
    pub fn purge_expired(&self) {
        self.previews.purge_expired();
    }
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ColumnMapping {
    pub date: Option<i64>,
    pub side: Option<i64>,
    pub ticker_or_isin: Option<i64>,
    pub name: Option<i64>,
    pub quantity: Option<i64>,
    pub unit_price: Option<i64>,
    pub fees: Option<i64>,
    pub currency: Option<i64>,
    pub amount: Option<i64>,
    pub description: Option<i64>,
    pub debit: Option<i64>,
    pub credit: Option<i64>,
}

/// TransactionImportMode: which mapping fields an import reads, decided by the account's type.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ImportMode {
    Trades,
    Cash,
}

impl ImportMode {
    pub fn of(account_type: AccountType) -> Self {
        if account_type.is_investment() {
            Self::Trades
        } else {
            Self::Cash
        }
    }
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DialectDto {
    pub delimiter: Option<String>,
    pub decimal: Option<String>,
    pub date_format: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PreviewResponse {
    file_token: String,
    detected_columns: Vec<String>,
    sample_rows: Vec<Vec<String>>,
    total_rows: usize,
    has_header_row: bool,
    dialect: DialectDto,
    suggested_mapping: ColumnMapping,
    mode: ImportMode,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportRequest {
    file_token: Option<String>,
    mapping: Option<ColumnMapping>,
    dialect: Option<DialectDto>,
    #[serde(default, deserialize_with = "crate::http::json::null_as_false")]
    has_header_row: bool,
    #[serde(default, deserialize_with = "crate::http::json::null_as_false")]
    fees_included_in_amount: bool,
    side_value_map: Option<serde_json::Map<String, serde_json::Value>>,
    #[serde(default, deserialize_with = "crate::http::json::null_as_false")]
    invert_sign: bool,
}

impl Validate for ImportRequest {
    fn validate(&self, errors: &mut Errors) {
        errors
            .not_blank("fileToken", self.file_token.as_deref())
            .not_null("mapping", self.mapping.as_ref())
            .not_null("dialect", self.dialect.as_ref());
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RowError {
    row_number: usize,
    message: String,
}

#[derive(Debug, Serialize)]
pub struct ImportResult {
    imported: usize,
    skipped: usize,
    errors: Vec<RowError>,
    /// Cash lines left out because the account already held them: not errors, not in `skipped`.
    duplicates: usize,
}

async fn preview(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Path(id): Path<i64>,
    form: MultipartForm,
) -> AppResult<Response> {
    let file = form.file("file")?;
    import_rate_limit(&state, &ip)?;
    let account = importable_account(&state, id, auth.member_id).await?;
    let mode = ImportMode::of(account.account_type);
    let content = read_content(file)?;
    let delimiter = csv::detect_delimiter(&content);
    let rows = csv::parse(&content, delimiter);
    if rows.is_empty() {
        return Err(AppError::BadRequest("The file has no rows".into()));
    }
    let decimal = csv::detect_decimal(&rows);
    let date_format = csv::detect_date_format(&rows);
    let has_header = looks_like_header(&rows[0], decimal);
    let data_rows = if has_header { &rows[1..] } else { &rows[..] };
    let detected_columns = if has_header {
        rows[0].iter().map(|c| java_trim(c).to_owned()).collect()
    } else {
        let max = rows.iter().map(Vec::len).max().unwrap_or(0);
        (1..=max).map(|i| format!("Column {i}")).collect()
    };
    let suggested_mapping = match (has_header, mode) {
        (false, _) => ColumnMapping::default(),
        (true, ImportMode::Trades) => guess_mapping(&rows[0]),
        (true, ImportMode::Cash) => ledger_lines::guess_mapping(&rows[0]),
    };
    let sample_rows = data_rows.iter().take(SAMPLE_ROWS).cloned().collect();
    let total_rows = data_rows.len();
    let file_token = state.transfer.csv.previews.put(
        auth.member_id,
        CachedCsv {
            account_id: id,
            content,
        },
    )?;
    Ok(axum::Json(PreviewResponse {
        file_token,
        detected_columns,
        sample_rows,
        total_rows,
        has_header_row: has_header,
        dialect: DialectDto {
            delimiter: Some(delimiter.to_string()),
            decimal: Some(decimal.name().to_owned()),
            date_format: Some(date_format.to_owned()),
        },
        suggested_mapping,
        mode,
    })
    .into_response())
}

async fn execute(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Path(id): Path<i64>,
    Json(req): Json<ImportRequest>,
) -> AppResult<Response> {
    import_rate_limit(&state, &ip)?;
    let account = importable_account(&state, id, auth.member_id).await?;
    let token = req.file_token.clone().unwrap_or_default();
    let store = &state.transfer.csv.previews;
    let entry = store
        .get(&token, auth.member_id)
        .ok_or_else(|| AppError::BadRequest(PREVIEW_EXPIRED.into()))?;
    if entry.payload.account_id != id {
        return Err(AppError::BadRequest(
            "Preview does not belong to this account".into(),
        ));
    }
    if !store.consume(&token, &entry) {
        return Err(AppError::BadRequest(PREVIEW_EXPIRED.into()));
    }
    match import_rows(&state, &account, &entry.payload.content, &req).await {
        Ok(result) => {
            store.complete(&token, &entry);
            Ok((StatusCode::CREATED, axum::Json(result)).into_response())
        }
        Err(e) => {
            store.restore(&token, &entry);
            Err(e)
        }
    }
}

async fn import_rows(
    state: &AppState,
    account: &Account,
    content: &str,
    req: &ImportRequest,
) -> AppResult<ImportResult> {
    let dialect = to_dialect(req.dialect.as_ref())?;
    let rows = csv::parse(content, dialect.delimiter);
    let data_rows = if req.has_header_row && !rows.is_empty() {
        &rows[1..]
    } else {
        &rows[..]
    };
    let first_row = if req.has_header_row { 2 } else { 1 };
    match ImportMode::of(account.account_type) {
        ImportMode::Trades => {
            import_trades(state, account, data_rows, first_row, &dialect, req).await
        }
        ImportMode::Cash => import_cash(state, account, data_rows, first_row, &dialect, req).await,
    }
}

async fn import_trades(
    state: &AppState,
    account: &Account,
    data_rows: &[Vec<String>],
    first_row: usize,
    dialect: &CsvDialect,
    req: &ImportRequest,
) -> AppResult<ImportResult> {
    let mapping = req.mapping.clone().unwrap_or_default();
    let side_map: Vec<(String, Option<String>)> = req
        .side_value_map
        .iter()
        .flatten()
        .map(|(k, v)| (k.clone(), v.as_str().map(str::to_owned)))
        .collect();
    let mut to_save = Vec::new();
    let mut errors = Vec::new();
    for (row_number, row) in (first_row..).zip(data_rows) {
        match map_row(
            state,
            row,
            &mapping,
            dialect,
            &side_map,
            req.fees_included_in_amount,
            account,
        )
        .await
        {
            Ok(tx) => to_save.push(tx),
            Err(message) => errors.push(RowError {
                row_number,
                message,
            }),
        }
    }
    if !to_save.is_empty() {
        let now = state.clock.now();
        let mut tx = state.db.begin().await?;
        for (row, isin) in &to_save {
            transactions::insert(&mut tx, row, now).await?;
            // A row entered by ISIN keeps it as the instrument's identity (#703).
            if let (Some(isin), Some(ticker)) = (isin, row.ticker.as_deref()) {
                holdings::record_ticker_isin(&mut tx, ticker, isin, now).await?;
            }
        }
        holding_compute::recompute_holdings(&mut tx.as_handle(), account.id, now).await?;
        tx.commit().await?;
    }
    tracing::info!(
        "CSV import for account {}: {} imported, {} skipped",
        account.id,
        to_save.len(),
        errors.len()
    );
    Ok(ImportResult {
        imported: to_save.len(),
        skipped: errors.len(),
        errors,
        duplicates: 0,
    })
}

/// importCashLines: the statement lines the account does not hold yet, categorized like a
/// hand-typed line; a manual cash ledger then has its balance re-anchored on its latest
/// statement. A synced account keeps the balance its provider reports.
async fn import_cash(
    state: &AppState,
    account: &Account,
    data_rows: &[Vec<String>],
    first_row: usize,
    dialect: &CsvDialect,
    req: &ImportRequest,
) -> AppResult<ImportResult> {
    let mapping = req.mapping.clone().unwrap_or_default();
    let mut mapped = Vec::new();
    let mut errors = Vec::new();
    for (row_number, row) in (first_row..).zip(data_rows) {
        match ledger_lines::map_row(
            row,
            &mapping,
            dialect,
            req.invert_sign,
            (account.id, &account.currency),
        ) {
            Ok(tx) => mapped.push(tx),
            Err(message) => errors.push(RowError {
                row_number,
                message,
            }),
        }
    }
    let mapped_count = mapped.len();
    let range = mapped
        .iter()
        .map(|tx| tx.date)
        .min()
        .zip(mapped.iter().map(|tx| tx.date).max());
    let kb = state.ledger.knowledge_base(&state.db).await?;
    let context =
        categorize::load_context(&state.db, kb, account.member_id, state.clock.now()).await?;
    let mut tx = state.db.begin().await?;
    let mut to_save = match range {
        Some((from, to)) => {
            let held: Vec<_> =
                transactions::find_by_accounts_between(&mut tx, &[account.id], from, to)
                    .await?
                    .into_iter()
                    .map(|t| (t.date, t.amount, t.description))
                    .collect();
            ledger_lines::without_held(mapped, &held)
        }
        None => mapped,
    };
    let duplicates = mapped_count - to_save.len();
    if !to_save.is_empty() {
        let now = state.clock.now();
        for row in &mut to_save {
            categorize::auto_categorize(row, &context);
            transactions::insert(&mut tx, row, now).await?;
        }
        let ctx = ManualEntryContext {
            prices: &*state.ledger.prices,
            today: state.clock.today(),
            now,
        };
        manual_transactions::recompute_derived_state(&mut tx.as_handle(), ctx, account).await?;
    }
    tx.commit().await?;
    tracing::info!(
        "CSV import for account {}: {} imported, {} skipped, {} already held",
        account.id,
        to_save.len(),
        errors.len(),
        duplicates
    );
    Ok(ImportResult {
        imported: to_save.len(),
        skipped: errors.len(),
        errors,
        duplicates,
    })
}

/// getImportableAccount. Trades rebuild the positions, and a synced account's positions belong
/// to its provider; cash lines never touch a synced balance, so any other account takes them. A
/// loan's balance is its amortization and a peer loan's its repayment ledger: summing statement
/// lines into either would turn the debt or the receivable into something else.
async fn importable_account(state: &AppState, id: i64, member_id: i64) -> AppResult<Account> {
    let account = accounts::get_or_throw(&state.db, id, member_id).await?;
    if account.account_type.is_investment() && !account.is_manual {
        return Err(AppError::BadRequest(
            "CSV trade import is only available for manual investment accounts".into(),
        ));
    }
    if matches!(
        account.account_type,
        AccountType::Loan | AccountType::PeerLoan
    ) {
        return Err(AppError::BadRequest(
            "CSV transaction import is not available for loans".into(),
        ));
    }
    Ok(account)
}

fn read_content(file: &Upload) -> AppResult<String> {
    if file.bytes.len() > MAX_UPLOAD_BYTES {
        return Err(AppError::BadRequest(
            "The file exceeds the 10 MiB limit".into(),
        ));
    }
    let content = String::from_utf8_lossy(&file.bytes).into_owned();
    if java_is_blank(&content) {
        return Err(AppError::BadRequest("The file is empty".into()));
    }
    Ok(content)
}

fn to_dialect(dto: Option<&DialectDto>) -> AppResult<CsvDialect> {
    let delimiter = dto
        .and_then(|d| d.delimiter.as_deref())
        .and_then(|d| d.chars().next())
        .unwrap_or(',');
    let decimal = match dto.and_then(|d| d.decimal.as_deref()) {
        Some(raw) => match java_trim(raw).to_uppercase().as_str() {
            "DOT" => DecimalStyle::Dot,
            "COMMA" => DecimalStyle::Comma,
            _ => {
                tracing::warn!("Unknown CSV decimal style — falling back to DOT");
                DecimalStyle::Dot
            }
        },
        None => DecimalStyle::Dot,
    };
    let pattern = match dto.and_then(|d| d.date_format.as_deref()) {
        Some(p) if !java_is_blank(p) => java_trim(p).to_owned(),
        _ => "yyyy-MM-dd".to_owned(),
    };
    let date_format = DatePattern::compile(&pattern)
        .ok_or_else(|| AppError::BadRequest(format!("Invalid date format pattern '{pattern}'")))?;
    Ok(CsvDialect {
        delimiter,
        decimal,
        date_format,
    })
}

fn looks_like_header(row: &[String], style: DecimalStyle) -> bool {
    !row.iter()
        .any(|cell| matches!(csv::parse_decimal(Some(cell), style), Ok(Some(_))))
}

fn guess_mapping(header: &[String]) -> ColumnMapping {
    let mut m = ColumnMapping::default();
    let has = |h: &str, words: &[&str]| words.iter().any(|w| h.contains(w));
    for (i, cell) in header.iter().enumerate() {
        let i = Some(i as i64);
        let h = java_trim(&cell.to_lowercase()).to_owned();
        if m.date.is_none() && h.contains("date") {
            m.date = i;
        } else if m.side.is_none() && has(&h, &["sens", "side", "type", "operation"]) {
            m.side = i;
        } else if m.ticker_or_isin.is_none()
            && has(
                &h,
                &[
                    "isin",
                    "ticker",
                    "symbol",
                    "symbole",
                    "valeur",
                    "instrument",
                ],
            )
        {
            m.ticker_or_isin = i;
        } else if m.name.is_none() && has(&h, &["name", "nom", "libell", "designation", "label"]) {
            m.name = i;
        } else if m.quantity.is_none()
            && has(&h, &["qty", "quant", "qte", "nombre", "shares", "parts"])
        {
            m.quantity = i;
        } else if m.fees.is_none() && has(&h, &["fee", "frais", "commission", "courtage"]) {
            m.fees = i;
        } else if m.unit_price.is_none()
            && has(&h, &["price", "prix", "cours", "unit"])
            && !h.contains("total")
        {
            m.unit_price = i;
        } else if m.currency.is_none() && has(&h, &["currency", "devise", "monnaie"]) {
            m.currency = i;
        } else if m.amount.is_none() && has(&h, &["amount", "montant", "total", "net"]) {
            m.amount = i;
        }
    }
    m
}

fn cell(row: &[String], index: Option<i64>) -> Option<String> {
    let index = usize::try_from(index?).ok()?;
    row.get(index).map(|v| java_trim(v).to_owned())
}

fn decimal_cell(
    raw: Option<&str>,
    dialect: &CsvDialect,
    field: &str,
) -> Result<Option<Decimal>, String> {
    csv::parse_decimal(raw, dialect.decimal)
        .map_err(|_| format!("Invalid {field} '{}'", raw.unwrap_or("null")))
}

/// TransactionRowMapper.map: one row to an unsaved manual trade, or the user-safe reason.
async fn map_row(
    state: &AppState,
    row: &[String],
    mapping: &ColumnMapping,
    dialect: &CsvDialect,
    side_map: &[(String, Option<String>)],
    fees_included_in_amount: bool,
    account: &Account,
) -> Result<(NewTransaction, Option<String>), String> {
    let side = resolve_side(
        cell(row, mapping.side).as_deref(),
        cell(row, mapping.amount).as_deref(),
        dialect,
        side_map,
    )?;
    let date_raw = cell(row, mapping.date);
    let date: NaiveDate = csv::parse_date(date_raw.as_deref(), &dialect.date_format)
        .map_err(|_| format!("Invalid date '{}'", date_raw.as_deref().unwrap_or("null")))?
        .ok_or("Missing date")?;
    let quantity = decimal_cell(cell(row, mapping.quantity).as_deref(), dialect, "quantity")?
        .filter(|q| *q > Decimal::ZERO)
        .ok_or("Missing or non-positive quantity")?;
    let fees = match decimal_cell(cell(row, mapping.fees).as_deref(), dialect, "fees")? {
        None => Decimal::ZERO,
        Some(f) if f < Decimal::ZERO => return Err("Fees cannot be negative".into()),
        Some(f) => f,
    };
    let price = resolve_price(
        row,
        mapping,
        dialect,
        side,
        quantity,
        fees,
        fees_included_in_amount,
    )?;
    let currency = cell(row, mapping.currency)
        .filter(|c| !java_is_blank(c))
        .unwrap_or_else(|| account.currency.clone());
    let ticker = manual_transactions::canonical_ticker_for(
        account.account_type,
        cell(row, mapping.ticker_or_isin).as_deref(),
    )
    .map_err(|e| match e {
        AppError::BadRequest(message) => message,
        other => other.to_string(),
    })?;
    let name = cell(row, mapping.name);
    let resolver = &state.transfer.isin;
    let resolved = instrument::resolve(ticker.as_deref(), name.as_deref(), async |isin| {
        resolver.resolve(isin).await
    })
    .await
    .ok_or("Missing ticker/ISIN")?;
    let amount = signed_amount(side, quantity, price, fees).ok_or(OUT_OF_RANGE)?;
    let mut tx = NewTransaction::new(account.id, date, resolved.description, amount);
    tx.tx_type = Some(side);
    tx.ticker = Some(resolved.ticker);
    tx.name = resolved.name;
    tx.quantity = Some(quantity);
    tx.price_per_unit = Some(price);
    tx.fees = Some(fees);
    tx.is_manual = true;
    tx.native_currency = currency;
    let isin = resolved
        .isin
        .filter(|_| account.account_type != AccountType::Crypto);
    Ok((tx, isin))
}

fn resolve_side(
    side_cell: Option<&str>,
    amount_cell: Option<&str>,
    dialect: &CsvDialect,
    side_map: &[(String, Option<String>)],
) -> Result<TransactionType, String> {
    if let Some(raw) = side_cell.filter(|s| !java_is_blank(s)) {
        let raw = java_trim(raw);
        if let Some((_, value)) = side_map
            .iter()
            .find(|(k, _)| k.to_lowercase() == raw.to_lowercase())
        {
            let wanted = java_trim(value.as_deref().unwrap_or_default()).to_uppercase();
            return wanted.parse::<TransactionType>().map_err(|_| {
                format!("No enum constant com.picsou.model.TransactionType.{wanted}")
            });
        }
        let lower = raw.to_lowercase();
        if lower == "s"
            || lower.contains("vent")
            || lower.contains("sell")
            || lower.contains("sale")
        {
            return Ok(TransactionType::Sell);
        }
        if lower == "b" || lower.contains("ach") || lower.contains("buy") {
            return Ok(TransactionType::Buy);
        }
    }
    match decimal_cell(amount_cell, dialect, "amount")? {
        Some(amount) if amount < Decimal::ZERO => Ok(TransactionType::Buy),
        Some(_) => Ok(TransactionType::Sell),
        None => Err("Cannot determine BUY/SELL".into()),
    }
}

fn resolve_price(
    row: &[String],
    mapping: &ColumnMapping,
    dialect: &CsvDialect,
    side: TransactionType,
    quantity: Decimal,
    fees: Decimal,
    fees_included_in_amount: bool,
) -> Result<Decimal, String> {
    if let Some(price) = decimal_cell(
        cell(row, mapping.unit_price).as_deref(),
        dialect,
        "unit price",
    )? {
        if price < Decimal::ZERO {
            return Err("Unit price cannot be negative".into());
        }
        return Ok(price);
    }
    let amount = decimal_cell(cell(row, mapping.amount).as_deref(), dialect, "amount")?
        .ok_or("Missing unit price (or amount)")?;
    let mut gross = amount.abs();
    if fees_included_in_amount {
        gross = if side == TransactionType::Sell {
            gross.checked_add(fees)
        } else {
            gross.checked_sub(fees)
        }
        .ok_or(OUT_OF_RANGE)?;
    }
    let price = gross.checked_div(quantity).ok_or(OUT_OF_RANGE)?;
    Ok(set_scale(price, 8, HALF_UP))
}

/// The row's figures do not fit a decimal: reported on the row, where BigDecimal had no limit.
const OUT_OF_RANGE: &str = "Amount out of range";

/// TransactionAmountCalculator.signedAmount: BUY is an outflow (fees added), SELL an inflow
/// (fees deducted); any other side is treated as an outflow. None when the result overflows.
pub fn signed_amount(
    side: TransactionType,
    quantity: Decimal,
    price: Decimal,
    fees: Decimal,
) -> Option<Decimal> {
    let gross = quantity.checked_mul(price)?;
    if side == TransactionType::Sell {
        gross.checked_sub(fees)
    } else {
        gross.checked_add(fees).map(|total| -total)
    }
}
