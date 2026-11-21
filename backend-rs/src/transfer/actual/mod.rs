//! Actual Budget import (ActualBudgetImportController, ActualBudgetImportService): a preview
//! parses the uploaded export and keeps it under a member-bound token, a plan dry-runs the
//! user's account and category mappings, and the import applies them in one transaction after
//! every mapping and row has been validated, so a rejected import writes nothing.

mod import;
mod parser;

use std::collections::HashMap;
use std::sync::LazyLock;
use std::time::Duration;

use axum::extract::{DefaultBodyLimit, State};
use axum::handler::Handler;
use axum::response::{IntoResponse, Response};
use chrono::{DateTime, NaiveDate, Utc};
use http::StatusCode;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

use self::parser::{FileParser, Kind, ParsedBudget};
use crate::AppState;
use crate::auth::Auth;
use crate::clock::AppClock;
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Validate};
use crate::ledger::model::{
    Account, AccountType, CardNature, Category, CategoryKind, RecurringCadence,
};
use crate::ledger::sync_values::java_is_blank;
use crate::ledger::{accounts, categorize};
use crate::routes::Routes;
use crate::transfer::import_rate_limit;
use crate::transfer::preview_store::PreviewStore;
use crate::transfer::upload::{MAX_UPLOAD_BYTES, MultipartForm};

pub fn register(routes: &mut Routes) {
    routes.post(
        "/api/actual/import/preview",
        preview.layer(DefaultBodyLimit::max(MAX_UPLOAD_BYTES)),
    );
    routes.post("/api/actual/import/plan", plan);
    routes.post("/api/actual/import", execute);
}

const PREVIEW_EXPIRED: &str = "Preview expired or invalid -- please upload the file again";
const SAMPLE_ROWS: usize = 20;
const PREFIX: &str = "actual_";

pub struct Stores {
    previews: PreviewStore<ParsedBudget>,
}

impl Stores {
    /// One live preview per member, as Java's self-contained cache keeps; the overall
    /// capacity only guards against an unbounded number of members.
    pub fn new(clock: &AppClock) -> Self {
        Self {
            previews: PreviewStore::new(clock.clone(), Duration::from_secs(30 * 60), 10_000, 1),
        }
    }

    /// The scheduled cleanupExpiredPreviews.
    pub fn purge_expired(&self) {
        self.previews.purge_expired();
    }
}

// --- Responses ------------------------------------------------------------------------------

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AccountPreview {
    source_id: String,
    name: String,
    off_budget: bool,
    closed: bool,
    suggested_type: AccountType,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    balance: Decimal,
    transaction_count: usize,
    imported_account_id: Option<i64>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct CategoryPreview {
    source_id: String,
    name: String,
    group_name: Option<String>,
    income: bool,
    transaction_count: usize,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct TransactionPreview {
    source_id: String,
    account_source_id: String,
    date: NaiveDate,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    amount: Decimal,
    payee: Option<String>,
    notes: Option<String>,
    category_source_id: Option<String>,
    kind: Kind,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AccountContractResponse {
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    paid_in_eur: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    net_invested_eur: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    scheduled_payment_eur: Option<Decimal>,
    scheduled_payment_cadence: Option<RecurringCadence>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct InstitutionResponse {
    name: String,
    has_logo: bool,
}

/// AccountResponse.from(account, account.currentBalance): the plain row, without the
/// real-estate, debt, savings, SCPI or viewer overlays the accounts page adds.
#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AccountResponse {
    id: i64,
    name: String,
    #[serde(rename = "type")]
    account_type: AccountType,
    provider: Option<String>,
    currency: String,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    current_balance: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    current_balance_eur: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    cash_balance: Option<Decimal>,
    last_synced_at: Option<DateTime<Utc>>,
    is_manual: bool,
    color: String,
    ticker: Option<String>,
    logo_url: Option<String>,
    logo_key: Option<String>,
    created_at: DateTime<Utc>,
    opened_at: Option<NaiveDate>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    payment_due_amount: Option<Decimal>,
    payment_due_date: Option<NaiveDate>,
    reward_points: Option<i64>,
    parent_account_id: Option<i64>,
    hidden: bool,
    list_collapsed: bool,
    card_nature: Option<CardNature>,
    contract: Option<AccountContractResponse>,
    institution: Option<InstitutionResponse>,
}

impl From<&Account> for AccountResponse {
    fn from(a: &Account) -> Self {
        let contract = (a.provider_paid_in_eur.is_some()
            || a.provider_invested_eur.is_some()
            || a.scheduled_payment_eur.is_some()
            || a.scheduled_payment_cadence.is_some())
        .then_some(AccountContractResponse {
            paid_in_eur: a.provider_paid_in_eur,
            net_invested_eur: a.provider_invested_eur,
            scheduled_payment_eur: a.scheduled_payment_eur,
            scheduled_payment_cadence: a.scheduled_payment_cadence,
        });
        let institution = a
            .institution_name
            .as_deref()
            .filter(|n| !java_is_blank(n))
            .map(|name| InstitutionResponse {
                name: name.to_owned(),
                has_logo: a
                    .institution_domain
                    .as_deref()
                    .is_some_and(|d| !java_is_blank(d)),
            });
        Self {
            id: a.id,
            name: a.name.clone(),
            account_type: a.account_type,
            provider: a.provider.clone(),
            currency: a.currency.clone(),
            current_balance: a.current_balance,
            current_balance_eur: a.current_balance,
            cash_balance: a.cash_balance,
            last_synced_at: a.last_synced_at,
            is_manual: a.is_manual,
            color: a.color.clone(),
            ticker: a.ticker.clone(),
            logo_url: a.logo_url.clone(),
            logo_key: a.logo_key.clone(),
            created_at: a.created_at,
            opened_at: a.opened_at,
            payment_due_amount: a.payment_due_amount,
            payment_due_date: a.payment_due_date,
            reward_points: a.reward_points,
            parent_account_id: a.parent_account_id,
            hidden: a.hidden,
            list_collapsed: false,
            card_nature: a.card_nature,
            contract,
            institution,
        }
    }
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct CategoryResponse {
    id: i64,
    name: String,
    kind: CategoryKind,
    color: String,
    icon: Option<String>,
    is_default: bool,
    archived: bool,
    sort_order: i32,
    parent_id: Option<i64>,
}

impl From<&Category> for CategoryResponse {
    fn from(c: &Category) -> Self {
        Self {
            id: c.id,
            name: c.name.clone(),
            kind: c.kind,
            color: c.color.clone(),
            icon: c.icon.clone(),
            is_default: c.is_default,
            archived: c.archived,
            sort_order: c.sort_order,
            parent_id: c.parent_id,
        }
    }
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct PreviewResponse {
    file_token: String,
    currency: Option<String>,
    accounts: Vec<AccountPreview>,
    categories: Vec<CategoryPreview>,
    existing_accounts: Vec<AccountResponse>,
    actual_account_ids: Vec<i64>,
    existing_categories: Vec<CategoryResponse>,
    sample_transactions: Vec<TransactionPreview>,
    total_transactions: usize,
    transfer_transactions: usize,
}

/// Rows a re-import leaves in place on an append-only account (one the user created).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum WarningReason {
    KeptMissing,
    KeptMoved,
}

#[derive(Debug, Serialize)]
struct Warning {
    reason: WarningReason,
    count: usize,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct PlanResponse {
    transactions_to_add: usize,
    transactions_to_delete: usize,
    transactions_to_move: usize,
    warnings: Vec<Warning>,
    large_deletion: bool,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ResultResponse {
    accounts_created: usize,
    accounts_mapped: usize,
    accounts_skipped: usize,
    categories_created: usize,
    transactions_imported: usize,
    transactions_skipped: usize,
    transactions_deleted: usize,
    transactions_moved: usize,
    warnings: Vec<Warning>,
}

// --- Requests -------------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AccountAction {
    Skip,
    MapExisting,
    CreateNew,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum CategoryAction {
    MapExisting,
    CreateNew,
    Uncategorized,
}

#[derive(Debug, Deserialize)]
struct NewAccountDetails {
    name: Option<String>,
    #[serde(rename = "type")]
    account_type: Option<AccountType>,
    provider: Option<String>,
    currency: Option<String>,
    color: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AccountMapping {
    source_id: Option<String>,
    action: Option<AccountAction>,
    target_account_id: Option<i64>,
    new_account: Option<NewAccountDetails>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CategoryMapping {
    source_id: Option<String>,
    action: Option<CategoryAction>,
    target_category_id: Option<i64>,
    name: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ImportRequest {
    file_token: Option<String>,
    currency: Option<String>,
    account_mappings: Option<Vec<Option<AccountMapping>>>,
    category_mappings: Option<Vec<Option<CategoryMapping>>>,
    #[serde(default, deserialize_with = "crate::http::json::null_as_false")]
    acknowledge_large_deletion: bool,
}

static CURRENCY: LazyLock<regex::Regex> =
    LazyLock::new(|| regex::Regex::new("^[A-Z]{3}$").expect("a valid regex"));

/// @Size(max) on a list, with the validator's message in the request's language.
fn list_size(errors: &mut Errors, field: &str, len: usize, max: usize) {
    if len > max {
        // Errors::size measures a string; a stand-in of the list's length reuses its message.
        errors.size(field, Some(&"x".repeat(len)), 0, max);
    }
}

impl Validate for ImportRequest {
    fn validate(&self, e: &mut Errors) {
        e.not_blank("fileToken", self.file_token.as_deref());
        e.not_null("currency", self.currency.as_ref()).pattern(
            "currency",
            self.currency.as_deref(),
            &CURRENCY,
            "must match \"[A-Z]{3}\"",
        );
        e.not_null("accountMappings", self.account_mappings.as_ref());
        if let Some(mappings) = &self.account_mappings {
            list_size(e, "accountMappings", mappings.len(), 100);
            for (i, mapping) in mappings.iter().enumerate() {
                let field = format!("accountMappings[{i}]");
                match mapping {
                    None => {
                        e.not_null(&field, None::<&()>);
                    }
                    Some(m) => {
                        e.not_blank(&format!("{field}.sourceId"), m.source_id.as_deref());
                        e.not_null(&format!("{field}.action"), m.action.as_ref());
                    }
                }
            }
        }
        e.not_null("categoryMappings", self.category_mappings.as_ref());
        if let Some(mappings) = &self.category_mappings {
            list_size(e, "categoryMappings", mappings.len(), 500);
            for (i, mapping) in mappings.iter().enumerate() {
                let field = format!("categoryMappings[{i}]");
                match mapping {
                    None => {
                        e.not_null(&field, None::<&()>);
                    }
                    Some(m) => {
                        e.not_blank(&format!("{field}.sourceId"), m.source_id.as_deref());
                        e.not_null(&format!("{field}.action"), m.action.as_ref());
                        e.size(&format!("{field}.name"), m.name.as_deref(), 0, 100);
                    }
                }
            }
        }
    }
}

impl ImportRequest {
    fn token(&self) -> &str {
        self.file_token.as_deref().unwrap_or_default()
    }

    fn currency(&self) -> &str {
        self.currency.as_deref().unwrap_or_default()
    }

    fn account_mappings(&self) -> impl Iterator<Item = &AccountMapping> {
        self.account_mappings.iter().flatten().flatten()
    }

    fn category_mappings(&self) -> impl Iterator<Item = &CategoryMapping> {
        self.category_mappings.iter().flatten().flatten()
    }
}

// --- Handlers -------------------------------------------------------------------------------

async fn preview(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    form: MultipartForm,
) -> AppResult<Response> {
    let file = form.file("file")?;
    import_rate_limit(&state, &ip)?;
    let bytes = file.bytes.clone();
    let budget = tokio::task::spawn_blocking(move || FileParser::default().parse(&bytes))
        .await
        .map_err(AppError::internal)?
        .map_err(|rejected| AppError::BadRequest(rejected.0))?;
    let member_id = auth.member_id;

    let mut balances: HashMap<&str, Decimal> = HashMap::new();
    let mut account_counts: HashMap<&str, usize> = HashMap::new();
    let mut category_counts: HashMap<&str, usize> = HashMap::new();
    for tx in &budget.transactions {
        *balances.entry(&tx.account_id).or_default() += tx.amount;
        *account_counts.entry(&tx.account_id).or_default() += 1;
        if let Some(category) = &tx.category_id {
            *category_counts.entry(category).or_default() += 1;
        }
    }
    let member_accounts = accounts::find_all_by_member(&state.db, member_id).await?;
    let mut imported: HashMap<&str, i64> = HashMap::new();
    for account in &member_accounts {
        if let Some(external) = account.external_account_id.as_deref()
            && external.starts_with(PREFIX)
        {
            imported.entry(external).or_insert(account.id);
        }
    }
    let account_previews = budget
        .accounts
        .iter()
        .map(|a| AccountPreview {
            source_id: a.id.clone(),
            name: a.name.clone(),
            off_budget: a.off_budget,
            closed: a.closed,
            suggested_type: if a.off_budget {
                AccountType::Other
            } else {
                AccountType::Checking
            },
            balance: balances
                .get(a.id.as_str())
                .copied()
                .unwrap_or_else(|| Decimal::new(0, 2)),
            transaction_count: account_counts.get(a.id.as_str()).copied().unwrap_or(0),
            imported_account_id: imported.get(format!("{PREFIX}{}", a.id).as_str()).copied(),
        })
        .collect();
    let category_previews = budget
        .categories
        .iter()
        .map(|c| CategoryPreview {
            source_id: c.id.clone(),
            name: c.name.clone(),
            group_name: c.group_name.clone(),
            income: c.income,
            transaction_count: category_counts.get(c.id.as_str()).copied().unwrap_or(0),
        })
        .collect();
    let existing_categories = categorize::categories_of_member(&state.db, member_id, false)
        .await?
        .iter()
        .map(CategoryResponse::from)
        .collect();
    let sample_transactions = budget
        .transactions
        .iter()
        .rev()
        .take(SAMPLE_ROWS)
        .map(|tx| TransactionPreview {
            source_id: tx.id.clone(),
            account_source_id: tx.account_id.clone(),
            date: tx.date,
            amount: tx.amount,
            payee: tx.payee.clone(),
            notes: tx.notes.clone(),
            category_source_id: tx.category_id.clone(),
            kind: tx.kind,
        })
        .collect();
    let response = PreviewResponse {
        file_token: String::new(),
        currency: budget.currency.clone(),
        accounts: account_previews,
        categories: category_previews,
        existing_accounts: member_accounts.iter().map(AccountResponse::from).collect(),
        actual_account_ids: member_accounts
            .iter()
            .filter(|a| import::import_created(a))
            .map(|a| a.id)
            .collect(),
        existing_categories,
        sample_transactions,
        total_transactions: budget.transactions.len(),
        transfer_transactions: budget
            .transactions
            .iter()
            .filter(|tx| tx.kind != Kind::Regular)
            .count(),
    };
    let file_token = state.transfer.actual.previews.put(member_id, budget)?;
    Ok(axum::Json(PreviewResponse {
        file_token,
        ..response
    })
    .into_response())
}

async fn plan(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<ImportRequest>,
) -> AppResult<Response> {
    import_rate_limit(&state, &ip)?;
    let entry = state
        .transfer
        .actual
        .previews
        .get(req.token(), auth.member_id)
        .ok_or_else(|| AppError::BadRequest(PREVIEW_EXPIRED.into()))?;
    let mut conn = state.db.acquire().await?;
    let resolved = import::resolve(&mut conn, &entry.payload, &req, auth.member_id).await?;
    let plan = &resolved.plan;
    Ok(axum::Json(PlanResponse {
        transactions_to_add: plan.to_add.len(),
        transactions_to_delete: plan.to_delete.len(),
        transactions_to_move: plan.to_move.len(),
        warnings: plan.warnings(),
        large_deletion: plan.large_deletion,
    })
    .into_response())
}

async fn execute(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(req): Json<ImportRequest>,
) -> AppResult<Response> {
    import_rate_limit(&state, &ip)?;
    let store = &state.transfer.actual.previews;
    let token = req.token().to_owned();
    let entry = store
        .get(&token, auth.member_id)
        .ok_or_else(|| AppError::BadRequest(PREVIEW_EXPIRED.into()))?;
    match import::execute(&state, &entry, &req, auth.member_id).await {
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

/// Java's String.strip(): Character.isWhitespace from both ends.
fn java_strip(value: &str) -> &str {
    value.trim_matches(|c: char| java_is_blank(c.encode_utf8(&mut [0; 4])))
}
