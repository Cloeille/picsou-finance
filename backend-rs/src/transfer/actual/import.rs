//! Mapping validation, the re-import sync plan and the transactional import
//! (ActualBudgetImportService.resolve, syncPlan, executeImport).

use std::collections::{HashMap, HashSet};

use rust_decimal::Decimal;
use sqlx::PgConnection;

use super::parser::{Kind, ParsedBudget, SourceAccount, SourceCategory, SourceTransaction};
use super::{
    AccountAction, AccountMapping, CategoryAction, CategoryMapping, ImportRequest,
    NewAccountDetails, PREFIX, PREVIEW_EXPIRED, ResultResponse, Warning, WarningReason, java_strip,
};
use crate::AppState;
use crate::error::{AppError, AppResult};
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::model::{Account, AccountType, Category, CategoryKind, NewTransaction};
use crate::ledger::sync_values::java_is_blank;
use crate::ledger::{categorize, manual_ledger, transactions};
use crate::transfer::preview_store::Entry;

/// Keeps each ANY list well under what one statement should carry.
const LOOKUP_BATCH: usize = 1_000;
const MAX_NAME: usize = 100;
const MAX_DESCRIPTION: usize = 255;
const DEFAULT_COLOR: &str = "#6366f1";
const TRANSFER_SLUG: &str = "virement-interne";
const ACTUAL_TRANSFER_SLUG: &str = "actual-transfer";
/// A re-import deleting more than this many rows, or more than this share of an account's
/// imported rows, needs acknowledgeLargeDeletion: a wrong mapping must not silently wipe a
/// history.
const LARGE_DELETION_ROWS: usize = 200;
const LARGE_DELETION_PERCENT: usize = 20;

fn bad(message: impl Into<String>) -> AppError {
    AppError::BadRequest(message.into())
}

/// What a request does to stored rows. to_add holds indexes into the budget's transactions;
/// to_move maps a stored row to the source account it now belongs to, in plan order.
pub(super) struct SyncPlan {
    pub to_add: Vec<usize>,
    pub to_delete: Vec<i64>,
    pub to_move: Vec<(i64, String)>,
    move_sources: Vec<i64>,
    unchanged: usize,
    kept_moved: usize,
    kept_missing: usize,
    pub large_deletion: bool,
}

impl SyncPlan {
    pub fn warnings(&self) -> Vec<Warning> {
        let mut warnings = Vec::new();
        if self.kept_missing > 0 {
            warnings.push(Warning {
                reason: WarningReason::KeptMissing,
                count: self.kept_missing,
            });
        }
        if self.kept_moved > 0 {
            warnings.push(Warning {
                reason: WarningReason::KeptMoved,
                count: self.kept_moved,
            });
        }
        warnings
    }

    fn needs_transfer(&self, budget: &ParsedBudget) -> bool {
        self.to_add
            .iter()
            .any(|&i| budget.transactions[i].kind != Kind::Regular)
    }
}

pub(super) struct Resolved<'a> {
    account_mappings: HashMap<&'a str, &'a AccountMapping>,
    category_mappings: HashMap<&'a str, &'a CategoryMapping>,
    targets: HashMap<String, Account>,
    target_categories: HashMap<String, Category>,
    group_parents: HashMap<String, Category>,
    member_accounts: HashMap<i64, Account>,
    pub plan: SyncPlan,
    existing_transfer: Option<Category>,
}

pub(super) fn import_created(account: &Account) -> bool {
    account
        .external_account_id
        .as_deref()
        .is_some_and(|id| id.starts_with(PREFIX))
}

fn source_id(id: &str) -> String {
    format!("{PREFIX}{id}")
}

fn is_blank(value: Option<&str>) -> bool {
    value.is_none_or(java_is_blank)
}

/// String.substring(0, max) on a value longer than max UTF-16 units, never splitting a char.
fn truncate(value: &str, max: usize) -> String {
    let mut units = 0;
    value
        .chars()
        .take_while(|c| {
            units += c.len_utf16();
            units <= max
        })
        .collect()
}

fn name_or(value: Option<&str>, fallback: &str) -> String {
    match value {
        Some(v) if !java_is_blank(v) => java_strip(v).to_owned(),
        _ => fallback.to_owned(),
    }
}

fn kind_of(source: &SourceCategory) -> CategoryKind {
    if source.income {
        CategoryKind::Income
    } else {
        CategoryKind::Expense
    }
}

/// Validates a request against its cached preview; shared by the dry run and the import.
pub(super) async fn resolve<'a>(
    conn: &mut PgConnection,
    budget: &ParsedBudget,
    request: &'a ImportRequest,
    member_id: i64,
) -> AppResult<Resolved<'a>> {
    let currency = request.currency();
    if let Some(own) = budget.currency.as_deref()
        && own != currency
    {
        return Err(bad(format!("The budget currency is {own}, not {currency}")));
    }
    let account_mappings = by_source(
        request.account_mappings(),
        |m| m.source_id.as_deref().unwrap_or_default(),
        budget.accounts.iter().map(|a| a.id.as_str()),
    )?;
    let category_mappings = by_source(
        request.category_mappings(),
        |m| m.source_id.as_deref().unwrap_or_default(),
        budget.categories.iter().map(|c| c.id.as_str()),
    )?;
    let targets =
        resolve_accounts(&mut *conn, budget, &account_mappings, currency, member_id).await?;
    let by_slug = categories_by_slug(&mut *conn, member_id).await?;
    let target_categories =
        resolve_categories(&mut *conn, budget, &category_mappings, &by_slug, member_id).await?;
    let group_parents =
        resolve_group_parents(budget, &category_mappings, &target_categories, &by_slug)?;
    let importable: Vec<usize> = (0..budget.transactions.len())
        .filter(|&i| {
            account_mappings[budget.transactions[i].account_id.as_str()].action
                != Some(AccountAction::Skip)
        })
        .collect();
    let member_accounts: HashMap<i64, Account> =
        accounts::find_all_by_member(&mut *conn, member_id)
            .await?
            .into_iter()
            .map(|a| (a.id, a))
            .collect();
    let plan = sync_plan(
        &mut *conn,
        budget,
        &importable,
        &targets,
        &member_accounts,
        member_id,
    )
    .await?;
    let existing_transfer = if plan.needs_transfer(budget) {
        existing_transfer_category(&by_slug)?
    } else {
        None
    };
    Ok(Resolved {
        account_mappings,
        category_mappings,
        targets,
        target_categories,
        group_parents,
        member_accounts,
        plan,
        existing_transfer,
    })
}

fn by_source<'a, 'b, M>(
    provided: impl Iterator<Item = &'a M>,
    source_id: impl Fn(&'a M) -> &'a str,
    expected: impl Iterator<Item = &'b str>,
) -> AppResult<HashMap<&'a str, &'a M>> {
    let message = "Each Actual account and category must be mapped exactly once";
    let mut mapped: HashMap<&'a str, &'a M> = HashMap::new();
    for mapping in provided {
        if mapped.insert(source_id(mapping), mapping).is_some() {
            return Err(bad(message));
        }
    }
    let expected: HashSet<&str> = expected.collect();
    if mapped.len() != expected.len() || !mapped.keys().all(|k| expected.contains(k)) {
        return Err(bad(message));
    }
    Ok(mapped)
}

async fn resolve_accounts(
    conn: &mut PgConnection,
    budget: &ParsedBudget,
    mappings: &HashMap<&str, &AccountMapping>,
    currency: &str,
    member_id: i64,
) -> AppResult<HashMap<String, Account>> {
    let mut targets = HashMap::new();
    let mut used = HashSet::new();
    for source in &budget.accounts {
        let mapping = mappings[source.id.as_str()];
        let target = match mapping.action {
            Some(AccountAction::Skip) | None => None,
            Some(AccountAction::MapExisting) => {
                let Some(target_id) = mapping.target_account_id else {
                    return Err(bad(format!("Choose the account '{}' maps to", source.name)));
                };
                let existing = accounts::find_by_id_and_member(&mut *conn, target_id, member_id)
                    .await?
                    .ok_or_else(|| bad("Target account not found"))?;
                require_ledger(Some(existing.account_type), &existing.currency, currency)?;
                // An imported account belongs to the source it was created for: a foreign
                // source's rows in it would be deleted by the next re-import of its own file.
                if import_created(&existing)
                    && existing.external_account_id.as_deref()
                        != Some(source_id(&source.id).as_str())
                {
                    return Err(bad(
                        "This account was created by another Actual import; map it from that file or create a new account",
                    ));
                }
                Some(existing)
            }
            Some(AccountAction::CreateNew) => {
                require_new_account(mapping.new_account.as_ref(), currency)?;
                let external_id = source_id(&source.id);
                if accounts::exists_soft_deleted_by_external_id(&mut *conn, &external_id, member_id)
                    .await?
                {
                    return Err(bad(format!(
                        "The account '{}' was imported before and then deleted",
                        source.name
                    )));
                }
                let reused =
                    accounts::find_by_external_id_and_member(&mut *conn, &external_id, member_id)
                        .await?;
                if let Some(reused) = &reused {
                    require_ledger(Some(reused.account_type), &reused.currency, currency)?;
                }
                reused
            }
        };
        if let Some(target) = target {
            if !used.insert(target.id) {
                return Err(bad(
                    "Several Actual accounts cannot map to the same Picsou account",
                ));
            }
            targets.insert(source.id.clone(), target);
        }
    }
    Ok(targets)
}

fn require_ledger(
    account_type: Option<AccountType>,
    account_currency: &str,
    currency: &str,
) -> AppResult<()> {
    // Actual holds cash ledgers; AccountType::is_ledger names the accounts valued instead.
    if !account_type.is_some_and(AccountType::is_ledger) {
        return Err(bad(
            "Investment and property accounts cannot receive Actual Budget transactions",
        ));
    }
    // Picsou stores a loan as the positive amount owed and negates it in totals; Actual's
    // negative ledger would then count as wealth.
    if account_type == Some(AccountType::Loan) {
        return Err(bad(
            "Loan accounts cannot receive Actual Budget transactions; use another account type",
        ));
    }
    if currency != account_currency {
        return Err(bad(
            "Target account currency does not match the budget currency",
        ));
    }
    Ok(())
}

fn valid_color(color: &str) -> bool {
    color.len() == 7 && color.starts_with('#') && color[1..].bytes().all(|b| b.is_ascii_hexdigit())
}

fn require_new_account(details: Option<&NewAccountDetails>, currency: &str) -> AppResult<()> {
    let Some(details) = details else {
        return Err(bad("Invalid new account details"));
    };
    let utf16 = |s: &str| s.encode_utf16().count();
    if is_blank(details.name.as_deref())
        || details.name.as_deref().is_some_and(|n| utf16(n) > MAX_NAME)
        || details
            .provider
            .as_deref()
            .is_some_and(|p| utf16(p) > MAX_NAME)
        || details.color.as_deref().is_some_and(|c| !valid_color(c))
        || details.currency.as_deref().is_some_and(|c| c != currency)
    {
        return Err(bad("Invalid new account details"));
    }
    require_ledger(details.account_type, currency, currency)
}

async fn categories_by_slug(
    conn: &mut PgConnection,
    member_id: i64,
) -> AppResult<HashMap<String, Category>> {
    let mut by_slug = HashMap::new();
    for category in categorize::categories_of_member(&mut *conn, member_id, true).await? {
        if let Some(slug) = category.slug.clone() {
            by_slug.entry(slug).or_insert(category);
        }
    }
    Ok(by_slug)
}

/// Resolves MAP_EXISTING targets and the categories an earlier import already created.
async fn resolve_categories(
    conn: &mut PgConnection,
    budget: &ParsedBudget,
    mappings: &HashMap<&str, &CategoryMapping>,
    by_slug: &HashMap<String, Category>,
    member_id: i64,
) -> AppResult<HashMap<String, Category>> {
    let mut resolved = HashMap::new();
    for source in &budget.categories {
        let mapping = mappings[source.id.as_str()];
        let kind = kind_of(source);
        match mapping.action {
            Some(CategoryAction::MapExisting) => {
                let target = match mapping.target_category_id {
                    Some(id) => categorize::category_of_member(&mut *conn, id, member_id)
                        .await?
                        .filter(|c| !c.archived),
                    None => None,
                };
                let Some(target) = target else {
                    return Err(bad(format!(
                        "Target category for '{}' not found or archived",
                        source.name
                    )));
                };
                if target.kind != kind {
                    return Err(bad(format!(
                        "Category '{}' must map to a {} category",
                        source.name,
                        kind.as_str()
                    )));
                }
                resolved.insert(source.id.clone(), target);
            }
            Some(CategoryAction::CreateNew) => {
                let name = mapping.name.as_deref();
                if is_blank(name)
                    || name.is_some_and(|n| java_strip(n).encode_utf16().count() > MAX_NAME)
                {
                    return Err(bad(format!("Invalid name for category '{}'", source.name)));
                }
                if let Some(existing) = by_slug.get(&source_id(&source.id)) {
                    require_reusable(existing, kind)?;
                    resolved.insert(source.id.clone(), existing.clone());
                }
            }
            Some(CategoryAction::Uncategorized) | None => {}
        }
    }
    Ok(resolved)
}

/// Actual groups become Picsou parent categories, created only for groups that gain a new
/// child. Actual groups hold a single kind, which Picsou requires of a parent and its children.
fn resolve_group_parents(
    budget: &ParsedBudget,
    mappings: &HashMap<&str, &CategoryMapping>,
    resolved: &HashMap<String, Category>,
    by_slug: &HashMap<String, Category>,
) -> AppResult<HashMap<String, Category>> {
    let mut parents = HashMap::new();
    for source in &budget.categories {
        let Some(group_id) = &source.group_id else {
            continue;
        };
        if mappings[source.id.as_str()].action != Some(CategoryAction::CreateNew)
            || resolved.contains_key(&source.id)
        {
            continue;
        }
        if let Some(existing) = by_slug.get(&format!("{PREFIX}group_{group_id}")) {
            require_reusable(existing, kind_of(source))?;
            if existing.parent_id.is_some() {
                return Err(bad(format!(
                    "The category created for group '{}' is no longer top-level",
                    source.group_name.as_deref().unwrap_or("null")
                )));
            }
            parents.insert(group_id.clone(), existing.clone());
        }
    }
    Ok(parents)
}

fn require_reusable(existing: &Category, kind: CategoryKind) -> AppResult<()> {
    if existing.archived {
        return Err(bad(format!(
            "Category '{}' from an earlier import is archived; restore it or map it",
            existing.name
        )));
    }
    if existing.kind != kind {
        return Err(bad(format!(
            "Category '{}' from an earlier import changed kind",
            existing.name
        )));
    }
    Ok(())
}

/// The member's default "Virement interne" category, else the one an earlier import created.
fn existing_transfer_category(by_slug: &HashMap<String, Category>) -> AppResult<Option<Category>> {
    for slug in [TRANSFER_SLUG, ACTUAL_TRANSFER_SLUG] {
        if let Some(category) = by_slug.get(slug)
            && !category.archived
            && category.kind == CategoryKind::Transfer
        {
            return Ok(Some(category.clone()));
        }
    }
    if by_slug.contains_key(ACTUAL_TRANSFER_SLUG) {
        return Err(bad(
            "The 'Actual Budget transfer' category is archived or no longer a transfer category",
        ));
    }
    Ok(None)
}

#[derive(crate::db::DbFromRow)]
struct StoredRow {
    id: i64,
    external_id: String,
    account_id: i64,
}

/// findStoredExternalIdsInAccounts: the prefix goes through LIKE, so its "_" matches any
/// character; callers re-check the prefix.
async fn stored_in_accounts(
    exec: impl crate::db::DbExec<'_>,
    member_id: i64,
    account_ids: &[i64],
) -> sqlx::Result<Vec<StoredRow>> {
    crate::db::query_as(
        "SELECT t.id, t.external_id, t.account_id FROM \"transaction\" t JOIN account a ON a.id = t.account_id \
         WHERE a.member_id = $1 AND a.deleted_at IS NULL AND t.account_id = ANY($2) \
         AND t.external_id LIKE $3 || '%' ORDER BY t.id",
    )
    .bind(member_id)
    .bind(account_ids)
    .bind(PREFIX)
    .fetch_all(exec)
    .await
}

/// Reconciles the file with the rows earlier imports stored, matched by Actual id rather than
/// by date since the user may have moved an imported row to another date.
///
/// Only a synchronised account follows the file: one an import created for a source account
/// of this file (actual_<source id>) and mapped from that same source. There, rows Actual no
/// longer emits are deleted and rows Actual moved to another synchronised account follow
/// them. Every other target is an account the user made: append-only, its rows stay and are
/// reported. A row still in the account an import created for its own Actual account, while
/// that Actual account now maps elsewhere, means the mapping changed and is refused.
async fn sync_plan(
    conn: &mut PgConnection,
    budget: &ParsedBudget,
    importable: &[usize],
    targets: &HashMap<String, Account>,
    member_accounts: &HashMap<i64, Account>,
    member_id: i64,
) -> AppResult<SyncPlan> {
    let synchronised: HashSet<i64> = targets
        .iter()
        .filter(|(source, account)| {
            account.external_account_id.as_deref() == Some(source_id(source).as_str())
        })
        .map(|(_, account)| account.id)
        .collect();

    let mut external_ids: Vec<String> = Vec::new();
    let mut seen = HashSet::new();
    for &i in importable {
        let id = source_id(&budget.transactions[i].id);
        if seen.insert(id.clone()) {
            external_ids.push(id);
        }
    }
    let mut stored: HashMap<String, transactions::StoredExternalId> = HashMap::new();
    for batch in external_ids.chunks(LOOKUP_BATCH) {
        for row in transactions::stored_external_ids(&mut *conn, member_id, batch).await? {
            stored.insert(row.external_id.clone(), row);
        }
    }

    let mut to_add = Vec::new();
    let mut to_move: Vec<(i64, String)> = Vec::new();
    let mut move_sources: Vec<i64> = Vec::new();
    let mut unchanged = 0;
    let mut kept_moved = 0;
    for &i in importable {
        let tx = &budget.transactions[i];
        let Some(row) = stored.get(&source_id(&tx.id)) else {
            to_add.push(i);
            continue;
        };
        let target = targets.get(&tx.account_id);
        if target.is_some_and(|t| t.id == row.account_id) {
            unchanged += 1;
            continue;
        }
        if synchronised.contains(&row.account_id)
            && target.is_none_or(|t| synchronised.contains(&t.id))
        {
            to_move.retain(|(id, _)| *id != row.id);
            to_move.push((row.id, tx.account_id.clone()));
            if !move_sources.contains(&row.account_id) {
                move_sources.push(row.account_id);
            }
            continue;
        }
        if let Some(current) = member_accounts.get(&row.account_id)
            && current.external_account_id.as_deref() == Some(source_id(&tx.account_id).as_str())
        {
            return Err(bad(format!(
                "The Actual account '{}' was imported into '{}' before; map it to that account to update it",
                source_name(budget, &tx.account_id),
                current.name
            )));
        }
        kept_moved += 1;
    }

    let emitted: HashSet<String> = budget
        .transactions
        .iter()
        .map(|tx| source_id(&tx.id))
        .collect();
    let mut to_delete = Vec::new();
    let mut stored_by_account: HashMap<i64, usize> = HashMap::new();
    let mut deleted_by_account: HashMap<i64, usize> = HashMap::new();
    let mut kept_missing = 0;
    let target_ids: Vec<i64> = targets.values().map(|a| a.id).collect();
    if !target_ids.is_empty() {
        for row in stored_in_accounts(&mut *conn, member_id, &target_ids).await? {
            if !row.external_id.starts_with(PREFIX) {
                continue;
            }
            *stored_by_account.entry(row.account_id).or_default() += 1;
            if emitted.contains(&row.external_id) {
                continue;
            }
            if synchronised.contains(&row.account_id) {
                to_delete.push(row.id);
                *deleted_by_account.entry(row.account_id).or_default() += 1;
            } else {
                kept_missing += 1;
            }
        }
    }
    let large_deletion = to_delete.len() > LARGE_DELETION_ROWS
        || deleted_by_account.iter().any(|(account, deleted)| {
            deleted * 100 > stored_by_account[account] * LARGE_DELETION_PERCENT
        });
    Ok(SyncPlan {
        to_add,
        to_delete,
        to_move,
        move_sources,
        unchanged,
        kept_moved,
        kept_missing,
        large_deletion,
    })
}

fn source_name<'a>(budget: &'a ParsedBudget, account_id: &'a str) -> &'a str {
    budget
        .accounts
        .iter()
        .find(|a| a.id == account_id)
        .map_or(account_id, |a: &SourceAccount| a.name.as_str())
}

#[derive(Default)]
struct Counts {
    accounts_created: usize,
    accounts_mapped: usize,
    accounts_skipped: usize,
    categories_created: usize,
}

/// executeImport: validates, refuses an unacknowledged large deletion, consumes the token,
/// then writes everything in one transaction. The caller restores the token on failure.
pub(super) async fn execute(
    state: &AppState,
    entry: &Entry<ParsedBudget>,
    request: &ImportRequest,
    member_id: i64,
) -> AppResult<ResultResponse> {
    let budget = &*entry.payload;
    let now = state.clock.now();
    let today = state.clock.today();
    let mut tx = state.db.begin().await?;
    let mut resolved = resolve(&mut tx, budget, request, member_id).await?;
    if resolved.plan.large_deletion && !request.acknowledge_large_deletion {
        return Err(bad(format!(
            "This import would delete {} transactions; review the plan and confirm the deletion",
            resolved.plan.to_delete.len()
        )));
    }
    // Consumed before writing: of two executes racing on one preview, exactly one passes.
    if !state
        .transfer
        .actual
        .previews
        .consume(request.token(), entry)
    {
        return Err(bad(PREVIEW_EXPIRED));
    }
    let member_exists: bool =
        crate::db::query_scalar("SELECT EXISTS(SELECT 1 FROM family_member WHERE id = $1)")
            .bind(member_id)
            .fetch_one(&mut tx)
            .await?;
    if !member_exists {
        return Err(bad("Family member not found"));
    }
    let currency = request.currency();
    let mut counts = Counts::default();
    create_accounts(
        &mut tx,
        budget,
        &mut resolved,
        member_id,
        currency,
        now,
        &mut counts,
    )
    .await?;
    create_categories(&mut tx, budget, &mut resolved, member_id, now, &mut counts).await?;
    let transfer_category = if !resolved.plan.needs_transfer(budget) {
        None
    } else if let Some(existing) = resolved.existing_transfer.clone() {
        Some(existing)
    } else {
        Some(
            insert_category(
                &mut tx,
                member_id,
                "Actual Budget transfer",
                ACTUAL_TRANSFER_SLUG,
                CategoryKind::Transfer,
                "#64748b",
                None,
                now,
            )
            .await?,
        )
    };

    let plan = &resolved.plan;
    if !plan.to_delete.is_empty() {
        crate::db::query("DELETE FROM \"transaction\" WHERE id = ANY($1)")
            .bind(&plan.to_delete)
            .execute(&mut tx)
            .await?;
    }
    let mut moved = 0;
    for (row_id, source) in &plan.to_move {
        let target = &resolved.targets[source];
        moved += crate::db::query("UPDATE \"transaction\" SET account_id = $2 WHERE id = $1")
            .bind(row_id)
            .bind(target.id)
            .execute(&mut tx)
            .await?
            .rows_affected() as usize;
    }

    let source_categories: HashMap<&str, &SourceCategory> = budget
        .categories
        .iter()
        .map(|c| (c.id.as_str(), c))
        .collect();
    for &i in &plan.to_add {
        let source = &budget.transactions[i];
        let row = to_transaction(
            source,
            &resolved.targets[&source.account_id],
            currency,
            source
                .category_id
                .as_deref()
                .and_then(|c| source_categories.get(c).copied()),
            &resolved.target_categories,
            transfer_category.as_ref(),
        );
        transactions::insert(&mut *tx, &row, now).await?;
    }

    // Accounts an Actual import created follow their ledger, whichever mapping a re-import
    // uses; accounts the user created keep the balance they set.
    let mut ledger: Vec<Account> = Vec::new();
    for source in &budget.accounts {
        if let Some(account) = resolved.targets.get(&source.id)
            && import_created(account)
            && !ledger.iter().any(|a| a.id == account.id)
        {
            ledger.push(account.clone());
        }
    }
    for id in &plan.move_sources {
        if !ledger.iter().any(|a| a.id == *id)
            && let Some(account) = resolved.member_accounts.get(id)
        {
            ledger.push(account.clone());
        }
    }
    let prices = &*state.ledger.prices;
    for mut account in ledger {
        let balance =
            manual_ledger::compute_manual_ledger_balance(&mut tx, &account, prices, today).await?;
        accounts::set_balance(&mut *tx, account.id, balance, None, now).await?;
        account.current_balance = balance;
        manual_ledger::reconstruct_snapshots_from_db(&mut tx, &account, prices, today, now).await?;
    }
    tx.commit().await?;

    let not_importable = budget
        .transactions
        .iter()
        .filter(|t| {
            resolved.account_mappings[t.account_id.as_str()].action == Some(AccountAction::Skip)
        })
        .count();
    tracing::info!(
        "Actual Budget import for member {member_id}: {} rows imported, {} deleted, {moved} moved",
        plan.to_add.len(),
        plan.to_delete.len()
    );
    Ok(ResultResponse {
        accounts_created: counts.accounts_created,
        accounts_mapped: counts.accounts_mapped,
        accounts_skipped: counts.accounts_skipped,
        categories_created: counts.categories_created,
        transactions_imported: plan.to_add.len(),
        transactions_skipped: not_importable + plan.unchanged + plan.kept_moved,
        transactions_deleted: plan.to_delete.len(),
        transactions_moved: moved,
        warnings: plan.warnings(),
    })
}

async fn create_accounts(
    conn: &mut PgConnection,
    budget: &ParsedBudget,
    resolved: &mut Resolved<'_>,
    member_id: i64,
    currency: &str,
    now: chrono::DateTime<chrono::Utc>,
    counts: &mut Counts,
) -> AppResult<()> {
    for source in &budget.accounts {
        let mapping = resolved.account_mappings[source.id.as_str()];
        match mapping.action {
            Some(AccountAction::Skip) | None => counts.accounts_skipped += 1,
            Some(AccountAction::MapExisting) => counts.accounts_mapped += 1,
            Some(AccountAction::CreateNew) => {
                if resolved.targets.contains_key(&source.id) {
                    counts.accounts_mapped += 1;
                    continue;
                }
                let Some(details) = &mapping.new_account else {
                    return Err(bad("Invalid new account details"));
                };
                let Some(account_type) = details.account_type else {
                    return Err(bad("Invalid new account details"));
                };
                let mut draft = NewAccount::new(
                    member_id,
                    java_strip(details.name.as_deref().unwrap_or_default()),
                    account_type,
                );
                draft.provider = details.provider.clone().filter(|p| !java_is_blank(p));
                draft.currency = currency.to_owned();
                draft.current_balance = Decimal::ZERO;
                draft.is_manual = true;
                draft.color = details
                    .color
                    .clone()
                    .unwrap_or_else(|| DEFAULT_COLOR.to_owned());
                draft.external_account_id = Some(source_id(&source.id));
                let account = accounts::insert(&mut *conn, &draft, now).await?;
                resolved.targets.insert(source.id.clone(), account);
                counts.accounts_created += 1;
            }
        }
    }
    Ok(())
}

async fn create_categories(
    conn: &mut PgConnection,
    budget: &ParsedBudget,
    resolved: &mut Resolved<'_>,
    member_id: i64,
    now: chrono::DateTime<chrono::Utc>,
    counts: &mut Counts,
) -> AppResult<()> {
    for source in &budget.categories {
        let mapping = resolved.category_mappings[source.id.as_str()];
        if mapping.action != Some(CategoryAction::CreateNew)
            || resolved.target_categories.contains_key(&source.id)
        {
            continue;
        }
        let mut parent_id = None;
        if let Some(group_id) = &source.group_id {
            let parent = match resolved.group_parents.get(group_id) {
                Some(parent) => parent.clone(),
                None => {
                    let parent = insert_category(
                        &mut *conn,
                        member_id,
                        &truncate(
                            &name_or(source.group_name.as_deref(), "Actual Budget"),
                            MAX_NAME,
                        ),
                        &format!("{PREFIX}group_{group_id}"),
                        kind_of(source),
                        DEFAULT_COLOR,
                        None,
                        now,
                    )
                    .await?;
                    resolved
                        .group_parents
                        .insert(group_id.clone(), parent.clone());
                    counts.categories_created += 1;
                    parent
                }
            };
            parent_id = Some(parent.id);
        }
        let category = insert_category(
            &mut *conn,
            member_id,
            java_strip(mapping.name.as_deref().unwrap_or_default()),
            &source_id(&source.id),
            kind_of(source),
            DEFAULT_COLOR,
            parent_id,
            now,
        )
        .await?;
        resolved
            .target_categories
            .insert(source.id.clone(), category);
        counts.categories_created += 1;
    }
    Ok(())
}

/// Category.builder() with its defaults: no icon, not default, not archived, sort order 0.
#[allow(clippy::too_many_arguments)]
async fn insert_category(
    exec: impl crate::db::DbExec<'_>,
    member_id: i64,
    name: &str,
    slug: &str,
    kind: CategoryKind,
    color: &str,
    parent_id: Option<i64>,
    now: chrono::DateTime<chrono::Utc>,
) -> sqlx::Result<Category> {
    crate::db::query_as(
        "INSERT INTO category (member_id, parent_id, name, slug, kind, color, icon, is_default, archived, \
         sort_order, created_at, updated_at) VALUES ($1, $2, $3, $4, $5::category_kind, $6, NULL, false, false, 0, $7, \
         $7) RETURNING id, member_id, parent_id, name, slug, kind::text AS kind, color, icon, is_default, archived, sort_order",
    )
    .bind(member_id)
    .bind(parent_id)
    .bind(name)
    .bind(slug)
    .bind(kind)
    .bind(color)
    .bind(now)
    .fetch_one(exec)
    .await
}

fn to_transaction(
    tx: &SourceTransaction,
    account: &Account,
    currency: &str,
    source_category: Option<&SourceCategory>,
    target_categories: &HashMap<String, Category>,
    transfer_category: Option<&Category>,
) -> NewTransaction {
    let category = if tx.kind == Kind::Regular {
        source_category.and_then(|c| target_categories.get(&c.id))
    } else {
        transfer_category
    };
    let payee = tx.payee.as_deref().filter(|p| !java_is_blank(p));
    let description = match (tx.notes.as_deref().filter(|n| !java_is_blank(n)), payee) {
        (Some(notes), _) => java_strip(notes).to_owned(),
        (None, Some(payee)) => java_strip(payee).to_owned(),
        (None, None) if tx.kind == Kind::StartingBalance => "Starting balance".to_owned(),
        (None, None) => "Actual Budget transaction".to_owned(),
    };
    let mut row = NewTransaction::new(
        account.id,
        tx.date,
        truncate(&description, MAX_DESCRIPTION),
        tx.amount,
    );
    row.counterparty = payee.map(|p| truncate(java_strip(p), MAX_DESCRIPTION));
    row.category = source_category.map(|c| truncate(&c.name, MAX_NAME));
    row.category_id = category.map(|c| c.id);
    row.category_manual = category.is_some();
    row.native_currency = currency.to_owned();
    row.external_id = Some(source_id(&tx.id));
    row.is_manual = true;
    row
}
