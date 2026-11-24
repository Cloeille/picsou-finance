//! Finary file import (FinaryImportController, FinaryImportService). The preview parses the XLSX
//! export, keeps it under a token and suggests a Picsou account per Finary row; the import maps,
//! creates or skips each row, rebuilds the balance history and replaces the synced transactions.

mod java_double;
mod matcher;
mod parse;
mod persist;
mod xlsx;

use std::collections::HashMap;
use std::time::Duration;

use axum::extract::{DefaultBodyLimit, State};
use axum::handler::Handler;
use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

use self::java_double::JavaDouble;
use self::parse::{ParsedAccount, ParsedData};
use crate::AppState;
use crate::auth::Auth;
use crate::clock::AppClock;
use crate::db::AsDbHandle;
use crate::error::{AppError, AppResult};
use crate::http::json::{Errors, Json, Validate};
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::members;
use crate::ledger::model::{Account, AccountType, CardNature, RecurringCadence};
use crate::ledger::sync_values::java_is_blank;
use crate::routes::Routes;
use crate::transfer::preview_store::PreviewStore;
use crate::transfer::upload::{MAX_UPLOAD_BYTES, MultipartForm};

pub fn register(routes: &mut Routes) {
    routes.post(
        "/api/finary/preview",
        preview.layer(DefaultBodyLimit::max(MAX_UPLOAD_BYTES)),
    );
    routes.post("/api/finary/import", import_data);
}

const PREVIEW_EXPIRED: &str = "Preview expired or invalid -- please re-upload the file";
const NOT_A_SPREADSHEET: &str = "That file isn't a valid Excel spreadsheet (.xlsx).";

pub struct Stores {
    previews: PreviewStore<ParsedData>,
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

/// String.equalsIgnoreCase, char by char through the simple case mappings.
pub(crate) fn java_equals_ignore_case(a: &str, b: &str) -> bool {
    fn simple(c: char, upper: bool) -> char {
        let mut mapped: Vec<char> = if upper {
            c.to_uppercase().collect()
        } else {
            c.to_lowercase().collect()
        };
        if mapped.len() == 1 {
            mapped.remove(0)
        } else {
            c
        }
    }
    a.chars().count() == b.chars().count()
        && a.chars().zip(b.chars()).all(|(x, y)| {
            if x == y {
                return true;
            }
            let (ux, uy) = (simple(x, true), simple(y, true));
            ux == uy || simple(ux, false) == simple(uy, false)
        })
}

/// slugify: lower case, every run of other characters one underscore, none at either end.
fn slugify(input: &str) -> String {
    let mut out = String::new();
    let mut gap = false;
    for c in input.to_lowercase().chars() {
        if c.is_ascii_lowercase() || c.is_ascii_digit() {
            if gap {
                out.push('_');
                gap = false;
            }
            out.push(c);
        } else {
            gap = true;
        }
    }
    if gap {
        out.push('_');
    }
    let out = out.strip_prefix('_').unwrap_or(&out);
    // A leading run became a leading underscore only once a later character followed it.
    out.strip_suffix('_').unwrap_or(out).to_owned()
}

fn parsed_key(category: &str, name: &str) -> String {
    slugify(&format!("{category}_{name}"))
}

/// FinaryPersistenceHelper.suggestTypeFromDisplayCategory.
fn suggest_type(category: &str) -> AccountType {
    match category {
        "Checkings" => AccountType::Checking,
        "Savings" => AccountType::Savings,
        "Investments" => AccountType::CompteTitres,
        "Cryptos" => AccountType::Crypto,
        _ => AccountType::Other,
    }
}

/// FinaryPersistenceHelper.defaultColorForType.
fn default_color(kind: AccountType) -> &'static str {
    match kind {
        AccountType::Pea => "#10b981",
        AccountType::CompteTitres => "#3b82f6",
        AccountType::Crypto => "#f59e0b",
        AccountType::Savings => "#8b5cf6",
        _ => "#6366f1",
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum MappingAction {
    Skip,
    MapExisting,
    CreateNew,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewAccountDetails {
    name: Option<String>,
    #[serde(rename = "type")]
    account_type: Option<AccountType>,
    provider: Option<String>,
    currency: Option<String>,
    color: Option<String>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountMapping {
    finary_id: Option<String>,
    finary_name: Option<String>,
    finary_category: Option<String>,
    action: Option<MappingAction>,
    target_account_id: Option<i64>,
    new_account: Option<NewAccountDetails>,
}

impl AccountMapping {
    fn key(&self) -> String {
        match &self.finary_id {
            Some(id) => id.clone(),
            // Java concatenates a missing part as "null".
            None => parsed_key(
                self.finary_category.as_deref().unwrap_or("null"),
                self.finary_name.as_deref().unwrap_or("null"),
            ),
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportRequest {
    mappings: Option<Vec<AccountMapping>>,
    file_token: Option<String>,
}

impl Validate for ImportRequest {
    fn validate(&self, _errors: &mut Errors) {}
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AccountPreview {
    finary_id: String,
    finary_name: String,
    finary_institution: String,
    finary_category: &'static str,
    suggested_type: AccountType,
    current_balance: JavaDouble,
    native_currency: String,
    transaction_count: usize,
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

/// AccountResponse.from(account, account.getCurrentBalance()): no viewer, display or
/// per-type overlays.
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
    contract: Option<ContractResponse>,
    institution: Option<InstitutionResponse>,
}

impl AccountResponse {
    fn from(a: Account) -> Self {
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
            .clone()
            .filter(|n| !java_is_blank(n))
            .map(|name| InstitutionResponse {
                name,
                has_logo: a
                    .institution_domain
                    .as_deref()
                    .is_some_and(|d| !java_is_blank(d)),
            });
        Self {
            id: a.id,
            name: a.name,
            account_type: a.account_type,
            provider: a.provider,
            currency: a.currency,
            current_balance: a.current_balance,
            current_balance_eur: a.current_balance,
            cash_balance: a.cash_balance,
            last_synced_at: a.last_synced_at,
            is_manual: a.is_manual,
            color: a.color,
            ticker: a.ticker,
            logo_url: a.logo_url,
            logo_key: a.logo_key,
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

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct PreviewResponse {
    accounts: Vec<AccountPreview>,
    existing_picsou_accounts: Vec<AccountResponse>,
    total_transaction_count: usize,
    file_token: String,
    auto_mapped: bool,
    suggested_mappings: Vec<AccountMapping>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ImportedAccountSummary {
    id: i64,
    name: String,
    #[serde(rename = "type")]
    account_type: AccountType,
    current_balance: JavaDouble,
    color: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ImportResultResponse {
    accounts_created: usize,
    accounts_mapped: usize,
    accounts_skipped: usize,
    snapshots_created: usize,
    transactions_imported: usize,
    imported_accounts: Vec<ImportedAccountSummary>,
}

fn transaction_count(data: &ParsedData, account: &ParsedAccount) -> usize {
    data.transactions
        .iter()
        .filter(|tx| java_equals_ignore_case(&tx.account_name, &account.name))
        .count()
}

async fn preview(
    State(state): State<AppState>,
    auth: Auth,
    form: MultipartForm,
) -> AppResult<axum::Json<PreviewResponse>> {
    let file = form.file("file")?;
    if file.is_empty() {
        // POI's EmptyFileException, an IllegalArgumentException.
        return Err(AppError::BadRequest(
            "The supplied file was empty (zero bytes long)".into(),
        ));
    }
    let bytes = file.bytes.clone();
    let workbook = tokio::task::spawn_blocking(move || xlsx::read(&bytes))
        .await
        .map_err(|e| AppError::Internal(format!("workbook parsing: {e}")))?
        .map_err(|_| AppError::BadRequest(NOT_A_SPREADSHEET.into()))?;
    let parsed = parse::parse(&workbook);

    let accounts: Vec<AccountPreview> = parsed
        .accounts
        .iter()
        .map(|acc| AccountPreview {
            finary_id: parsed_key(acc.category, &acc.name),
            finary_name: acc.name.clone(),
            finary_institution: acc.institution.clone(),
            finary_category: acc.category,
            suggested_type: suggest_type(acc.category),
            current_balance: acc.balance.clone(),
            native_currency: acc.currency.clone(),
            transaction_count: transaction_count(&parsed, acc),
        })
        .collect();
    let existing = accounts::find_all_by_member(&state.db, auth.member_id).await?;
    // The matcher adds the sheet itself: it gets the slug the import persists, not the row key.
    let rows: Vec<matcher::Row<'_>> = parsed
        .accounts
        .iter()
        .map(|acc| matcher::Row {
            finary_id: slugify(&acc.name),
            name: &acc.name,
            category: acc.category,
        })
        .collect();
    let matches = matcher::suggest_all(&rows, &existing);
    let auto_mapped = !rows.is_empty() && matches.iter().all(Option::is_some);
    let suggested_mappings = parsed
        .accounts
        .iter()
        .zip(&matches)
        .map(|(acc, hit)| AccountMapping {
            finary_id: Some(parsed_key(acc.category, &acc.name)),
            finary_name: Some(acc.name.clone()),
            finary_category: Some(acc.category.to_owned()),
            action: Some(if hit.is_some() {
                MappingAction::MapExisting
            } else {
                MappingAction::CreateNew
            }),
            target_account_id: *hit,
            new_account: None,
        })
        .collect();
    let total_transaction_count = parsed.transactions.len();
    let file_token = state.transfer.finary.previews.put(auth.member_id, parsed)?;
    Ok(axum::Json(PreviewResponse {
        accounts,
        existing_picsou_accounts: existing.into_iter().map(AccountResponse::from).collect(),
        total_transaction_count,
        file_token,
        auto_mapped,
        suggested_mappings,
    }))
}

async fn import_data(
    State(state): State<AppState>,
    auth: Auth,
    Json(req): Json<ImportRequest>,
) -> AppResult<axum::Json<ImportResultResponse>> {
    // ConcurrentHashMap.get(null) throws: a 500, as in Java.
    let token = req
        .file_token
        .as_deref()
        .ok_or_else(|| AppError::internal("Finary import without a file token"))?;
    let store = &state.transfer.finary.previews;
    let entry = store
        .get(token, auth.member_id)
        .ok_or_else(|| AppError::BadRequest(PREVIEW_EXPIRED.into()))?;
    if !store.consume(token, &entry) {
        return Err(AppError::BadRequest(PREVIEW_EXPIRED.into()));
    }
    match execute(&state, auth.member_id, &entry.payload, &req).await {
        Ok(result) => {
            store.complete(token, &entry);
            Ok(axum::Json(result))
        }
        Err(e) => {
            store.restore(token, &entry);
            Err(e)
        }
    }
}

async fn execute(
    state: &AppState,
    member_id: i64,
    parsed: &ParsedData,
    req: &ImportRequest,
) -> AppResult<ImportResultResponse> {
    members::find_by_id(&state.db, member_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Family member not found".into()))?;
    let mappings = req
        .mappings
        .as_deref()
        .ok_or_else(|| AppError::internal("Finary import without mappings"))?;

    let mut by_key: HashMap<String, &ParsedAccount> = HashMap::new();
    for acc in &parsed.accounts {
        by_key
            .entry(parsed_key(acc.category, &acc.name))
            .or_insert(acc);
    }
    // Two rows mapped onto one account would each replace its balance and history. CREATE_NEW
    // always creates here, so only explicit targets count.
    if let Some(shared) = matcher::shared_target(
        mappings
            .iter()
            .filter(|m| {
                m.action == Some(MappingAction::MapExisting) && by_key.contains_key(&m.key())
            })
            .map(|m| m.target_account_id),
    ) {
        return Err(AppError::BadRequest(format!(
            "Two Finary accounts are mapped onto the same Picsou account {shared} -- pick a different account for one of them"
        )));
    }

    let today = state.clock.today();
    let now = state.clock.now();
    let prices = &*state.ledger.prices;
    let mut result = ImportResultResponse {
        accounts_created: 0,
        accounts_mapped: 0,
        accounts_skipped: 0,
        snapshots_created: 0,
        transactions_imported: 0,
        imported_accounts: Vec::new(),
    };
    let mut tx = state.db.begin().await?;
    for mapping in mappings {
        let Some(finary) = by_key.get(&mapping.key()).copied() else {
            continue;
        };
        let account = match mapping.action {
            Some(MappingAction::Skip) => {
                result.accounts_skipped += 1;
                continue;
            }
            Some(MappingAction::MapExisting) => {
                let target = mapping.target_account_id;
                let found = match target {
                    Some(id) => accounts::find_by_id_and_member(&mut tx, id, member_id).await?,
                    None => None,
                };
                let mut account = found.ok_or_else(|| {
                    AppError::BadRequest(format!(
                        "Account {} not found",
                        target.map_or_else(|| "null".to_owned(), |id| id.to_string())
                    ))
                })?;
                // The rebuilt history is anchored at the Finary balance; the account shows it too.
                account.current_balance = finary.balance.value;
                account.currency = finary.currency.clone();
                let account = accounts::update(&mut tx, &account, now).await?;
                result.accounts_mapped += 1;
                account
            }
            Some(MappingAction::CreateNew) => {
                let account =
                    create_account(&mut tx.as_handle(), member_id, mapping, finary, now).await?;
                result.accounts_created += 1;
                account
            }
            None => continue,
        };
        result.snapshots_created += persist::reconstruct_snapshots(
            &mut tx.as_handle(),
            prices,
            account.id,
            finary,
            &parsed.transactions,
            today,
            now,
        )
        .await?;
        result.transactions_imported += persist::import_transactions(
            &mut tx.as_handle(),
            account.id,
            finary,
            &parsed.transactions,
            now,
        )
        .await?;
        result.imported_accounts.push(ImportedAccountSummary {
            id: account.id,
            name: account.name,
            account_type: account.account_type,
            // The entity still holds the parsed BigDecimal, not the value read back.
            current_balance: finary.balance.clone(),
            color: account.color,
        });
    }
    tx.commit().await?;
    Ok(result)
}

async fn create_account(
    conn: &mut crate::db::DbHandle<'_>,
    member_id: i64,
    mapping: &AccountMapping,
    finary: &ParsedAccount,
    now: DateTime<Utc>,
) -> AppResult<Account> {
    // Missing details fail in Java as an NPE or a NOT NULL violation: a 500.
    let details = mapping
        .new_account
        .as_ref()
        .ok_or_else(|| AppError::internal("CREATE_NEW without account details"))?;
    let (Some(name), Some(kind), Some(currency)) =
        (&details.name, details.account_type, &details.currency)
    else {
        return Err(AppError::internal(
            "CREATE_NEW with incomplete account details",
        ));
    };
    let mut draft = NewAccount::new(member_id, name.clone(), kind);
    draft.provider = details.provider.clone();
    draft.currency = currency.clone();
    draft.current_balance = finary.balance.value;
    draft.is_manual = true;
    draft.color = details
        .color
        .clone()
        .unwrap_or_else(|| default_color(kind).to_owned());
    draft.external_account_id = Some(format!(
        "finary_{}_{}",
        finary.category,
        slugify(&finary.name)
    ));
    Ok(accounts::insert(&mut *conn, &draft, now).await?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn slugifies_as_java() {
        assert_eq!(
            slugify("Checkings_Compte courant"),
            "checkings_compte_courant"
        );
        assert_eq!(slugify("  Livret A!  "), "livret_a");
        assert_eq!(slugify("_"), "");
        assert_eq!(slugify("Épargne"), "pargne");
        assert_eq!(slugify("Real Estate_Paris flat"), "real_estate_paris_flat");
    }

    #[test]
    fn compares_ignoring_case_as_java() {
        assert!(java_equals_ignore_case("COMPTE courant", "compte COURANT"));
        assert!(java_equals_ignore_case("Élan", "élan"));
        assert!(!java_equals_ignore_case("Livret", "Livret A"));
    }
}
