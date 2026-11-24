//! The budget and cashflow queries of TransactionRepository and CategoryRepository.
//!
//! Hidden accounts are left out of the aggregates, as the dashboard does (a second connector
//! often syncs the same bank account), and budget-neutral rows (money moving inside an
//! investment wrapper) out of everything here. Soft-deleted accounts are never joined.

use std::collections::HashMap;

use crate::db::Db;
use chrono::NaiveDate;
use rust_decimal::Decimal;

use crate::db::DbExec;
use crate::ledger::accounts;
use crate::ledger::categorize;
use crate::ledger::manual_transactions::TransactionResponse;
use crate::ledger::model::{Account, CATEGORY_COLUMNS, Category, TRANSACTION_COLUMNS, Transaction};

const TX: &str = TRANSACTION_COLUMNS;
const CATEGORY: &str = CATEGORY_COLUMNS;

fn member_join() -> &'static str {
    "FROM \"transaction\" t JOIN account a ON a.id = t.account_id AND a.deleted_at IS NULL"
}

/// findByMemberIdAndDateBetween: the member's visible rows dated within [from, to], oldest first.
pub async fn member_between<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    from: NaiveDate,
    to: NaiveDate,
) -> sqlx::Result<Vec<Transaction>> {
    crate::db::query_as::<Transaction>(&format!(
        "SELECT {TX} {} WHERE a.member_id = $1 AND a.hidden = false AND t.budget_neutral = false \
         AND t.date BETWEEN $2 AND $3 ORDER BY t.date, t.id",
        member_join()
    ))
    .bind(member_id)
    .bind(from)
    .bind(to)
    .fetch_all(db)
    .await
}

/// findByMemberIdAndKindAndDateBetween with TRANSFER: every transfer leg of the member. Java's
/// query has no ORDER BY; this one has the same shape so PostgreSQL returns the same order.
pub async fn member_transfers_between<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    from: NaiveDate,
    to: NaiveDate,
) -> sqlx::Result<Vec<Transaction>> {
    crate::db::query_as::<Transaction>(&format!(
        "SELECT {TX} {} JOIN category c ON c.id = t.category_id WHERE a.member_id = $1 AND a.hidden = false \
         AND t.budget_neutral = false AND c.kind = 'TRANSFER'::category_kind AND t.date BETWEEN $2 AND $3",
        member_join()
    ))
    .bind(member_id)
    .bind(from)
    .bind(to)
    .fetch_all(db)
    .await
}

/// sumByCategoryIdInAndDateBetween: the signed sum over categories within [from, to].
pub async fn sum_by_categories_between<'e>(
    db: impl DbExec<'e>,
    category_ids: &[i64],
    from: NaiveDate,
    to: NaiveDate,
) -> sqlx::Result<Decimal> {
    if db.is_sqlite() {
        let sum: Option<Decimal> = crate::db::query_scalar(&format!(
            "SELECT dec_sum(t.amount) AS amount {} WHERE t.category_id = ANY($1) AND a.hidden = false \
             AND t.budget_neutral = false AND t.date BETWEEN $2 AND $3",
            member_join()
        ))
        .bind(category_ids)
        .bind(from)
        .bind(to)
        .fetch_one(db)
        .await?;
        return Ok(sum.unwrap_or(Decimal::ZERO));
    }
    crate::db::query_scalar::<Decimal>(&format!(
        "SELECT COALESCE(SUM(t.amount), 0) {} WHERE t.category_id = ANY($1) AND a.hidden = false \
         AND t.budget_neutral = false AND t.date BETWEEN $2 AND $3",
        member_join()
    ))
    .bind(category_ids)
    .bind(from)
    .bind(to)
    .fetch_one(db)
    .await
}

/// findByMemberIdAndCategoryIdInAndDateBetween: newest first.
pub async fn member_in_categories_between<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    category_ids: &[i64],
    from: NaiveDate,
    to: NaiveDate,
) -> sqlx::Result<Vec<Transaction>> {
    crate::db::query_as::<Transaction>(&format!(
        "SELECT {TX} {} WHERE a.member_id = $1 AND a.hidden = false AND t.budget_neutral = false \
         AND t.category_id = ANY($2) AND t.date BETWEEN $3 AND $4 ORDER BY t.date DESC, t.id DESC",
        member_join()
    ))
    .bind(member_id)
    .bind(category_ids)
    .bind(from)
    .bind(to)
    .fetch_all(db)
    .await
}

/// findUncategorizedByMemberId: no managed category yet, newest first (hidden accounts included).
pub async fn uncategorized<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
) -> sqlx::Result<Vec<Transaction>> {
    crate::db::query_as::<Transaction>(&format!(
        "SELECT {TX} {} WHERE a.member_id = $1 AND t.category_id IS NULL AND t.budget_neutral = false \
         ORDER BY t.date DESC, t.id DESC",
        member_join()
    ))
    .bind(member_id)
    .fetch_all(db)
    .await
}

/// findChangeable: rows a new rule could change (no category, or one not set by hand).
pub async fn changeable<'e>(db: impl DbExec<'e>, member_id: i64) -> sqlx::Result<Vec<Transaction>> {
    crate::db::query_as::<Transaction>(&format!(
        "SELECT {TX} {} WHERE a.member_id = $1 AND (t.category_id IS NULL OR t.category_manual = false) \
         AND t.budget_neutral = false ORDER BY t.date DESC, t.id DESC",
        member_join()
    ))
    .bind(member_id)
    .fetch_all(db)
    .await
}

/// searchByMember: hidden accounts are skipped unless asked for by id.
pub async fn search<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    from: NaiveDate,
    to: NaiveDate,
    account_id: Option<i64>,
    category_id: Option<i64>,
) -> sqlx::Result<Vec<Transaction>> {
    crate::db::query_as::<Transaction>(&format!(
        "SELECT {TX} {} WHERE a.member_id = $1 AND t.budget_neutral = false AND t.date BETWEEN $2 AND $3 \
         AND (($4::bigint IS NULL AND a.hidden = false) OR t.account_id = $4) \
         AND ($5::bigint IS NULL OR t.category_id = $5) ORDER BY t.date DESC, t.id DESC",
        member_join()
    ))
    .bind(member_id)
    .bind(from)
    .bind(to)
    .bind(account_id)
    .bind(category_id)
    .fetch_all(db)
    .await
}

/// findRecentCategorizedByMemberId: the newest labelled, categorized rows (AI few-shot examples).
pub async fn recent_categorized<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    limit: i64,
) -> sqlx::Result<Vec<Transaction>> {
    crate::db::query_as::<Transaction>(&format!(
        "SELECT {TX} {} WHERE a.member_id = $1 AND t.category_id IS NOT NULL AND t.merchant_label IS NOT NULL \
         AND t.budget_neutral = false ORDER BY t.date DESC, t.id DESC LIMIT $2",
        member_join()
    ))
    .bind(member_id)
    .bind(limit)
    .fetch_all(db)
    .await
}

/// Writes what the categorization pipeline sets on a stored row.
pub async fn save_categorization<'e>(db: impl DbExec<'e>, tx: &Transaction) -> sqlx::Result<()> {
    crate::db::query(
        "UPDATE \"transaction\" SET merchant_label = $2, merchant_brand_id = $3, category_id = $4, \
         category_manual = $5, ai_suggested_category_id = $6, ai_confidence = $7 WHERE id = $1",
    )
    .bind(tx.id)
    .bind(&tx.merchant_label)
    .bind(tx.merchant_brand_id)
    .bind(tx.category_id)
    .bind(tx.category_manual)
    .bind(tx.ai_suggested_category_id)
    .bind(tx.ai_confidence)
    .execute(db)
    .await?;
    Ok(())
}

/// Categories by id, whoever owns them (a transaction's category is read through its id).
pub async fn categories_by_ids<'e>(
    db: impl DbExec<'e>,
    ids: &[i64],
) -> sqlx::Result<HashMap<i64, Category>> {
    let rows: Vec<Category> = crate::db::query_as::<Category>(&format!(
        "SELECT {CATEGORY} FROM category c WHERE c.id = ANY($1)"
    ))
    .bind(ids)
    .fetch_all(db)
    .await?;
    Ok(rows.into_iter().map(|c| (c.id, c)).collect())
}

/// findAllByMemberIdAndParentIdOrderBySortOrderAscIdAsc (archived children included).
pub async fn children<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    parent_id: i64,
) -> sqlx::Result<Vec<Category>> {
    crate::db::query_as::<Category>(&format!(
        "SELECT {CATEGORY} FROM category c WHERE c.member_id = $1 AND c.parent_id = $2 ORDER BY c.sort_order, c.id"
    ))
    .bind(member_id)
    .bind(parent_id)
    .fetch_all(db)
    .await
}

pub use categorize::category_of_member;

/// The accounts and categories a list of rows points at.
pub struct Refs {
    pub accounts: HashMap<i64, Account>,
    pub categories: HashMap<i64, Category>,
}

impl Refs {
    pub async fn load(db: &Db, rows: &[Transaction]) -> sqlx::Result<Self> {
        let mut account_ids: Vec<i64> = rows.iter().map(|t| t.account_id).collect();
        account_ids.sort_unstable();
        account_ids.dedup();
        let mut category_ids: Vec<i64> = rows.iter().filter_map(|t| t.category_id).collect();
        category_ids.sort_unstable();
        category_ids.dedup();
        let accounts = accounts::find_by_ids(db, &account_ids)
            .await?
            .into_iter()
            .map(|a| (a.id, a))
            .collect();
        let mut categories = categories_by_ids(db, &category_ids).await?;
        let parent_ids: Vec<i64> = categories
            .values()
            .filter_map(|c| c.parent_id)
            .filter(|id| !categories.contains_key(id))
            .collect();
        if !parent_ids.is_empty() {
            categories.extend(categories_by_ids(db, &parent_ids).await?);
        }
        Ok(Self {
            accounts,
            categories,
        })
    }

    pub fn account(&self, tx: &Transaction) -> Option<&Account> {
        self.accounts.get(&tx.account_id)
    }

    pub fn category(&self, tx: &Transaction) -> Option<&Category> {
        tx.category_id.and_then(|id| self.categories.get(&id))
    }

    pub fn by_id(&self, id: i64) -> Option<&Category> {
        self.categories.get(&id)
    }

    /// TransactionResponse.from, without a peer-loan link. Returns None if the row's account was
    /// soft-deleted after the transactions were loaded.
    pub fn response(&self, tx: &Transaction) -> Option<TransactionResponse> {
        let account = self.account(tx)?;
        Some(TransactionResponse::from_row(
            tx,
            &account.name,
            self.category(tx).map(|c| c.name.clone()),
            None,
        ))
    }
}

/// The member's rows with the given ids (findAllByIdInAndAccountMemberId).
pub async fn member_rows_by_ids<'e>(
    db: impl DbExec<'e>,
    ids: &[i64],
    member_id: i64,
) -> sqlx::Result<Vec<Transaction>> {
    crate::db::query_as::<Transaction>(&format!(
        "SELECT {TX} FROM \"transaction\" t WHERE t.id = ANY($1) \
         AND EXISTS (SELECT 1 FROM account a WHERE a.id = t.account_id AND a.member_id = $2 AND a.deleted_at IS NULL) \
         ORDER BY t.id"
    ))
    .bind(ids)
    .bind(member_id)
    .fetch_all(db)
    .await
}

/// The value of a NUMERIC(p, scale) column as JDBC reads it: sqlx decodes a zero without its
/// scale (0.00 comes back as 0).
pub fn column_scale(value: Decimal, scale: u32) -> Decimal {
    let mut v = value;
    if v.is_zero() {
        v.rescale(scale);
    }
    v
}
