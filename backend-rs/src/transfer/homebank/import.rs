//! HomeBankImportService: the preview built from a decoded source, then the mapped import.

use std::collections::{HashMap, HashSet};

use crate::db::AsDbHandle;
use crate::db::DbHandle;
use chrono::{DateTime, Days, NaiveDate, Utc};
use regex::Regex;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

use super::source::{ParsedHomeBankData, SourceAccount, SourceCategory, jlen};
use crate::AppState;
use crate::error::{AppError, AppResult};
use crate::http::json::{Errors, Validate};
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::categorize;
use crate::ledger::manual_ledger;
use crate::ledger::members;
use crate::ledger::model::{
    Account, AccountType, CardNature, Category, CategoryKind, NewTransaction, RecurringCadence,
};
use crate::ledger::sync_values::java_is_blank;
use crate::ledger::transactions;
use crate::ledger::valuation;
use crate::transfer::preview_store::PreviewStore;

const MAX_ACCOUNT_NAME: usize = 100;
const MAX_CATEGORY_NAME: usize = 100;
const MAX_TRANSACTION_TEXT: usize = 255;
const SAMPLE_SIZE: usize = 20;
const DEFAULT_COLOR: &str = "#6366f1";
const TRANSFER_SLUG: &str = "homebank-transfer";

fn bad(message: &str) -> AppError {
    AppError::BadRequest(message.to_owned())
}

// ---- Preview -------------------------------------------------------------------------------

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AccountPreview {
    source_id: String,
    name: String,
    institution: Option<String>,
    source_type: String,
    suggested_type: AccountType,
    currency: String,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    initial_balance: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    balance: Decimal,
    transaction_count: usize,
    closed: bool,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct CategoryPreview {
    source_id: String,
    name: String,
    parent_source_id: Option<String>,
    income: bool,
    kind_inferred: bool,
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
    currency: String,
    payee: Option<String>,
    notes: Option<String>,
    category_source_id: Option<String>,
    transfer: bool,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ContractResponse {
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

/// AccountResponse.from(account, account.getCurrentBalance()): the fields an entity carries,
/// the EUR balance left unconverted, every enrichment (debt, savings, viewer share) absent.
#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ExistingAccount {
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
    contract: Option<ContractResponse>,
    institution: Option<InstitutionResponse>,
}

impl From<&Account> for ExistingAccount {
    fn from(a: &Account) -> Self {
        let contract = (a.provider_paid_in_eur.is_some()
            || a.provider_invested_eur.is_some()
            || a.scheduled_payment_eur.is_some()
            || a.scheduled_payment_cadence.is_some())
        .then_some(ContractResponse {
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

/// CategoryResponse.from.
#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ExistingCategory {
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

impl From<&Category> for ExistingCategory {
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

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PreviewResponse {
    file_token: String,
    accounts: Vec<AccountPreview>,
    categories: Vec<CategoryPreview>,
    existing_accounts: Vec<ExistingAccount>,
    existing_categories: Vec<ExistingCategory>,
    sample_transactions: Vec<TransactionPreview>,
    total_transactions: usize,
    forecast_transactions: usize,
}

fn suggest(source_type: &str) -> AccountType {
    match source_type {
        "bank" | "cash" | "creditcard" => AccountType::Checking,
        "savings" => AccountType::Savings,
        "liability" => AccountType::Loan,
        _ => AccountType::Other,
    }
}

pub async fn preview(
    state: &AppState,
    store: &PreviewStore<ParsedHomeBankData>,
    member_id: i64,
    parsed: ParsedHomeBankData,
) -> AppResult<PreviewResponse> {
    let mut balances: HashMap<String, Decimal> = parsed
        .accounts
        .iter()
        .map(|a| (a.id.clone(), a.initial_balance))
        .collect();
    let mut transaction_counts: HashMap<&str, usize> = HashMap::new();
    let mut category_counts: HashMap<&str, usize> = HashMap::new();
    for t in parsed.transactions.iter().filter(|t| !t.forecast) {
        if let Some(balance) = balances.get_mut(&t.account_id) {
            *balance += t.amount;
        }
        *transaction_counts.entry(&t.account_id).or_default() += 1;
        if let Some(category) = &t.category_id {
            *category_counts.entry(category).or_default() += 1;
        }
    }
    let accounts = parsed
        .accounts
        .iter()
        .map(|a| AccountPreview {
            source_id: a.id.clone(),
            name: a.name.clone(),
            institution: a.institution.clone(),
            source_type: a.source_type.clone(),
            suggested_type: suggest(&a.source_type),
            currency: a.currency.clone(),
            initial_balance: a.initial_balance,
            balance: balances[&a.id],
            transaction_count: transaction_counts.get(a.id.as_str()).copied().unwrap_or(0),
            closed: a.closed,
        })
        .collect();
    let categories = parsed
        .categories
        .iter()
        .map(|c| CategoryPreview {
            source_id: c.id.clone(),
            name: c.name.clone(),
            parent_source_id: c.parent_id.clone(),
            income: c.income,
            kind_inferred: c.kind_inferred,
            transaction_count: category_counts.get(c.id.as_str()).copied().unwrap_or(0),
        })
        .collect();
    let sample_transactions = parsed
        .transactions
        .iter()
        .filter(|t| !t.forecast)
        .take(SAMPLE_SIZE)
        .map(|t| TransactionPreview {
            source_id: t.id.clone(),
            account_source_id: t.account_id.clone(),
            date: t.date,
            amount: t.amount,
            currency: t.currency.clone(),
            payee: t.payee.clone(),
            notes: t.notes.clone(),
            category_source_id: t.category_id.clone(),
            transfer: t.transfer_account_id.is_some(),
        })
        .collect();
    let forecast_transactions = parsed.transactions.iter().filter(|t| t.forecast).count();
    let total_transactions = parsed.transactions.len() - forecast_transactions;
    let file_token = store.put(member_id, parsed)?;
    let existing_accounts = accounts::find_all_by_member(&state.db, member_id)
        .await?
        .iter()
        .map(ExistingAccount::from)
        .collect();
    let existing_categories = categorize::categories_of_member(&state.db, member_id, false)
        .await?
        .iter()
        .map(ExistingCategory::from)
        .collect();
    Ok(PreviewResponse {
        file_token,
        accounts,
        categories,
        existing_accounts,
        existing_categories,
        sample_transactions,
        total_transactions,
        forecast_transactions,
    })
}

// ---- Import request ------------------------------------------------------------------------

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
#[serde(rename_all = "camelCase")]
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
pub struct ImportRequest {
    file_token: Option<String>,
    account_mappings: Option<Vec<Option<AccountMapping>>>,
    category_mappings: Option<Vec<Option<CategoryMapping>>>,
}

fn validate_list<T>(
    errors: &mut Errors,
    field: &str,
    list: Option<&Vec<Option<T>>>,
    max: usize,
    mut each: impl FnMut(&mut Errors, &str, &T),
) {
    let Some(list) = list else {
        errors.not_null(field, None::<&()>);
        return;
    };
    if list.len() > max {
        errors.add(field, format!("size must be between 0 and {max}"));
    }
    for (index, item) in list.iter().enumerate() {
        let path = format!("{field}[{index}]");
        match item {
            None => {
                errors.not_null(&path, None::<&()>);
            }
            Some(item) => each(errors, &path, item),
        }
    }
}

impl Validate for ImportRequest {
    fn validate(&self, errors: &mut Errors) {
        errors.not_blank("fileToken", self.file_token.as_deref());
        validate_list(
            errors,
            "accountMappings",
            self.account_mappings.as_ref(),
            100,
            |e, p, m| {
                e.not_blank(&format!("{p}.sourceId"), m.source_id.as_deref())
                    .not_null(&format!("{p}.action"), m.action.as_ref());
            },
        );
        validate_list(
            errors,
            "categoryMappings",
            self.category_mappings.as_ref(),
            200,
            |e, p, m| {
                e.not_blank(&format!("{p}.sourceId"), m.source_id.as_deref())
                    .not_null(&format!("{p}.action"), m.action.as_ref())
                    .size(
                        &format!("{p}.name"),
                        m.name.as_deref(),
                        0,
                        MAX_CATEGORY_NAME,
                    );
            },
        );
    }
}

#[derive(Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportResult {
    accounts_created: usize,
    accounts_mapped: usize,
    accounts_skipped: usize,
    categories_created: usize,
    transactions_imported: usize,
    transactions_skipped: usize,
}

// ---- Import --------------------------------------------------------------------------------

fn mappings_by_source<'r, M>(
    provided: &'r [Option<M>],
    source_ids: impl Iterator<Item = &'r str>,
    id_of: impl Fn(&'r M) -> Option<&'r str>,
) -> AppResult<HashMap<&'r str, &'r M>> {
    let mut by_source: HashMap<&'r str, &'r M> = HashMap::new();
    for mapping in provided.iter().flatten() {
        let id = id_of(mapping).ok_or_else(|| bad("Duplicate or missing mapping"))?;
        if by_source.insert(id, mapping).is_some() {
            return Err(bad("Duplicate or missing mapping"));
        }
    }
    let expected: HashSet<&str> = source_ids.collect();
    if by_source.len() != expected.len() || !by_source.keys().all(|k| expected.contains(k)) {
        return Err(bad("Every source item must have exactly one mapping"));
    }
    Ok(by_source)
}

fn account_action(mappings: &HashMap<&str, &AccountMapping>, source_id: &str) -> AccountAction {
    mappings
        .get(source_id)
        .and_then(|m| m.action)
        .unwrap_or(AccountAction::Skip)
}

fn category_action(
    mappings: &HashMap<&str, &CategoryMapping>,
    source_id: &str,
) -> Option<CategoryAction> {
    mappings.get(source_id).and_then(|m| m.action)
}

fn color_pattern() -> &'static Regex {
    static PATTERN: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
    PATTERN.get_or_init(|| Regex::new("^#[0-9a-fA-F]{6}$").expect("a valid pattern"))
}

fn blank(value: Option<&str>) -> bool {
    value.is_none_or(java_is_blank)
}

fn homebank_id(source_id: &str) -> String {
    format!("homebank_{source_id}")
}

fn opening_id(source_id: &str) -> String {
    format!("homebank_opening_{source_id}")
}

/// String.equalsIgnoreCase.
fn equals_ignore_case(a: &str, b: &str) -> bool {
    let upper = |c: char| c.to_uppercase().next().unwrap_or(c);
    let lower = |c: char| c.to_lowercase().next().unwrap_or(c);
    jlen(a) == jlen(b)
        && a.chars()
            .zip(b.chars())
            .all(|(x, y)| x == y || upper(x) == upper(y) || lower(upper(x)) == lower(upper(y)))
}

fn validate_target_account(source: &SourceAccount, target: &Account) -> AppResult<()> {
    if source.currency != target.currency {
        return Err(bad("Target account currency does not match"));
    }
    // HomeBank carries a cash ledger; AccountType::is_ledger lists the accounts valued instead.
    if !target.account_type.is_ledger() {
        return Err(bad(
            "Investment and valued accounts cannot receive HomeBank transactions",
        ));
    }
    Ok(())
}

fn validate_new_account_details(
    source: &SourceAccount,
    details: Option<&NewAccountDetails>,
) -> AppResult<()> {
    let valid = details.is_some_and(|d| {
        !blank(d.name.as_deref())
            && d.name
                .as_deref()
                .is_some_and(|n| jlen(n) <= MAX_ACCOUNT_NAME)
            && d.account_type.is_some_and(AccountType::is_ledger)
            && d.currency.as_deref() == Some(source.currency.as_str())
            && d.provider.as_deref().is_none_or(|p| jlen(p) <= 100)
            && d.color
                .as_deref()
                .is_none_or(|c| color_pattern().is_match(c))
    });
    if valid {
        Ok(())
    } else {
        Err(bad("Invalid new account details"))
    }
}

async fn validate_accounts(
    conn: &mut DbHandle<'_>,
    parsed: &ParsedHomeBankData,
    mappings: &HashMap<&str, &AccountMapping>,
    member_id: i64,
) -> AppResult<HashMap<String, Account>> {
    let mut targets = HashMap::new();
    for source in &parsed.accounts {
        let mapping = mappings[source.id.as_str()];
        match mapping.action {
            Some(AccountAction::MapExisting) => {
                let Some(target_id) = mapping
                    .target_account_id
                    .filter(|_| mapping.new_account.is_none())
                else {
                    return Err(bad("Invalid existing account mapping"));
                };
                let target = accounts::find_by_id_and_member(&mut *conn, target_id, member_id)
                    .await?
                    .ok_or_else(|| bad("Target account not found"))?;
                validate_target_account(source, &target)?;
                targets.insert(source.id.clone(), target);
            }
            Some(AccountAction::CreateNew) => {
                validate_new_account_details(source, mapping.new_account.as_ref())?;
                let external_id = homebank_id(&source.id);
                if accounts::exists_soft_deleted_by_external_id(&mut *conn, &external_id, member_id)
                    .await?
                {
                    return Err(bad(
                        "This HomeBank account was previously archived and cannot be restored by import",
                    ));
                }
                if let Some(existing) =
                    accounts::find_by_external_id_and_member(&mut *conn, &external_id, member_id)
                        .await?
                {
                    validate_target_account(source, &existing)?;
                    if !existing.is_manual {
                        return Err(bad("Existing HomeBank account is not a manual account"));
                    }
                    targets.insert(source.id.clone(), existing);
                }
            }
            Some(AccountAction::Skip) | None => {}
        }
    }
    Ok(targets)
}

fn validate_category_kind(source: &SourceCategory, target: &Category) -> AppResult<()> {
    let expected = if source.income {
        CategoryKind::Income
    } else {
        CategoryKind::Expense
    };
    let compatible = if source.kind_inferred {
        matches!(target.kind, CategoryKind::Income | CategoryKind::Expense)
    } else {
        target.kind == expected
    };
    if compatible {
        Ok(())
    } else {
        Err(bad("Target category kind does not match source"))
    }
}

async fn validate_categories(
    conn: &mut DbHandle<'_>,
    parsed: &ParsedHomeBankData,
    mappings: &HashMap<&str, &CategoryMapping>,
    member_id: i64,
) -> AppResult<HashMap<String, Category>> {
    let mut resolved: HashMap<String, Category> = HashMap::new();
    let existing = categorize::categories_of_member(&mut *conn, member_id, true).await?;
    for source in &parsed.categories {
        let mapping = mappings[source.id.as_str()];
        if mapping.action == Some(CategoryAction::MapExisting) {
            let Some(target_id) = mapping
                .target_category_id
                .filter(|_| mapping.name.is_none())
            else {
                return Err(bad("Invalid existing category mapping"));
            };
            let target = categorize::category_of_member(&mut *conn, target_id, member_id)
                .await?
                .filter(|c| !c.archived)
                .ok_or_else(|| bad("Target category not found or archived"))?;
            validate_category_kind(source, &target)?;
            resolved.insert(source.id.clone(), target);
        }
    }
    for source in &parsed.categories {
        let mapping = mappings[source.id.as_str()];
        match mapping.action {
            Some(CategoryAction::CreateNew) => {
                if blank(mapping.name.as_deref())
                    || mapping
                        .name
                        .as_deref()
                        .is_some_and(|n| jlen(n) > MAX_CATEGORY_NAME)
                    || mapping.target_category_id.is_some()
                {
                    return Err(bad("Invalid new category mapping"));
                }
                let slug = homebank_id(&source.id);
                if let Some(found) = existing
                    .iter()
                    .find(|c| c.slug.as_deref() == Some(slug.as_str()))
                {
                    if found.archived {
                        return Err(bad(
                            "HomeBank category slug belongs to an archived category",
                        ));
                    }
                    validate_category_kind(source, found)?;
                    resolved.insert(source.id.clone(), found.clone());
                }
                if let Some(parent_id) = &source.parent_id {
                    match category_action(mappings, parent_id) {
                        None | Some(CategoryAction::Uncategorized) => {
                            return Err(bad("Category parent must also be mapped"));
                        }
                        Some(CategoryAction::MapExisting) => {
                            if resolved
                                .get(parent_id)
                                .is_some_and(|p| p.parent_id.is_some())
                            {
                                return Err(bad(
                                    "HomeBank categories cannot create a third category level",
                                ));
                            }
                        }
                        Some(CategoryAction::CreateNew) => {}
                    }
                }
            }
            Some(CategoryAction::Uncategorized)
                if mapping.target_category_id.is_some() || mapping.name.is_some() =>
            {
                return Err(bad("Uncategorized mapping cannot have a target or name"));
            }
            _ => {}
        }
    }
    let kinds = effective_kinds(parsed, &resolved);
    for source in &parsed.categories {
        if let Some(parent_id) = &source.parent_id
            && category_action(mappings, &source.id) == Some(CategoryAction::CreateNew)
            && kinds.get(&source.id) != kinds.get(parent_id)
        {
            return Err(bad("Category parent kind does not match"));
        }
    }
    for source in &parsed.categories {
        let Some(category) = resolved.get(&source.id) else {
            continue;
        };
        if category_action(mappings, &source.id) != Some(CategoryAction::CreateNew) {
            continue;
        }
        let expected_parent = source
            .parent_id
            .as_ref()
            .and_then(|p| resolved.get(p))
            .map(|p| p.id);
        if category.parent_id != expected_parent {
            return Err(bad(
                "Existing HomeBank category hierarchy does not match source",
            ));
        }
    }
    Ok(resolved)
}

/// The kind each source category ends up with. Existing targets keep their own kind; a kind
/// inferred from amount signs (QIF) is not authoritative, so an inferred sub-category adopts
/// its parent's kind, which Picsou requires to be uniform within a category tree.
fn effective_kinds(
    parsed: &ParsedHomeBankData,
    resolved: &HashMap<String, Category>,
) -> HashMap<String, CategoryKind> {
    let by_id: HashMap<&str, &SourceCategory> = parsed
        .categories
        .iter()
        .map(|c| (c.id.as_str(), c))
        .collect();
    let mut kinds = HashMap::new();
    for source in &parsed.categories {
        effective_kind(source, &by_id, resolved, &mut kinds, &mut HashSet::new());
    }
    kinds
}

fn effective_kind(
    source: &SourceCategory,
    by_id: &HashMap<&str, &SourceCategory>,
    resolved: &HashMap<String, Category>,
    kinds: &mut HashMap<String, CategoryKind>,
    visiting: &mut HashSet<String>,
) -> CategoryKind {
    if let Some(known) = kinds.get(&source.id) {
        return *known;
    }
    let mut kind = if source.income {
        CategoryKind::Income
    } else {
        CategoryKind::Expense
    };
    if let Some(existing) = resolved.get(&source.id) {
        kind = existing.kind;
    } else if source.kind_inferred
        && let Some(parent) = source.parent_id.as_deref().and_then(|p| by_id.get(p))
        && visiting.insert(source.id.clone())
    {
        kind = effective_kind(parent, by_id, resolved, kinds, visiting);
    }
    kinds.insert(source.id.clone(), kind);
    kind
}

async fn find_reusable_transfer_category(
    conn: &mut DbHandle<'_>,
    member_id: i64,
) -> AppResult<Option<Category>> {
    let existing = categorize::categories_of_member(&mut *conn, member_id, true).await?;
    if let Some(named) = existing.iter().find(|c| {
        !c.archived
            && c.kind == CategoryKind::Transfer
            && equals_ignore_case("Virement interne", &c.name)
    }) {
        return Ok(Some(named.clone()));
    }
    let slugged = |c: &&Category| c.slug.as_deref() == Some(TRANSFER_SLUG);
    if let Some(found) = existing
        .iter()
        .filter(slugged)
        .find(|c| !c.archived && c.kind == CategoryKind::Transfer)
    {
        return Ok(Some(found.clone()));
    }
    if existing.iter().any(|c| slugged(&c)) {
        return Err(bad(
            "HomeBank transfer category slug is archived or has an incompatible kind",
        ));
    }
    Ok(None)
}

/// 999999999999.99999999
fn max_ledger_amount() -> Decimal {
    Decimal::from_i128_with_scale(99_999_999_999_999_999_999, 8)
}

fn validate_ledger_amount(amount: Decimal) -> AppResult<()> {
    if amount.abs() > max_ledger_amount() || amount.normalize().scale() > 8 {
        return Err(bad("HomeBank amount exceeds supported precision"));
    }
    Ok(())
}

fn description_of(notes: Option<&str>, payee: Option<&str>) -> String {
    match (
        notes.filter(|n| !n.is_empty()),
        payee.filter(|p| !java_is_blank(p)),
    ) {
        (Some(notes), _) => notes.to_owned(),
        (None, Some(payee)) => payee.to_owned(),
        (None, None) => "HomeBank transaction".to_owned(),
    }
}

fn validate_transactions(
    parsed: &ParsedHomeBankData,
    account_mappings: &HashMap<&str, &AccountMapping>,
    category_mappings: &HashMap<&str, &CategoryMapping>,
    targets: &HashMap<String, Account>,
) -> AppResult<()> {
    let mut balances: HashMap<&str, Decimal> = parsed
        .accounts
        .iter()
        .map(|a| (a.id.as_str(), a.initial_balance))
        .collect();
    for t in &parsed.transactions {
        if t.forecast || account_action(account_mappings, &t.account_id) == AccountAction::Skip {
            continue;
        }
        validate_ledger_amount(t.amount)?;
        let too_long =
            |v: &Option<String>| v.as_deref().is_some_and(|v| jlen(v) > MAX_TRANSACTION_TEXT);
        if too_long(&t.notes)
            || too_long(&t.payee)
            || jlen(&description_of(t.notes.as_deref(), t.payee.as_deref())) > MAX_TRANSACTION_TEXT
        {
            return Err(bad("HomeBank transaction text exceeds supported length"));
        }
        let source = parsed
            .accounts
            .iter()
            .find(|a| a.id == t.account_id)
            .ok_or_else(|| bad("HomeBank transaction account is missing"))?;
        if source.currency != t.currency {
            return Err(bad(
                "HomeBank transaction currency does not match its account",
            ));
        }
        if let Some(target) = targets.get(&source.id) {
            validate_target_account(source, target)?;
        }
        if t.category_id
            .as_deref()
            .is_some_and(|c| !category_mappings.contains_key(c))
        {
            return Err(bad("HomeBank transaction category is missing"));
        }
        if let Some(balance) = balances.get_mut(t.account_id.as_str()) {
            *balance += t.amount;
        }
    }
    balances.into_values().try_for_each(validate_ledger_amount)
}

async fn validate_existing_transaction_ownership(
    exec: impl crate::db::DbExec<'_>,
    parsed: &ParsedHomeBankData,
    mappings: &HashMap<&str, &AccountMapping>,
    targets: &HashMap<String, Account>,
    member_id: i64,
) -> AppResult<()> {
    let mut external_ids: Vec<String> = parsed
        .transactions
        .iter()
        .filter(|t| !t.forecast && account_action(mappings, &t.account_id) != AccountAction::Skip)
        .map(|t| homebank_id(&t.id))
        .collect();
    external_ids.extend(
        parsed
            .accounts
            .iter()
            .filter(|a| {
                account_action(mappings, &a.id) != AccountAction::Skip
                    && !a.initial_balance.is_zero()
            })
            .map(|a| opening_id(&a.id)),
    );
    if external_ids.is_empty() {
        return Ok(());
    }
    let existing: Vec<(String, i64)> = crate::db::query_as(
        "SELECT t.external_id, t.account_id FROM \"transaction\" t JOIN account a ON a.id = t.account_id \
         WHERE a.member_id = $1 AND a.deleted_at IS NULL AND t.external_id = ANY($2)",
    )
    .bind(member_id)
    .bind(&external_ids)
    .fetch_all(exec)
    .await?;
    let mut source_account_by_external_id: HashMap<String, &str> = HashMap::new();
    for t in parsed.transactions.iter().filter(|t| !t.forecast) {
        source_account_by_external_id
            .entry(homebank_id(&t.id))
            .or_insert(&t.account_id);
    }
    for a in &parsed.accounts {
        source_account_by_external_id.insert(opening_id(&a.id), &a.id);
    }
    for (external_id, account_id) in existing {
        let expected = source_account_by_external_id
            .get(&external_id)
            .and_then(|source| targets.get(*source));
        if expected.is_none_or(|target| target.id != account_id) {
            return Err(bad(
                "HomeBank transaction was previously imported into a different account",
            ));
        }
    }
    Ok(())
}

fn validate_unique_account_targets(
    parsed: &ParsedHomeBankData,
    mappings: &HashMap<&str, &AccountMapping>,
    targets: &HashMap<String, Account>,
) -> AppResult<()> {
    let mut seen = HashSet::new();
    for source in &parsed.accounts {
        if account_action(mappings, &source.id) == AccountAction::Skip {
            continue;
        }
        if let Some(target) = targets.get(&source.id)
            && !seen.insert(target.id)
        {
            return Err(bad("Multiple source accounts cannot map to one target"));
        }
    }
    Ok(())
}

/// The columns of a category the import creates; the rest are the entity's defaults.
struct NewCategory<'a> {
    member_id: i64,
    name: &'a str,
    slug: &'a str,
    kind: CategoryKind,
    color: &'a str,
    parent_id: Option<i64>,
}

async fn insert_category(
    exec: impl crate::db::DbExec<'_>,
    category: NewCategory<'_>,
    now: DateTime<Utc>,
) -> AppResult<Category> {
    let NewCategory {
        member_id,
        name,
        slug,
        kind,
        color,
        parent_id,
    } = category;
    Ok(crate::db::query_as(
        "INSERT INTO category (member_id, parent_id, slug, name, kind, color, is_default, archived, \
         sort_order, created_at, updated_at) VALUES ($1, $2, $3, $4, $5::category_kind, $6, false, false, 0, $7, $7) \
         RETURNING id, member_id, parent_id, name, slug, kind::text AS kind, color, icon, is_default, archived, sort_order",
    )
    .bind(member_id)
    .bind(parent_id)
    .bind(slug)
    .bind(name)
    .bind(kind)
    .bind(color)
    .bind(now)
    .fetch_one(exec)
    .await?)
}

pub async fn execute(
    state: &AppState,
    store: &PreviewStore<ParsedHomeBankData>,
    member_id: i64,
    request: &ImportRequest,
) -> AppResult<ImportResult> {
    let token = request.file_token.as_deref().unwrap_or_default();
    let entry = store
        .get(token, member_id)
        .ok_or_else(|| bad("Preview expired or invalid; please upload the file again"))?;
    let parsed = &*entry.payload;
    // The manual ledgers this import rebuilds convert from these currencies: the rates are
    // asked before the transaction opens, where a slow provider would keep SQLite's only writer.
    let mut needs =
        valuation::currency_needs_of_member(&mut state.db.pool_handle(), member_id).await?;
    for account in &parsed.accounts {
        needs.currency(&account.currency);
    }
    state
        .ledger
        .prices
        .prefetch(&needs, state.clock.today())
        .await;
    let mut tx = state.db.begin().await?;
    members::lock(&mut tx, member_id)
        .await?
        .ok_or_else(|| bad("Family member not found"))?;
    let account_mappings = mappings_by_source(
        request.account_mappings.as_deref().unwrap_or_default(),
        parsed.accounts.iter().map(|a| a.id.as_str()),
        |m: &AccountMapping| m.source_id.as_deref(),
    )?;
    let category_mappings = mappings_by_source(
        request.category_mappings.as_deref().unwrap_or_default(),
        parsed.categories.iter().map(|c| c.id.as_str()),
        |m: &CategoryMapping| m.source_id.as_deref(),
    )?;
    let mut targets =
        validate_accounts(&mut tx.as_handle(), parsed, &account_mappings, member_id).await?;
    let mut categories =
        validate_categories(&mut tx.as_handle(), parsed, &category_mappings, member_id).await?;
    let reusable_transfer = find_reusable_transfer_category(&mut tx.as_handle(), member_id).await?;
    validate_transactions(parsed, &account_mappings, &category_mappings, &targets)?;
    validate_existing_transaction_ownership(
        &mut tx,
        parsed,
        &account_mappings,
        &targets,
        member_id,
    )
    .await?;
    validate_unique_account_targets(parsed, &account_mappings, &targets)?;

    if !store.consume(token, &entry) {
        return Err(bad("Preview has already been used"));
    }
    let outcome = async {
        let counts = write_import(
            state,
            &mut tx.as_handle(),
            parsed,
            &account_mappings,
            &category_mappings,
            &mut targets,
            &mut categories,
            reusable_transfer,
            member_id,
        )
        .await?;
        tx.commit().await?;
        Ok::<_, AppError>(counts)
    }
    .await;
    match outcome {
        Ok(counts) => {
            store.complete(token, &entry);
            Ok(counts)
        }
        Err(e) => {
            store.restore(token, &entry);
            Err(e)
        }
    }
}

#[allow(clippy::too_many_arguments)]
async fn write_import(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    parsed: &ParsedHomeBankData,
    account_mappings: &HashMap<&str, &AccountMapping>,
    category_mappings: &HashMap<&str, &CategoryMapping>,
    targets: &mut HashMap<String, Account>,
    categories: &mut HashMap<String, Category>,
    reusable_transfer: Option<Category>,
    member_id: i64,
) -> AppResult<ImportResult> {
    let now = state.clock.now();
    let today = state.clock.today();
    let mut counts = ImportResult::default();

    for source in &parsed.accounts {
        let mapping = account_mappings[source.id.as_str()];
        match mapping.action {
            Some(AccountAction::Skip) | None => counts.accounts_skipped += 1,
            Some(AccountAction::CreateNew) if !targets.contains_key(&source.id) => {
                let details = mapping
                    .new_account
                    .as_ref()
                    .ok_or_else(|| bad("Invalid new account details"))?;
                let mut draft = NewAccount::new(
                    member_id,
                    details.name.clone().unwrap_or_default(),
                    details.account_type.unwrap_or(AccountType::Checking),
                );
                draft.provider = details.provider.clone();
                draft.currency = details.currency.clone().unwrap_or_default();
                draft.is_manual = true;
                draft.color = details
                    .color
                    .clone()
                    .unwrap_or_else(|| DEFAULT_COLOR.to_owned());
                draft.external_account_id = Some(homebank_id(&source.id));
                let account = accounts::insert(&mut *conn, &draft, now).await?;
                targets.insert(source.id.clone(), account);
                counts.accounts_created += 1;
            }
            Some(AccountAction::CreateNew) => {}
            Some(AccountAction::MapExisting) => counts.accounts_mapped += 1,
        }
    }

    let kinds = effective_kinds(parsed, categories);
    let mut pending: Vec<&str> = parsed
        .categories
        .iter()
        .filter(|c| {
            category_action(category_mappings, &c.id) == Some(CategoryAction::CreateNew)
                && !categories.contains_key(&c.id)
        })
        .map(|c| c.id.as_str())
        .collect();
    while !pending.is_empty() {
        let mut created_one = false;
        for source in &parsed.categories {
            if !pending.contains(&source.id.as_str()) {
                continue;
            }
            let parent = match &source.parent_id {
                None => None,
                Some(parent_id) => match categories.get(parent_id) {
                    Some(parent) => Some(parent.id),
                    None => continue,
                },
            };
            let name = category_mappings[source.id.as_str()]
                .name
                .clone()
                .unwrap_or_default();
            let kind = kinds
                .get(&source.id)
                .copied()
                .unwrap_or(CategoryKind::Expense);
            let category = insert_category(
                &mut *conn,
                NewCategory {
                    member_id,
                    name: &name,
                    slug: &homebank_id(&source.id),
                    kind,
                    color: DEFAULT_COLOR,
                    parent_id: parent,
                },
                now,
            )
            .await?;
            categories.insert(source.id.clone(), category);
            pending.retain(|id| *id != source.id);
            counts.categories_created += 1;
            created_one = true;
        }
        if !created_one {
            return Err(bad("HomeBank category hierarchy is invalid"));
        }
    }
    let transfer = match reusable_transfer {
        Some(category) => category,
        None => {
            insert_category(
                &mut *conn,
                NewCategory {
                    member_id,
                    name: "HomeBank transfer",
                    slug: TRANSFER_SLUG,
                    kind: CategoryKind::Transfer,
                    color: "#64748b",
                    parent_id: None,
                },
                now,
            )
            .await?
        }
    };

    let mut with_new_rows: HashSet<i64> = HashSet::new();
    for source in &parsed.accounts {
        if account_action(account_mappings, &source.id) == AccountAction::Skip {
            continue;
        }
        let Some(target) = targets.get(&source.id).filter(|t| t.is_manual) else {
            continue;
        };
        if source.initial_balance.is_zero() {
            continue;
        }
        let external_id = opening_id(&source.id);
        if transactions::exists_by_account_and_external_id(&mut *conn, target.id, &external_id)
            .await?
        {
            continue;
        }
        let first = parsed
            .transactions
            .iter()
            .filter(|t| t.account_id == source.id && !t.forecast)
            .map(|t| t.date)
            .min()
            .unwrap_or(today);
        let date = first.checked_sub_days(Days::new(1)).unwrap_or(first);
        let mut row = NewTransaction::new(
            target.id,
            date,
            "HomeBank opening balance",
            source.initial_balance,
        );
        row.category = Some("Opening balance".to_owned());
        row.category_id = Some(transfer.id);
        row.category_manual = true;
        row.native_currency = source.currency.clone();
        row.external_id = Some(external_id);
        row.is_manual = true;
        transactions::insert(&mut *conn, &row, now).await?;
        with_new_rows.insert(target.id);
    }

    let sources: HashMap<&str, &SourceCategory> = parsed
        .categories
        .iter()
        .map(|c| (c.id.as_str(), c))
        .collect();
    for t in &parsed.transactions {
        if t.forecast || account_action(account_mappings, &t.account_id) == AccountAction::Skip {
            counts.transactions_skipped += 1;
            continue;
        }
        let Some(account) = targets.get(&t.account_id) else {
            counts.transactions_skipped += 1;
            continue;
        };
        let external_id = homebank_id(&t.id);
        if transactions::exists_by_account_and_external_id(&mut *conn, account.id, &external_id)
            .await?
        {
            counts.transactions_skipped += 1;
            continue;
        }
        let source_category = t.category_id.as_deref().and_then(|c| sources.get(c));
        let (category_id, explicit) = if t.transfer_account_id.is_some() {
            (Some(transfer.id), true)
        } else {
            (
                source_category
                    .and_then(|c| categories.get(&c.id))
                    .map(|c| c.id),
                source_category.is_some_and(|c| category_mappings.contains_key(c.id.as_str())),
            )
        };
        let mut row = NewTransaction::new(
            account.id,
            t.date,
            description_of(t.notes.as_deref(), t.payee.as_deref()),
            t.amount,
        );
        row.counterparty = t.payee.clone();
        row.category = source_category.map(|c| c.name.clone());
        row.category_id = category_id;
        row.category_manual = category_id.is_some() && explicit;
        row.external_id = Some(external_id);
        row.native_currency = t.currency.clone();
        row.is_manual = true;
        transactions::insert(&mut *conn, &row, now).await?;
        with_new_rows.insert(account.id);
        counts.transactions_imported += 1;
    }

    // ManualTransactionService.recomputeDerivedState for the cash balance: a manual target that
    // received a new row is re-anchored and its derived history rebuilt; synced targets keep
    // their provider balance.
    let prices = &*state.ledger.prices;
    for source in &parsed.accounts {
        if account_action(account_mappings, &source.id) == AccountAction::Skip {
            continue;
        }
        let Some(account) = targets.get_mut(&source.id) else {
            continue;
        };
        if !account.is_manual || !with_new_rows.contains(&account.id) {
            continue;
        }
        account.current_balance =
            manual_ledger::compute_manual_ledger_balance(&mut *conn, account, prices, today)
                .await?;
        manual_ledger::reconstruct_snapshots_from_db(&mut *conn, account, prices, today, now)
            .await?;
        accounts::update(&mut *conn, account, now).await?;
    }
    Ok(counts)
}
