//! The ledger shared by every domain track: accounts, holdings, transactions, balance
//! snapshots, FX reads, ownership shares and the ingestion primitives (track L).
//! It serves no endpoint; see README.md beside this file for the API each track uses.

pub mod access;
pub mod accounts;
pub mod bank_import;
pub mod categorize;
pub mod decimal;
pub mod display_prefs;
pub mod holding_compute;
pub mod holding_dedup;
pub mod holdings;
pub mod instrument;
pub mod liabilities;
pub mod loans;
pub mod locks;
pub mod manual_ledger;
pub mod manual_transactions;
pub mod members;
pub mod merchants;
pub mod model;
pub mod peer_loans;
pub mod prices;
pub mod providers;
pub mod sidecar;
pub mod sidecar_import;
pub mod snapshots;
pub mod sync_values;
pub mod transactions;
pub mod transfers;
pub mod valuation;

use std::sync::Arc;

use sqlx::PgPool;
use tokio::sync::OnceCell;

use self::locks::{ConnectionLocks, MemberRowLock};
use self::merchants::KnowledgeBase;
use self::prices::{PriceRead, RecordedPrices};
use self::transfers::{DividendClassifier, NoDividendClassifier};
use crate::routes::Routes;

pub fn register(_routes: &mut Routes) {}

/// The ledger's plug-in points, held by AppState as state.ledger. Each starts with the
/// behavior Java has when the provider is absent; the owning track swaps in its own.
pub struct Ledger {
    /// Track T4 replaces it with the provider-backed price service.
    pub prices: Arc<dyn PriceRead>,
    /// Track T4 replaces it with DividendReceiptService.classifyRecent.
    pub dividends: Arc<dyn DividendClassifier>,
    /// Tracks T5/T6 replace it if their syncs lock more than the member row.
    pub connection_locks: Arc<dyn ConnectionLocks>,
    knowledge_base: OnceCell<Arc<KnowledgeBase>>,
}

impl Ledger {
    pub fn new(db: PgPool) -> Self {
        Self {
            prices: Arc::new(RecordedPrices::new(db)),
            dividends: Arc::new(NoDividendClassifier),
            connection_locks: Arc::new(MemberRowLock),
            knowledge_base: OnceCell::new(),
        }
    }

    /// The merchant knowledge base, loaded once (MerchantKnowledgeBase's @PostConstruct).
    pub async fn knowledge_base(&self, db: &PgPool) -> sqlx::Result<Arc<KnowledgeBase>> {
        self.knowledge_base
            .get_or_try_init(|| async { KnowledgeBase::load(db).await.map(Arc::new) })
            .await
            .cloned()
    }
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeSet;
    use std::str::FromStr;

    use chrono::{DateTime, NaiveDate, Utc};
    use rust_decimal::Decimal;

    use super::accounts::{self, NewAccount};
    use super::holdings;
    use super::model::{AccountType, HoldingDraft, NewTransaction};
    use super::prices;
    use super::snapshots::{self, NewSnapshot};
    use super::transactions;
    use crate::db::Db;
    use crate::db::sqlite::{SqliteDb, migrate};

    fn dec(s: &str) -> Decimal {
        Decimal::from_str(s).expect("valid decimal")
    }

    fn open_sqlite_db() -> Db {
        let sq = SqliteDb::open_in_memory().expect("open sqlite");
        sq.with_conn(|conn| {
            migrate::migrate(conn).expect("migrate sqlite");
            Ok(())
        })
        .expect("with_conn");
        Db::Sqlite(sq)
    }

    #[tokio::test]
    async fn sqlite_parity_ctes_sums_holdings_and_last_known_prices() {
        let db = open_sqlite_db();
        let now: DateTime<Utc> = "2026-06-15T12:00:00Z".parse().expect("ts");
        let today = NaiveDate::from_ymd_opt(2026, 6, 15).expect("date");
        let yesterday = NaiveDate::from_ymd_opt(2026, 6, 14).expect("date");

        // Seed a family member.
        let member_id: i64 = crate::db::query_scalar(
            "INSERT INTO family_member (display_name, avatar_color, is_managed) VALUES ($1, $2, false) RETURNING id",
        )
        .bind("Alice")
        .bind("#6366f1")
        .fetch_one(&db)
        .await
        .expect("insert member");

        // 1. accounts::insert & accounts::update (rewritten CTEs).
        let mut acc = accounts::insert(
            &db,
            &NewAccount::new(member_id, "Checking", AccountType::Checking),
            now,
        )
        .await
        .expect("insert account");
        acc.name = "Checking Updated".to_owned();
        let acc = accounts::update(&db, &acc, now)
            .await
            .expect("update account");
        assert_eq!(acc.name, "Checking Updated");

        // 2. transactions::insert, update (rewritten CTEs) + 3 Decimal SUMs (exact 0.10 + 0.20 = 0.30).
        let t1 = transactions::insert(
            &db,
            &NewTransaction::new(acc.id, yesterday, "Coffee", dec("0.10")),
            now - chrono::Duration::hours(2),
        )
        .await
        .expect("insert tx 1");
        let mut t2 = transactions::insert(
            &db,
            &NewTransaction::new(acc.id, today, "Tea", dec("0.15")),
            now,
        )
        .await
        .expect("insert tx 2");
        t2.amount = dec("0.20");
        let t2 = transactions::update(&db, &t2).await.expect("update tx 2");
        assert_eq!(t2.amount, dec("0.20"));
        assert_eq!(
            transactions::sum_amount(&db, acc.id).await.expect("sum"),
            dec("0.30")
        );
        assert_eq!(
            transactions::sum_amount_after(&db, acc.id, yesterday)
                .await
                .expect("sum_after"),
            dec("0.20")
        );
        assert_eq!(
            transactions::sum_amount_after_statement(
                &db,
                acc.id,
                yesterday,
                t1.created_at + chrono::Duration::seconds(1)
            )
            .await
            .expect("sum_after_statement"),
            dec("0.20")
        );

        // 3. holdings::insert, update (rewritten CTEs), find_positive_by_accounts (dec_cmp),
        //    and find_by_account_by_price_desc (exact Decimal DESC NULLS FIRST, id ASC).
        let mut h1 = holdings::insert(
            &db,
            acc.id,
            &HoldingDraft {
                ticker: "AAA".to_owned(),
                quantity: dec("2.5"),
                current_price: Some(dec("9.99")),
                ..HoldingDraft::default()
            },
            now,
        )
        .await
        .expect("insert h1");
        let h2 = holdings::insert(
            &db,
            acc.id,
            &HoldingDraft {
                ticker: "BBB".to_owned(),
                quantity: dec("0"),
                current_price: None,
                ..HoldingDraft::default()
            },
            now,
        )
        .await
        .expect("insert h2");
        let h3 = holdings::insert(
            &db,
            acc.id,
            &HoldingDraft {
                ticker: "CCC".to_owned(),
                quantity: dec("10"),
                current_price: Some(dec("100.5")),
                ..HoldingDraft::default()
            },
            now,
        )
        .await
        .expect("insert h3");
        h1.name = Some("Alpha".to_owned());
        let h1 = holdings::update(&db, h1.id, &HoldingDraft::from(&h1), now)
            .await
            .expect("update h1");
        assert_eq!(h1.name.as_deref(), Some("Alpha"));

        let positive = holdings::find_positive_by_accounts(&db, &[acc.id])
            .await
            .expect("find_positive");
        let pos_ids: Vec<i64> = positive.iter().map(|h| h.id).collect();
        assert_eq!(pos_ids, vec![h1.id, h3.id]);

        let by_price = holdings::find_by_account_by_price_desc(&db, acc.id)
            .await
            .expect("by_price_desc");
        let ordered_ids: Vec<i64> = by_price.iter().map(|h| h.id).collect();
        assert_eq!(ordered_ids, vec![h2.id, h3.id, h1.id]);

        // 4. snapshots::insert, update_amounts, set_native_balance (3 rewritten CTEs).
        let snap = snapshots::insert(
            &db,
            &NewSnapshot::derived(acc.id, today, dec("100"), dec("90")),
            now,
        )
        .await
        .expect("insert snap");
        let snap = snapshots::update_amounts(&db, snap.id, dec("110"), dec("95"))
            .await
            .expect("update snap");
        assert_eq!(snap.balance, dec("110"));
        let snap = snapshots::set_native_balance(&db, snap.id, dec("120"), now)
            .await
            .expect("set_native_balance");
        assert_eq!(snap.native_balance, Some(dec("120")));

        // 5. prices::last_known_prices (ROW_NUMBER window rewrite).
        for (ticker, d, p) in [
            ("VWCE", yesterday, dec("110.50")),
            ("VWCE", today, dec("112.75")),
            ("BTC", yesterday, dec("60000")),
        ] {
            crate::db::query(
                "INSERT INTO price_snapshot (ticker, date, price_eur, created_at) VALUES ($1, $2, $3, $4)",
            )
            .bind(ticker)
            .bind(d)
            .bind(p)
            .bind(now)
            .execute(&db)
            .await
            .expect("insert price");
        }
        let wanted = BTreeSet::from(["VWCE".to_owned(), "BTC".to_owned()]);
        let latest = prices::last_known_prices(&db, &wanted, today)
            .await
            .expect("last_known_prices");
        assert_eq!(latest.len(), 2);
        assert_eq!(latest["VWCE"].price_eur, dec("112.75"));
        assert_eq!(latest["BTC"].price_eur, dec("60000"));
    }
}
