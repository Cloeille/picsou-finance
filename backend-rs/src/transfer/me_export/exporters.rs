//! The sixteen EntityExporters, in the order Spring injects them (class name order): each a
//! JSON section of data.json and one CSV entry, read with the scope of the Java repository call.

use rust_decimal::Decimal;
use sqlx::postgres::PgRow;
use sqlx::{PgConnection, Row};

use super::Exporting;
use super::json::Cell;

#[derive(Debug, Clone, Copy)]
pub enum Kind {
    Int,
    Text,
    Bool,
    Dec,
    Date,
    Instant,
}

/// Which id binds $1, and whether the query runs once per live account of the member.
#[derive(Debug, Clone, Copy)]
pub enum Scope {
    Member,
    User,
    PerAccount,
}

/// A flat exporter: its columns are both the CSV header and the JSON field names.
pub struct Table {
    pub name: &'static str,
    pub columns: &'static [(&'static str, Kind)],
    pub scope: Scope,
    pub sql: &'static str,
}

pub enum Exporter {
    Table(&'static Table),
    Goals,
    Profile,
}

impl Exporter {
    pub fn name(&self) -> &'static str {
        match self {
            Self::Table(t) => t.name,
            Self::Goals => "goals",
            Self::Profile => "profile",
        }
    }

    pub fn csv_header(&self) -> Vec<&'static str> {
        match self {
            Self::Table(t) => t.columns.iter().map(|(n, _)| *n).collect(),
            Self::Goals => GOALS_CSV.to_vec(),
            Self::Profile => PROFILE_CSV.to_vec(),
        }
    }

    /// BalanceSnapshotsExporter is opt-in.
    pub fn enabled(&self, include_balance_snapshots: bool) -> bool {
        !matches!(self, Self::Table(t) if t.name == BALANCE_SNAPSHOTS.name)
            || include_balance_snapshots
    }
}

use Kind::{Bool, Date, Dec, Instant, Int, Text};

const ACCOUNT_OWNERSHIP: Table = Table {
    name: "account_ownership",
    columns: &[
        ("id", Int),
        ("account_id", Int),
        ("member_id", Int),
        ("share_percent", Dec),
        ("created_at", Instant),
        ("updated_at", Instant),
    ],
    scope: Scope::Member,
    sql: "SELECT id, account_id, member_id, share_percent::text, created_at, updated_at \
          FROM account_ownership WHERE member_id = $1 ORDER BY id",
};

const ACCOUNTS: Table = Table {
    name: "accounts",
    columns: &[
        ("id", Int),
        ("name", Text),
        ("type", Text),
        ("provider", Text),
        ("currency", Text),
        ("current_balance", Dec),
        ("cash_balance", Dec),
        ("ticker", Text),
        ("is_manual", Bool),
        ("color", Text),
        ("external_account_id", Text),
        ("last_synced_at", Instant),
        ("created_at", Instant),
        ("updated_at", Instant),
    ],
    scope: Scope::Member,
    sql: "SELECT id, name, type::text, provider, currency, current_balance::text, cash_balance::text, ticker, \
          is_manual, color, external_account_id, last_synced_at, created_at, updated_at \
          FROM account WHERE member_id = $1 AND deleted_at IS NULL ORDER BY created_at, id",
};

const BALANCE_SNAPSHOTS: Table = Table {
    name: "balance_snapshots",
    columns: &[
        ("id", Int),
        ("account_id", Int),
        ("date", Date),
        ("balance", Dec),
        ("invested_amount", Dec),
        ("created_at", Instant),
    ],
    scope: Scope::PerAccount,
    sql: "SELECT id, account_id, date, balance::text, invested_amount::text, created_at \
          FROM balance_snapshot WHERE account_id = $1 ORDER BY date, id",
};

const BANK_CONNECTIONS: Table = Table {
    name: "bank_connections",
    columns: &[
        ("id", Int),
        ("requisition_id", Text),
        ("institution_id", Text),
        ("institution_name", Text),
        ("status", Text),
        ("last_synced_at", Instant),
        ("created_at", Instant),
        ("updated_at", Instant),
    ],
    scope: Scope::Member,
    sql: "SELECT id, requisition_id, institution_id, institution_name, status::text, last_synced_at, created_at, updated_at \
          FROM requisition WHERE member_id = $1 ORDER BY id",
};

const DEBTS: Table = Table {
    name: "debts",
    columns: &[
        ("id", Int),
        ("account_id", Int),
        ("linked_account_id", Int),
        ("borrowed_amount", Dec),
        ("interest_rate", Dec),
        ("monthly_payment", Dec),
        ("lender_name", Text),
        ("start_date", Date),
        ("end_date", Date),
        ("insurance_monthly", Dec),
        ("file_fees", Dec),
        ("created_at", Instant),
        ("updated_at", Instant),
    ],
    scope: Scope::Member,
    sql: "SELECT id, account_id, linked_account_id, borrowed_amount::text, interest_rate::text, monthly_payment::text, \
          lender_name, start_date, end_date, insurance_monthly::text, file_fees::text, created_at, updated_at \
          FROM debt WHERE member_id = $1 ORDER BY id",
};

const HOLDINGS: Table = Table {
    name: "holdings",
    columns: &[
        ("id", Int),
        ("account_id", Int),
        ("ticker", Text),
        ("name", Text),
        ("quantity", Dec),
        ("average_buy_in", Dec),
        ("current_price", Dec),
        ("quote_currency", Text),
        ("provider_value_eur", Dec),
        ("provider_pnl_eur", Dec),
        ("last_synced_at", Instant),
        ("created_at", Instant),
        ("updated_at", Instant),
    ],
    scope: Scope::PerAccount,
    sql: "SELECT id, account_id, ticker, name, quantity::text, average_buy_in::text, current_price::text, quote_currency, \
          provider_value_eur::text, provider_pnl_eur::text, last_synced_at, created_at, updated_at \
          FROM account_holding WHERE account_id = $1 ORDER BY id",
};

const LINKED_IDENTITIES: Table = Table {
    name: "linked_identities",
    columns: &[
        ("id", Int),
        ("issuer", Text),
        ("subject", Text),
        ("email", Text),
        ("display_name", Text),
        ("created_at", Instant),
        ("last_login_at", Instant),
    ],
    scope: Scope::User,
    sql: "SELECT id, issuer, subject, email, display_name, created_at, last_login_at \
          FROM user_identity WHERE user_id = $1 ORDER BY created_at, id",
};

const PEER_COUNTERPARTIES: Table = Table {
    name: "peer_counterparties",
    columns: &[
        ("id", Int),
        ("member_id", Int),
        ("name", Text),
        ("note", Text),
        ("created_at", Instant),
        ("updated_at", Instant),
    ],
    scope: Scope::Member,
    sql: "SELECT id, member_id, name, note, created_at, updated_at \
          FROM peer_counterparty WHERE member_id = $1 ORDER BY name, id",
};

// The derived queries walk account.member, and Hibernate applies Account's
// @SQLRestriction to that join: a soft-deleted account's loan is left out.
const PEER_LOAN_REPAYMENTS: Table = Table {
    name: "peer_loan_repayments",
    columns: &[
        ("id", Int),
        ("account_id", Int),
        ("paid_on", Date),
        ("amount", Dec),
        ("kind", Text),
        ("note", Text),
        ("cash_transaction_id", Int),
        ("cash_leg_owned", Bool),
        ("created_at", Instant),
        ("updated_at", Instant),
    ],
    scope: Scope::Member,
    sql: "SELECT r.id, r.account_id, r.paid_on, r.amount::text, r.kind, r.note, r.cash_transaction_id, r.cash_leg_owned, \
          r.created_at, r.updated_at FROM peer_loan_repayment r \
          JOIN account a ON a.id = r.account_id AND a.deleted_at IS NULL \
          WHERE a.member_id = $1 ORDER BY r.paid_on, r.id",
};

const PEER_LOAN_TERMS: Table = Table {
    name: "peer_loan_terms",
    columns: &[
        ("id", Int),
        ("account_id", Int),
        ("counterparty_id", Int),
        ("principal", Dec),
        ("lent_at", Date),
        ("expected_back", Date),
        ("annual_rate", Dec),
        ("rate_period", Text),
        ("planned_monthly_amount", Dec),
        ("planned_months", Int),
        ("linked_account_id", Int),
        ("lending_transaction_id", Int),
        ("note", Text),
        ("closed_at", Date),
        ("created_at", Instant),
        ("updated_at", Instant),
    ],
    scope: Scope::Member,
    sql: "SELECT t.id, t.account_id, t.counterparty_id, t.principal::text, t.lent_at, t.expected_back, t.annual_rate::text, \
          t.rate_period, t.planned_monthly_amount::text, t.planned_months::bigint, t.linked_account_id, \
          t.lending_transaction_id, t.note, t.closed_at, t.created_at, t.updated_at FROM peer_loan_terms t \
          JOIN account a ON a.id = t.account_id AND a.deleted_at IS NULL \
          WHERE a.member_id = $1 ORDER BY t.lent_at, t.id",
};

const PROPERTY_VALUATIONS: Table = Table {
    name: "property_valuations",
    columns: &[
        ("id", Int),
        ("account_id", Int),
        ("valued_at", Date),
        ("estimated_value", Dec),
        ("low_value", Dec),
        ("high_value", Dec),
        ("price_per_sqm", Dec),
        ("provider", Text),
        ("confidence", Text),
        ("sample_size", Int),
        ("source_year", Int),
        ("method_detail", Text),
        ("created_at", Instant),
        ("updated_at", Instant),
    ],
    scope: Scope::Member,
    sql: "SELECT id, account_id, valued_at, estimated_value::text, low_value::text, high_value::text, price_per_sqm::text, \
          provider, confidence, sample_size::bigint, source_year::bigint, method_detail, created_at, updated_at \
          FROM property_valuation WHERE member_id = $1 ORDER BY id",
};

const SHARED_RESOURCES: Table = Table {
    name: "shared_resources",
    columns: &[
        ("id", Int),
        ("resource_type", Text),
        ("resource_id", Int),
        ("created_at", Instant),
    ],
    scope: Scope::Member,
    sql: "SELECT id, resource_type, resource_id, created_at FROM shared_resource \
          WHERE owner_member_id = $1 AND resource_type IN ('ACCOUNT', 'GOAL', 'DEBT', 'WALLET') \
          ORDER BY array_position(ARRAY['ACCOUNT', 'GOAL', 'DEBT', 'WALLET']::varchar[], resource_type), id",
};

const TRANSACTIONS: Table = Table {
    name: "transactions",
    columns: &[
        ("id", Int),
        ("account_id", Int),
        ("date", Date),
        ("description", Text),
        ("amount", Dec),
        ("type", Text),
        ("category", Text),
        ("native_currency", Text),
        ("is_manual", Bool),
        ("tx_type", Text),
        ("ticker", Text),
        ("quantity", Dec),
        ("price_per_unit", Dec),
        ("fees", Dec),
        ("created_at", Instant),
    ],
    scope: Scope::PerAccount,
    sql: "SELECT id, account_id, date, description, amount::text, type, category, native_currency, is_manual, tx_type, \
          ticker, quantity::text, price_per_unit::text, fees::text, created_at \
          FROM \"transaction\" WHERE account_id = $1 ORDER BY date DESC, id DESC",
};

const WALLET_ADDRESSES: Table = Table {
    name: "wallet_addresses",
    columns: &[
        ("id", Int),
        ("chain", Text),
        ("address", Text),
        ("label", Text),
        ("last_synced_at", Instant),
        ("created_at", Instant),
        ("updated_at", Instant),
    ],
    scope: Scope::Member,
    sql: "SELECT id, chain, address, label, last_synced_at, created_at, updated_at \
          FROM wallet_address WHERE member_id = $1 ORDER BY id",
};

pub const EXPORTERS: [Exporter; 16] = [
    Exporter::Table(&ACCOUNT_OWNERSHIP),
    Exporter::Table(&ACCOUNTS),
    Exporter::Table(&BALANCE_SNAPSHOTS),
    Exporter::Table(&BANK_CONNECTIONS),
    Exporter::Table(&DEBTS),
    Exporter::Goals,
    Exporter::Table(&HOLDINGS),
    Exporter::Table(&LINKED_IDENTITIES),
    Exporter::Table(&PEER_COUNTERPARTIES),
    Exporter::Table(&PEER_LOAN_REPAYMENTS),
    Exporter::Table(&PEER_LOAN_TERMS),
    Exporter::Profile,
    Exporter::Table(&PROPERTY_VALUATIONS),
    Exporter::Table(&SHARED_RESOURCES),
    Exporter::Table(&TRANSACTIONS),
    Exporter::Table(&WALLET_ADDRESSES),
];

fn decode(row: &PgRow, columns: &[(&str, Kind)]) -> Result<Vec<Cell>, sqlx::Error> {
    columns
        .iter()
        .enumerate()
        .map(|(i, (_, kind))| {
            Ok(match kind {
                Int => row.try_get::<Option<i64>, _>(i)?.map(Cell::Int),
                Text => row.try_get::<Option<String>, _>(i)?.map(Cell::Text),
                Bool => row.try_get::<Option<bool>, _>(i)?.map(Cell::Bool),
                // Read as text: sqlx turns a numeric zero into a Decimal of scale 0, and the
                // export writes the column's scale (0E-8, 0.00000000) as Java does.
                Dec => match row.try_get::<Option<String>, _>(i)? {
                    Some(s) => Some(Cell::Dec(
                        Decimal::from_str_exact(&s)
                            .map_err(|e| sqlx::Error::Decode(Box::new(e)))?,
                    )),
                    None => None,
                },
                Date => row
                    .try_get::<Option<chrono::NaiveDate>, _>(i)?
                    .map(Cell::Date),
                Instant => row
                    .try_get::<Option<chrono::DateTime<chrono::Utc>>, _>(i)?
                    .map(Cell::Instant),
            }
            .unwrap_or(Cell::Null))
        })
        .collect()
}

async fn query(
    conn: &mut PgConnection,
    sql: &str,
    columns: &[(&str, Kind)],
    key: i64,
) -> Result<Vec<Vec<Cell>>, sqlx::Error> {
    let rows = sqlx::query(sql).bind(key).fetch_all(conn).await?;
    rows.iter().map(|r| decode(r, columns)).collect()
}

/// AccountRepository.findAllByMemberIdOrderByCreatedAtAsc: the live accounts.
pub async fn account_ids(conn: &mut PgConnection, member_id: i64) -> Result<Vec<i64>, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT id FROM account WHERE member_id = $1 AND deleted_at IS NULL ORDER BY created_at, id",
    )
    .bind(member_id)
    .fetch_all(conn)
    .await
}

/// The bound ids of a table's query: one query for the member or user, one per account.
pub async fn keys(
    conn: &mut PgConnection,
    table: &Table,
    who: &Exporting,
) -> Result<Vec<i64>, sqlx::Error> {
    Ok(match table.scope {
        Scope::Member => vec![who.member_id],
        Scope::User => vec![who.user_id],
        Scope::PerAccount => account_ids(conn, who.member_id).await?,
    })
}

pub async fn rows(
    conn: &mut PgConnection,
    table: &Table,
    key: i64,
) -> Result<Vec<Vec<Cell>>, sqlx::Error> {
    query(conn, table.sql, table.columns, key).await
}

pub const PROFILE_CSV: [&str; 11] = [
    "user_id",
    "username",
    "role",
    "activated",
    "acknowledged_warning",
    "created_at",
    "updated_at",
    "member_id",
    "display_name",
    "avatar_color",
    "managed",
];

const PROFILE_COLUMNS: [(&str, Kind); 11] = [
    ("user_id", Int),
    ("username", Text),
    ("role", Text),
    ("activated", Bool),
    ("acknowledged_warning", Bool),
    ("created_at", Instant),
    ("updated_at", Instant),
    ("id", Int),
    ("display_name", Text),
    ("avatar_color", Text),
    ("managed", Bool),
];

/// The user (no password hash, no activation token) and their own member row.
pub async fn profile(conn: &mut PgConnection, who: &Exporting) -> Result<Vec<Cell>, sqlx::Error> {
    let mut rows = query(
        conn,
        "SELECT u.id, u.username, u.role, u.is_activated, u.acknowledged_warning, u.created_at, u.updated_at, \
         m.id, m.display_name, m.avatar_color, m.is_managed \
         FROM app_user u JOIN family_member m ON m.id = u.member_id WHERE u.id = $1",
        &PROFILE_COLUMNS,
        who.user_id,
    )
    .await?;
    rows.pop().ok_or(sqlx::Error::RowNotFound)
}

pub type Named<'a> = Vec<(&'static str, &'a Cell)>;

/// The profile cells split into the user's fields and the nested member object.
pub fn profile_parts(cells: &[Cell]) -> (Named<'_>, Named<'_>) {
    let named: Vec<(&'static str, &Cell)> =
        PROFILE_COLUMNS.iter().map(|(n, _)| *n).zip(cells).collect();
    let (user, member) = named.split_at(7);
    (user.to_vec(), member.to_vec())
}

pub const GOALS_CSV: [&str; 12] = [
    "id",
    "name",
    "type",
    "target_amount",
    "deadline",
    "monthly_amount",
    "expected_return",
    "start_date",
    "end_date",
    "account_ids",
    "created_at",
    "updated_at",
];

pub const GOAL_COLUMNS: [(&str, Kind); 11] = [
    ("id", Int),
    ("name", Text),
    ("type", Text),
    ("target_amount", Dec),
    ("deadline", Date),
    ("monthly_amount", Dec),
    ("expected_return", Dec),
    ("start_date", Date),
    ("end_date", Date),
    ("created_at", Instant),
    ("updated_at", Instant),
];

pub async fn goals(conn: &mut PgConnection, member_id: i64) -> Result<Vec<Vec<Cell>>, sqlx::Error> {
    query(
        conn,
        "SELECT id, name, type, target_amount::text, deadline, monthly_amount::text, expected_return::text, \
         start_date, end_date, created_at, updated_at FROM goal WHERE member_id = $1 ORDER BY created_at, id",
        &GOAL_COLUMNS,
        member_id,
    )
    .await
}

/// Goal.accounts: the ManyToMany loads only live accounts.
pub async fn goal_account_ids(
    conn: &mut PgConnection,
    goal_id: i64,
) -> Result<Vec<i64>, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT ga.account_id FROM goal_account ga \
         JOIN account a ON a.id = ga.account_id AND a.deleted_at IS NULL \
         WHERE ga.goal_id = $1 ORDER BY ga.account_id",
    )
    .bind(goal_id)
    .fetch_all(conn)
    .await
}

/// A nested list of a goal in data.json.
pub struct GoalChild {
    pub field: &'static str,
    pub columns: &'static [(&'static str, Kind)],
    pub sql: &'static str,
}

pub const GOAL_CHILDREN: [GoalChild; 4] = [
    GoalChild {
        field: "contributors",
        columns: &[("member_id", Int)],
        sql: "SELECT member_id FROM goal_contributor WHERE goal_id = $1 ORDER BY member_id",
    },
    GoalChild {
        field: "manual_contributions",
        columns: &[
            ("id", Int),
            ("year_month", Text),
            ("amount", Dec),
            ("member_id", Int),
        ],
        sql: "SELECT id, year_month, amount::text, member_id FROM goal_manual_contribution WHERE goal_id = $1 ORDER BY id",
    },
    GoalChild {
        field: "allocations",
        columns: &[("id", Int), ("ticker", Text), ("monthly_amount", Dec)],
        sql: "SELECT id, ticker, monthly_amount::text FROM goal_allocation WHERE goal_id = $1 ORDER BY id",
    },
    GoalChild {
        field: "month_overrides",
        columns: &[("id", Int), ("year_month", Text), ("amount", Dec)],
        sql: "SELECT id, year_month, amount::text FROM goal_month_override WHERE goal_id = $1 ORDER BY id",
    },
];

pub async fn goal_children(
    conn: &mut PgConnection,
    child: &GoalChild,
    goal_id: i64,
) -> Result<Vec<Vec<Cell>>, sqlx::Error> {
    query(conn, child.sql, child.columns, goal_id).await
}
