//! FinaryApiSyncService: credentials, the two-phase preview / execute sync and the auto-sync.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use crate::db::AsDbHandle;
use crate::db::DbHandle;
use chrono::{DateTime, Duration, Utc};
use rust_decimal::Decimal;

use super::api::{
    AccountMapping, AccountPreview, AccountResponse, AutoSyncStatus, CheckTotpResponse,
    ConnectionStatusResponse, FinaryAutoSyncResponse, ImportResultResponse, ImportedAccountSummary,
    JavaDouble, MappingAction, PreviewResponse,
};
use super::client::{Failure, FinaryClient, OrgContext};
use super::upstream::{FinaryAccount, Transaction};
use super::{holdings, matcher, persistence, session};
use crate::AppState;
use crate::error::{AppError, AppResult};
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::model::{Account, NewTransaction};
use crate::ledger::sync_values::java_is_blank;
use crate::ledger::valuation;
use crate::ledger::{manual_ledger, members};

const ACCOUNT_CATEGORIES: [&str; 10] = [
    "checkings",
    "savings",
    "investments",
    "real_estates",
    "cryptos",
    "fonds_euro",
    "commodities",
    "credits",
    "other_assets",
    "startups",
];
/// Loans come from the dedicated /loans endpoint, under this synthetic category.
const LOANS_CATEGORY: &str = "loans";
const TRANSACTION_CATEGORIES: [&str; 4] = ["checkings", "savings", "investments", "credits"];
const PAGE_SIZE: usize = 200;
/// A display balance matching to the cent at this size pins the display rate to 1:1.
const MIN_EUR_WITNESS: Decimal = Decimal::from_parts(1000, 0, 0, false, 0);
const CENT: Decimal = Decimal::from_parts(1, 0, 0, false, 2);

fn sync_error(message: impl Into<String>) -> Failure {
    Failure::Sync(message.into())
}

/// One account the preview fetched, with the category it came from.
#[derive(Debug, Clone)]
pub struct Categorized {
    pub category: &'static str,
    pub account: FinaryAccount,
}

/// SyncSessionData: what the preview fetched, until execute or the ten-minute expiry.
#[derive(Debug)]
struct SyncSession {
    member_id: i64,
    accounts: Vec<Categorized>,
    transactions: HashMap<&'static str, Vec<Transaction>>,
    created_at: DateTime<Utc>,
}

/// The preview cache keyed by sync token.
#[derive(Debug, Default)]
pub struct PreviewCache(Mutex<HashMap<String, Arc<SyncSession>>>);

impl PreviewCache {
    fn entries(
        &self,
        now: DateTime<Utc>,
    ) -> std::sync::MutexGuard<'_, HashMap<String, Arc<SyncSession>>> {
        let mut entries = self.0.lock().unwrap_or_else(|e| e.into_inner());
        let cutoff = now - Duration::minutes(10);
        entries.retain(|_, s| s.created_at >= cutoff);
        entries
    }

    fn put(&self, token: String, session: SyncSession) {
        let now = session.created_at;
        self.entries(now).insert(token, Arc::new(session));
    }

    /// The member's own entry only: a token is no use to another member.
    fn get(&self, token: &str, member_id: i64, now: DateTime<Utc>) -> Option<Arc<SyncSession>> {
        self.entries(now)
            .get(token)
            .filter(|s| s.member_id == member_id)
            .cloned()
    }

    fn remove(&self, token: &str) {
        self.0
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(token);
    }

    pub(super) fn purge_expired(&self, now: DateTime<Utc>) {
        drop(self.entries(now));
    }
}

fn mask_email(email: &str) -> String {
    match email.find('@') {
        Some(at) if email[..at].chars().count() > 1 => {
            let first = email.chars().next().unwrap_or_default();
            format!("{first}***{}", &email[at..])
        }
        _ => "***".to_owned(),
    }
}

pub async fn login(
    state: &AppState,
    member_id: i64,
    email: Option<&str>,
    password: Option<&str>,
) -> AppResult<()> {
    let (Some(email), Some(password)) = (email, password) else {
        return Err(AppError::internal(
            "finary_session credentials cannot be null",
        ));
    };
    let email = state.crypto.encrypt(email)?;
    let password = state.crypto.encrypt(password)?;
    members::find_by_id(&state.db, member_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Family member not found".to_owned()))?;
    let now = state.clock.now();
    match session::find_by_member(&state.db, member_id).await? {
        Some(existing) => {
            session::replace_credentials(&state.db, existing.id, &email, &password, now).await?
        }
        None => session::insert(&state.db, member_id, &email, &password, now).await?,
    }
    tracing::info!("Finary credentials stored for member {member_id}");
    Ok(())
}

pub async fn status(state: &AppState, member_id: i64) -> AppResult<ConnectionStatusResponse> {
    let Some(s) = session::find_by_member(&state.db, member_id).await? else {
        return Ok(ConnectionStatusResponse {
            connected: false,
            session_id: None,
            status: None,
            last_synced_at: None,
            masked_email: None,
        });
    };
    let email = state.crypto.decrypt(&s.email)?;
    Ok(ConnectionStatusResponse {
        connected: true,
        session_id: Some(s.id),
        status: Some(s.status),
        last_synced_at: s.last_synced_at,
        masked_email: Some(mask_email(&email)),
    })
}

pub async fn check_totp(state: &AppState, member_id: i64) -> AppResult<CheckTotpResponse> {
    let s = session::find_by_member(&state.db, member_id)
        .await?
        .ok_or_else(|| AppError::NotFound("No Finary session for member".to_owned()))?;
    let email = state.crypto.decrypt(&s.email)?;
    let password = state.crypto.decrypt(&s.password)?;
    let sign_in = FinaryClient::new(state)?
        .check_totp_required(&email, &password)
        .await?;
    Ok(CheckTotpResponse {
        totp_required: sign_in.is_some(),
    })
}

pub async fn delete_session(state: &AppState, member_id: i64) -> AppResult<()> {
    if let Some(s) = session::find_by_member(&state.db, member_id).await? {
        session::delete(&state.db, s.id).await?;
        tracing::info!("Finary session deleted for member {member_id}");
    }
    Ok(())
}

/// What the preview fetched from Finary, before anything is written.
struct Fetched {
    accounts: Vec<Categorized>,
    transactions: HashMap<&'static str, Vec<Transaction>>,
}

async fn fetch(
    state: &AppState,
    email: &str,
    password: &str,
    totp: Option<&str>,
) -> Result<Fetched, Failure> {
    let client = FinaryClient::new(state)?;
    tracing::info!("Authenticating to Finary API (preview)");
    let jwt = client.authenticate(email, password, totp).await?;
    let ctx = client.organization_context(&jwt).await?;

    let mut accounts = Vec::new();
    for category in ACCOUNT_CATEGORIES {
        let fetched = client
            .category_accounts(&jwt, &ctx, category)
            .await
            .map_err(|_| {
                tracing::error!("Failed to fetch accounts from category {category}");
                sync_error(format!("Failed to fetch accounts from {category}"))
            })?;
        tracing::info!(
            "Fetched {} accounts from category: {category}",
            fetched.len()
        );
        accounts.extend(
            fetched
                .into_iter()
                .map(|account| Categorized { category, account }),
        );
    }
    let loans = client.loans(&jwt).await.map_err(|_| {
        tracing::error!("Failed to fetch loans");
        sync_error("Failed to fetch loans")
    })?;
    tracing::info!("Fetched {} entries from loans endpoint", loans.len());
    accounts.extend(loans.into_iter().map(|loan| Categorized {
        category: LOANS_CATEGORY,
        account: loan.into_account(),
    }));

    let mut transactions = HashMap::new();
    for category in TRANSACTION_CATEGORIES {
        let txs = all_transaction_pages(&client, &jwt, &ctx, category)
            .await
            .map_err(|_| {
                tracing::error!("Failed to fetch transactions from category {category}");
                sync_error(format!("Failed to fetch transactions from {category}"))
            })?;
        tracing::info!(
            "Fetched {} transactions from category: {category}",
            txs.len()
        );
        transactions.insert(category, txs);
    }
    Ok(Fetched {
        accounts,
        transactions,
    })
}

async fn all_transaction_pages(
    client: &FinaryClient,
    jwt: &str,
    ctx: &OrgContext,
    category: &str,
) -> Result<Vec<Transaction>, Failure> {
    let mut all = Vec::new();
    let mut page = 1;
    loop {
        let batch = client
            .category_transactions(jwt, ctx, category, page, PAGE_SIZE)
            .await?;
        let last = batch.len() < PAGE_SIZE;
        if batch.is_empty() {
            break;
        }
        all.extend(batch);
        if last {
            break;
        }
        page += 1;
    }
    Ok(all)
}

/// displayCurrencyIsEur: every EUR account with a non-zero balance shows its native balance as
/// display balance, to the cent, and one of them holds at least 1,000.
pub fn display_currency_is_eur(accounts: &[Categorized]) -> bool {
    let mut proven = false;
    for acc in accounts.iter().map(|c| &c.account) {
        let (Some(display), Some(balance)) = (acc.display_balance, acc.balance) else {
            continue;
        };
        if balance.is_zero()
            || !acc
                .currency_code()
                .is_some_and(|c| c.eq_ignore_ascii_case("EUR"))
        {
            continue;
        }
        if (display - balance).abs() > CENT {
            return false;
        }
        proven |= balance.abs() >= MIN_EUR_WITNESS;
    }
    proven
}

fn non_blank(value: Option<&str>) -> Option<&str> {
    value.filter(|v| !java_is_blank(v))
}

/// balanceOf: the EUR display balance once the display currency is proven EUR, the native
/// balance in the account's own currency otherwise.
fn balance_of(
    acc: &FinaryAccount,
    display_is_eur: bool,
    fallback: Option<&str>,
) -> (Decimal, String) {
    if display_is_eur && let Some(display) = acc.display_balance {
        return (display, "EUR".to_owned());
    }
    let currency = non_blank(acc.currency_code())
        .or(non_blank(fallback))
        .unwrap_or("EUR");
    (acc.balance.unwrap_or(Decimal::ZERO), currency.to_owned())
}

/// transactionCurrency: the transaction's own, else its Finary account's native currency.
fn transaction_currency(tx: &Transaction, acc: &FinaryAccount) -> Result<String, Failure> {
    non_blank(tx.currency.as_ref().and_then(|c| c.code.as_deref()))
        .or(non_blank(acc.currency_code()))
        .map(str::to_owned)
        .ok_or_else(|| {
            sync_error(format!(
                "Finary transaction {} on account {} has no currency, and neither does its account",
                tx.id.as_deref().unwrap_or("null"),
                acc.name.as_deref().unwrap_or("null")
            ))
        })
}

/// The preview's writes and its response, given what was fetched.
async fn finish_preview(
    conn: &mut DbHandle<'_>,
    state: &AppState,
    member_id: i64,
    session_id: i64,
    fetched: Fetched,
) -> Result<PreviewResponse, Failure> {
    let now = state.clock.now();
    let mut tx_count: HashMap<Option<&str>, usize> = HashMap::new();
    for tx in fetched.transactions.values().flatten() {
        if tx.account.is_none() {
            return Err(sync_error(
                "Finary API preview failed: a transaction carries no account",
            ));
        }
        *tx_count.entry(tx.account_id()).or_default() += 1;
    }
    let total: usize = fetched.transactions.values().map(Vec::len).sum();

    let display_is_eur = display_currency_is_eur(&fetched.accounts);
    let previews = fetched
        .accounts
        .iter()
        .map(|c| {
            let acc = &c.account;
            let (amount, currency) = balance_of(acc, display_is_eur, Some("EUR"));
            AccountPreview {
                finary_id: acc.id.clone(),
                finary_name: acc.name.clone(),
                finary_institution: match &acc.institution {
                    Some(institution) => institution.name.clone(),
                    None => Some("Finary".to_owned()),
                },
                finary_category: c.category.to_owned(),
                suggested_type: matcher::suggest_type(c.category),
                current_balance: JavaDouble(amount),
                native_currency: currency,
                transaction_count: tx_count.get(&acc.id.as_deref()).copied().unwrap_or(0),
            }
        })
        .collect();

    let existing = accounts::find_all_by_member(&mut *conn, member_id).await?;
    let rows: Vec<matcher::Row> = fetched
        .accounts
        .iter()
        .map(|c| matcher::Row {
            finary_id: c.account.id.as_deref(),
            name: c.account.name.as_deref(),
            category: c.category,
            slug: c.account.slug.as_deref(),
        })
        .collect();
    let matches = matcher::suggest_all(&rows, &existing);
    let auto_mapped = matches.iter().all(Option::is_some);
    let suggested_mappings = fetched
        .accounts
        .iter()
        .zip(&matches)
        .map(|(c, hit)| AccountMapping {
            finary_id: c.account.id.clone(),
            finary_name: c.account.name.clone(),
            finary_category: Some(c.category.to_owned()),
            action: Some(if hit.is_some() {
                MappingAction::MapExisting
            } else {
                MappingAction::CreateNew
            }),
            target_account_id: hit.map(|a| a.id),
            new_account: None,
        })
        .collect();
    let existing_picsou_accounts = existing.iter().map(AccountResponse::from).collect();

    let token = uuid::Uuid::new_v4().to_string();
    let account_count = fetched.accounts.len();
    state.sync.finary.previews.put(
        token.clone(),
        SyncSession {
            member_id,
            accounts: fetched.accounts,
            transactions: fetched.transactions,
            created_at: now,
        },
    );
    session::update_status(&mut *conn, session_id, session::CONNECTED, Some(now), now).await?;
    tracing::info!(
        "Preview ready: {account_count} accounts, {total} total transactions, autoMapped={auto_mapped}"
    );
    Ok(PreviewResponse {
        accounts: previews,
        existing_picsou_accounts,
        total_transaction_count: total,
        file_token: token,
        auto_mapped,
        suggested_mappings,
    })
}

async fn credentials(state: &AppState, s: &session::FinarySession) -> AppResult<(String, String)> {
    Ok((
        state.crypto.decrypt(&s.email)?,
        state.crypto.decrypt(&s.password)?,
    ))
}

pub async fn preview(
    state: &AppState,
    member_id: i64,
    totp: Option<&str>,
) -> AppResult<PreviewResponse> {
    let s = session::find_by_member(&state.db, member_id)
        .await?
        .ok_or_else(|| sync_error("Finary not connected. Please log in first."))?;
    let (email, password) = credentials(state, &s).await?;
    let fetched = fetch(state, &email, &password, totp).await?;
    let mut tx = state.db.begin().await?;
    let response = finish_preview(&mut tx.as_handle(), state, member_id, s.id, fetched).await?;
    tx.commit().await?;
    Ok(response)
}

pub async fn execute(
    state: &AppState,
    member_id: i64,
    token: Option<&str>,
    mappings: Option<&[AccountMapping]>,
) -> AppResult<ImportResultResponse> {
    let previewed = token.and_then(|token| {
        state
            .sync
            .finary
            .previews
            .get(token, member_id, state.clock.now())
    });
    if let Some(session) = previewed {
        ask_first(state, member_id, &session.accounts, &session.transactions).await?;
    }
    let mut tx = state.db.begin().await?;
    let result = execute_in(&mut tx.as_handle(), state, member_id, token, mappings).await?;
    tx.commit().await?;
    if let Err(e) = crate::ledger::reconstruct::reconstruct_member_accounts(state, member_id).await
    {
        tracing::warn!("Finary history reconstruction failed (member={member_id}): {e}");
    }
    Ok(result)
}

/// What the import asks of outside services, asked before its transaction opens: the ticker of
/// each security, and the EUR rate of every currency the ledgers it rebuilds convert from (the
/// member's accounts and rows as stored, Finary's accounts and transactions). All are
/// remembered, so the import finds them in memory and awaits no provider while it holds
/// SQLite's only writer.
async fn ask_first(
    state: &AppState,
    member_id: i64,
    accounts: &[Categorized],
    transactions: &HashMap<&'static str, Vec<Transaction>>,
) -> sqlx::Result<()> {
    let mut needs =
        valuation::currency_needs_of_member(&mut state.db.pool_handle(), member_id).await?;
    for categorized in accounts {
        holdings::resolve_first(&*state.sync.finary.isin, &categorized.account).await;
        if let Some(currency) = categorized.account.currency_code() {
            needs.currency(currency);
        }
    }
    let codes = transactions
        .values()
        .flatten()
        .filter_map(|tx| tx.currency.as_ref()?.code.as_deref());
    for currency in codes {
        needs.currency(currency);
    }
    state
        .ledger
        .prices
        .prefetch(&needs, state.clock.today())
        .await;
    Ok(())
}

fn account_key(category: Option<&str>, id: Option<&str>) -> String {
    format!("{}::{}", category.unwrap_or("null"), id.unwrap_or("null"))
}

/// Two Finary rows written into one Picsou account would each replace its balance and
/// holdings; a CREATE_NEW row writes into the account already holding its external id.
async fn reject_shared_targets(
    conn: &mut DbHandle<'_>,
    mappings: &[AccountMapping],
    by_key: &HashMap<String, &FinaryAccount>,
    member_id: i64,
) -> Result<(), Failure> {
    let mut targets = Vec::new();
    for mapping in mappings {
        let category = mapping.finary_category.as_deref();
        if !by_key.contains_key(&account_key(category, mapping.finary_id.as_deref())) {
            continue;
        }
        match mapping.action {
            Some(MappingAction::MapExisting) => targets.push(mapping.target_account_id),
            Some(MappingAction::CreateNew) => {
                let external_id =
                    matcher::external_id(category.unwrap_or("null"), mapping.finary_id.as_deref());
                if let Some(existing) =
                    accounts::find_by_external_id_and_member(&mut *conn, &external_id, member_id)
                        .await?
                {
                    targets.push(Some(existing.id));
                }
            }
            _ => {}
        }
    }
    match matcher::shared_target(&targets) {
        Some(shared) => Err(sync_error(format!(
            "Two Finary accounts are mapped onto the same Picsou account {shared} -- pick a different account for one of them"
        ))),
        None => Ok(()),
    }
}

async fn execute_in(
    conn: &mut DbHandle<'_>,
    state: &AppState,
    member_id: i64,
    token: Option<&str>,
    mappings: Option<&[AccountMapping]>,
) -> Result<ImportResultResponse, Failure> {
    let now = state.clock.now();
    let today = state.clock.today();
    let token = token.ok_or_else(|| AppError::internal("Finary sync token is null"))?;
    let session = state
        .sync
        .finary
        .previews
        .get(token, member_id, now)
        .ok_or_else(|| sync_error("Sync session expired or invalid -- please start a new sync"))?;
    let mappings = mappings.ok_or_else(|| AppError::internal("Finary mappings are null"))?;
    let mut by_key: HashMap<String, &FinaryAccount> = HashMap::new();
    for c in &session.accounts {
        by_key
            .entry(account_key(Some(c.category), c.account.id.as_deref()))
            .or_insert(&c.account);
    }
    reject_shared_targets(conn, mappings, &by_key, member_id).await?;
    state.sync.finary.previews.remove(token);

    let display_is_eur = display_currency_is_eur(&session.accounts);
    let (mut created, mut mapped, mut skipped, mut imported_count) = (0, 0, 0, 0);
    let mut imported = Vec::new();

    for mapping in mappings {
        let category = mapping.finary_category.as_deref().unwrap_or("null");
        let Some(finary) = by_key
            .get(&account_key(
                mapping.finary_category.as_deref(),
                mapping.finary_id.as_deref(),
            ))
            .copied()
        else {
            continue;
        };
        let external_id = matcher::external_id(category, finary.id.as_deref());

        let (mut account, balance) = match mapping.action {
            None => continue,
            Some(MappingAction::Skip) => {
                skipped += 1;
                continue;
            }
            Some(MappingAction::MapExisting) => {
                let target = mapping.target_account_id;
                let mut account = match target {
                    Some(id) => accounts::find_by_id_and_member(&mut *conn, id, member_id).await?,
                    None => None,
                }
                .ok_or_else(|| {
                    sync_error(format!(
                        "Account {} not found",
                        target.map_or_else(|| "null".to_owned(), |id| id.to_string())
                    ))
                })?;
                let balance = apply_balance(&mut account, finary, display_is_eur);
                account.last_synced_at = Some(now);
                if matcher::owned_by_another_connector(&account) {
                    tracing::warn!(
                        "Finary mapping onto account {} keeps its external id and holdings (owned by another connector)",
                        account.id
                    );
                } else {
                    account.external_account_id = Some(external_id);
                }
                mapped += 1;
                (accounts::update(&mut *conn, &account, now).await?, balance)
            }
            Some(MappingAction::CreateNew) => {
                match accounts::find_by_external_id_and_member(&mut *conn, &external_id, member_id)
                    .await?
                {
                    Some(mut existing) => {
                        let balance = apply_balance(&mut existing, finary, display_is_eur);
                        existing.last_synced_at = Some(now);
                        mapped += 1;
                        (accounts::update(&mut *conn, &existing, now).await?, balance)
                    }
                    None => {
                        let details = mapping.new_account.as_ref().ok_or_else(|| {
                            AppError::internal("CREATE_NEW mapping without newAccount")
                        })?;
                        let account_type = details.account_type.ok_or_else(|| {
                            AppError::internal("CREATE_NEW mapping without a type")
                        })?;
                        let name = details.name.clone().ok_or_else(|| {
                            AppError::internal("CREATE_NEW mapping without a name")
                        })?;
                        let (amount, currency) =
                            balance_of(finary, display_is_eur, details.currency.as_deref());
                        let mut draft = NewAccount::new(member_id, name, account_type);
                        draft.provider = Some(
                            details
                                .provider
                                .clone()
                                .unwrap_or_else(|| "Finary".to_owned()),
                        );
                        draft.currency = currency;
                        draft.current_balance = amount;
                        draft.is_manual = true;
                        draft.color = details
                            .color
                            .clone()
                            .unwrap_or_else(|| matcher::default_color(account_type).to_owned());
                        draft.external_account_id = Some(external_id);
                        draft.last_synced_at = Some(now);
                        created += 1;
                        (accounts::insert(&mut *conn, &draft, now).await?, amount)
                    }
                }
            }
        };

        if !matcher::owned_by_another_connector(&account) {
            holdings::import(
                &mut *conn,
                &*state.sync.finary.isin,
                &mut account,
                finary,
                display_is_eur,
                now,
            )
            .await?;
            account = accounts::update(&mut *conn, &account, now).await?;
        }

        let account_txs: Vec<&Transaction> = session
            .transactions
            .get(category)
            .into_iter()
            .flatten()
            .filter(|tx| tx.account_id().is_some() && tx.account_id() == finary.id.as_deref())
            .collect();
        if !account_txs.is_empty() {
            let mut rows = Vec::with_capacity(account_txs.len());
            for tx in account_txs {
                rows.push(transaction_row(&account, tx, finary)?);
            }
            imported_count +=
                persistence::import_transactions(&mut *conn, account.id, rows, now).await?;
            manual_ledger::reconstruct_snapshots_from_db(
                &mut *conn,
                &account,
                &*state.ledger.prices,
                today,
                now,
            )
            .await?;
        }

        imported.push(ImportedAccountSummary {
            id: account.id,
            name: account.name.clone(),
            account_type: account.account_type,
            current_balance: JavaDouble(balance),
            color: account.color.clone(),
        });
    }

    tracing::info!(
        "Finary sync completed: {created} created, {mapped} mapped, {skipped} skipped, {imported_count} transactions"
    );
    Ok(ImportResultResponse {
        accounts_created: created,
        accounts_mapped: mapped,
        accounts_skipped: skipped,
        snapshots_created: 0,
        transactions_imported: imported_count,
        imported_accounts: imported,
    })
}

fn apply_balance(account: &mut Account, finary: &FinaryAccount, display_is_eur: bool) -> Decimal {
    let (amount, currency) = balance_of(finary, display_is_eur, Some(&account.currency));
    account.current_balance = amount;
    account.currency = currency;
    amount
}

fn transaction_row(
    account: &Account,
    tx: &Transaction,
    finary: &FinaryAccount,
) -> Result<NewTransaction, Failure> {
    let date = tx
        .date
        .as_deref()
        .and_then(|d| DateTime::parse_from_rfc3339(d).ok())
        .ok_or_else(|| AppError::internal("Finary transaction date is not an instant"))?
        .with_timezone(&Utc)
        .date_naive();
    let description = tx
        .display_name
        .clone()
        .or_else(|| tx.name.clone())
        .ok_or_else(|| AppError::internal("Finary transaction without a name"))?;
    let mut row = NewTransaction::new(
        account.id,
        date,
        description,
        tx.value.unwrap_or(Decimal::ZERO),
    );
    row.kind = tx.transaction_type.clone();
    row.category = Some(
        tx.category
            .as_ref()
            .map_or_else(String::new, |c| c.name.clone().unwrap_or_default()),
    );
    row.native_currency = transaction_currency(tx, finary)?;
    Ok(row)
}

/// autoSync: preview and execute in one step when every Finary account is already mapped.
pub async fn auto_sync(state: &AppState, member_id: i64) -> AppResult<FinaryAutoSyncResponse> {
    let Some(s) = session::find_by_member(&state.db, member_id).await? else {
        return Ok(FinaryAutoSyncResponse::idle(AutoSyncStatus::NotConnected));
    };
    if s.status == session::TOTP_REQUIRED {
        return Ok(FinaryAutoSyncResponse::idle(AutoSyncStatus::TotpRequired));
    }
    if s.status != session::CONNECTED {
        return Ok(FinaryAutoSyncResponse::idle(AutoSyncStatus::NotConnected));
    }
    let (email, password) = credentials(state, &s).await?;
    let fetched = match fetch(state, &email, &password, None).await {
        Ok(fetched) => fetched,
        Err(Failure::Totp(_)) => {
            let now = state.clock.now();
            session::update_status(&state.db, s.id, session::TOTP_REQUIRED, None, now).await?;
            return Ok(FinaryAutoSyncResponse::idle(AutoSyncStatus::TotpRequired));
        }
        Err(failure) => {
            tracing::error!("Finary auto-sync failed for member {member_id}");
            return Err(failure.into());
        }
    };
    ask_first(state, member_id, &fetched.accounts, &fetched.transactions).await?;
    // One transaction, as Java's @Transactional autoSync: a failed import leaves nothing behind.
    let mut tx = state.db.begin().await?;
    let preview = finish_preview(&mut tx.as_handle(), state, member_id, s.id, fetched).await?;
    let response = if preview.auto_mapped {
        let result = execute_in(
            &mut tx.as_handle(),
            state,
            member_id,
            Some(&preview.file_token),
            Some(&preview.suggested_mappings),
        )
        .await
        .inspect_err(|_| tracing::error!("Finary auto-sync failed for member {member_id}"))?;
        FinaryAutoSyncResponse {
            status: AutoSyncStatus::Ok,
            accounts_synced: result.accounts_created + result.accounts_mapped,
            new_account_count: 0,
        }
    } else {
        FinaryAutoSyncResponse {
            status: AutoSyncStatus::NeedsMapping,
            accounts_synced: 0,
            new_account_count: preview.accounts.len(),
        }
    };
    tx.commit().await?;
    if let Err(e) = crate::ledger::reconstruct::reconstruct_member_accounts(state, member_id).await
    {
        tracing::warn!("Finary auto-sync history reconstruction failed (member={member_id}): {e}");
    }
    Ok(response)
}
