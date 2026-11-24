//! ActualBudgetFileParser and ActualBudgetDatabaseReader: an uploaded Actual Budget export (the
//! budget .zip holding db.sqlite, or a bare db.sqlite, recognised by signature) read into
//! ParsedBudget, already resolved against Actual's own rules (tombstones, splits, merged
//! categories and payees, transfers, starting balances).
//!
//! The upload is user-supplied: archive entries and inflated bytes are capped while inflating,
//! an entry name that could escape a directory rejects the archive, the database is copied
//! into a private temporary directory deleted whatever the outcome, opened read-only, and every
//! read is bounded before it reaches the heap.

use std::collections::{HashMap, HashSet};
use std::io::{Cursor, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::mpsc;
use std::time::Duration;

use chrono::NaiveDate;
use rusqlite::types::ValueRef;
use rusqlite::{Connection, ErrorCode, OpenFlags, Row};
use rust_decimal::Decimal;
use serde::Serialize;

use crate::ledger::sync_values::{java_is_blank, java_trim};

const DATABASE_ENTRY: &str = "db.sqlite";
const ZIP_MAGIC: &[u8] = b"PK\x03\x04";
const SQLITE_MAGIC: &[u8] = b"SQLite format 3\0";
const DEFAULT_MAX_ENTRIES: usize = 32;
const DEFAULT_MAX_UNCOMPRESSED_BYTES: u64 = 256 * 1024 * 1024;

const MAX_ACCOUNTS: usize = 100;
const MAX_CATEGORIES: usize = 500;
const MAX_TRANSACTIONS: usize = 200_000;
const MAX_TEXT: usize = 255;
const MAX_ACCOUNT_ID: usize = 80;
const MAX_CATEGORY_ID: usize = 40;
const MAX_PAYEE_ID: usize = 80;
const MAX_TRANSACTION_ID: usize = 200;
const MAX_NUMBER_TEXT: usize = 32;
/// No Actual value comes near this; a larger one fails inside SQLite before it is copied out.
const MAX_VALUE_BYTES: i32 = 1024 * 1024;
/// Stored rows per table, deleted ones included: an order of magnitude over a heavy
/// decade-long budget, with room for tombstones and split parents above the live-row cap.
const MAX_ROWS: [(&str, i64); 8] = [
    ("accounts", 10_000),
    ("category_groups", 10_000),
    ("categories", 10_000),
    ("category_mapping", 50_000),
    ("payees", 50_000),
    ("payee_mapping", 50_000),
    ("transactions", 300_000),
    ("preferences", 10_000),
];
const QUERY_TIMEOUT: Duration = Duration::from_secs(60);
const MAX_AMOUNT_HUNDREDTHS: i64 = 99_999_999_999_999;

const REQUIRED_COLUMNS: [(&str, &[&str]); 5] = [
    (
        "accounts",
        &["id", "name", "offbudget", "closed", "tombstone"],
    ),
    ("category_groups", &["id", "name", "is_income", "tombstone"]),
    (
        "categories",
        &["id", "name", "is_income", "cat_group", "tombstone"],
    ),
    ("payees", &["id", "name", "transfer_acct", "tombstone"]),
    (
        "transactions",
        &[
            "id",
            "acct",
            "category",
            "amount",
            "description",
            "notes",
            "date",
            "isParent",
            "isChild",
            "tombstone",
            "transferred_id",
            "starting_balance_flag",
        ],
    ),
];

/// Tables read only when present; their columns are then required like the others.
const OPTIONAL_COLUMNS: [(&str, &[&str]); 3] = [
    ("category_mapping", &["id", "transferId"]),
    ("payee_mapping", &["id", "targetId"]),
    ("preferences", &["id", "value"]),
];

/// The importable content of an Actual Budget file. currency is the budget's default currency
/// when the file records one (Actual budgets are single-currency; older ones never store it).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedBudget {
    pub currency: Option<String>,
    pub accounts: Vec<SourceAccount>,
    pub categories: Vec<SourceCategory>,
    pub transactions: Vec<SourceTransaction>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SourceAccount {
    pub id: String,
    pub name: String,
    pub off_budget: bool,
    pub closed: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SourceCategory {
    pub id: String,
    pub name: String,
    pub group_id: Option<String>,
    pub group_name: Option<String>,
    pub income: bool,
}

/// How a row moves money, which decides whether it may count as income or spending.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Kind {
    Regular,
    Transfer,
    StartingBalance,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SourceTransaction {
    pub id: String,
    pub account_id: String,
    pub date: NaiveDate,
    pub amount: Decimal,
    pub payee: Option<String>,
    pub notes: Option<String>,
    pub category_id: Option<String>,
    pub kind: Kind,
}

/// A rejection whose message is shown to the user as the 400 detail.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rejected(pub String);

fn bad(message: impl Into<String>) -> Rejected {
    Rejected(message.into())
}

pub struct FileParser {
    max_entries: usize,
    max_uncompressed_bytes: u64,
    temporary_root: PathBuf,
}

impl Default for FileParser {
    fn default() -> Self {
        Self {
            max_entries: DEFAULT_MAX_ENTRIES,
            max_uncompressed_bytes: DEFAULT_MAX_UNCOMPRESSED_BYTES,
            temporary_root: std::env::temp_dir(),
        }
    }
}

/// Deletes the private directory when dropped, whatever the outcome of the parse.
struct TempDir(PathBuf);

impl Drop for TempDir {
    fn drop(&mut self) {
        if let Err(e) = std::fs::remove_dir_all(&self.0) {
            tracing::warn!("Could not delete an Actual Budget temporary copy: {e}");
        }
    }
}

enum Failure {
    Rejected(Rejected),
    Corrupt,
    Io,
}

impl From<Rejected> for Failure {
    fn from(r: Rejected) -> Self {
        Self::Rejected(r)
    }
}

impl From<std::io::Error> for Failure {
    fn from(_: std::io::Error) -> Self {
        Self::Io
    }
}

impl From<zip::result::ZipError> for Failure {
    fn from(e: zip::result::ZipError) -> Self {
        match e {
            zip::result::ZipError::Io(_) => Self::Io,
            _ => Self::Corrupt,
        }
    }
}

impl FileParser {
    #[cfg(test)]
    fn with_limits(
        max_entries: usize,
        max_uncompressed_bytes: u64,
        temporary_root: PathBuf,
    ) -> Self {
        Self {
            max_entries,
            max_uncompressed_bytes,
            temporary_root,
        }
    }

    /// Blocking: inflates, writes a temporary file and reads SQLite.
    pub fn parse(&self, upload: &[u8]) -> Result<ParsedBudget, Rejected> {
        if upload.is_empty() {
            return Err(bad("The file is empty"));
        }
        let outcome = (|| -> Result<ParsedBudget, Failure> {
            let directory = create_private_directory(&self.temporary_root)?;
            let database = directory.0.join("budget.sqlite");
            self.write_database(upload, &database)?;
            Ok(read_database(&database)?)
        })();
        outcome.map_err(|failure| match failure {
            Failure::Rejected(r) => r,
            Failure::Corrupt => bad("The Actual Budget archive is corrupt"),
            Failure::Io => bad("Unable to read the Actual Budget file"),
        })
    }

    fn write_database(&self, upload: &[u8], database: &Path) -> Result<(), Failure> {
        if upload.starts_with(SQLITE_MAGIC) {
            if upload.len() as u64 > self.max_uncompressed_bytes {
                return Err(bad("The Actual Budget database is too large").into());
            }
            std::fs::write(database, upload)?;
            return Ok(());
        }
        if !upload.starts_with(ZIP_MAGIC) {
            return Err(bad(
                "Unsupported file: expected an Actual Budget export (.zip) or its db.sqlite",
            )
            .into());
        }
        self.extract_from_zip(upload, database)?;
        let mut header = [0u8; SQLITE_MAGIC.len()];
        let mut file = std::fs::File::open(database)?;
        let mut filled = 0;
        while filled < header.len() {
            let read = file.read(&mut header[filled..])?;
            if read == 0 {
                break;
            }
            filled += read;
        }
        if filled != header.len() || header != SQLITE_MAGIC {
            return Err(bad("The archive's db.sqlite is not a SQLite database").into());
        }
        Ok(())
    }

    fn extract_from_zip(&self, upload: &[u8], database: &Path) -> Result<(), Failure> {
        let mut archive = zip::ZipArchive::new(Cursor::new(upload))?;
        let mut inflated = 0u64;
        let mut found = false;
        let mut entries = 0usize;
        for index in 0..archive.len() {
            entries += 1;
            if entries > self.max_entries {
                return Err(bad("The Actual Budget archive has too many entries").into());
            }
            let mut entry = archive.by_index(index)?;
            let name = String::from_utf8_lossy(entry.name_raw()).into_owned();
            if is_unsafe(&name) {
                return Err(bad("The Actual Budget archive contains an unsafe path").into());
            }
            if name == DATABASE_ENTRY && !entry.is_dir() {
                if found {
                    return Err(
                        bad("The Actual Budget archive contains several db.sqlite files").into(),
                    );
                }
                found = true;
                let mut out = std::fs::File::create(database)?;
                inflated = self.copy_bounded(&mut entry, &mut out, inflated)?;
            } else {
                inflated = self.copy_bounded(&mut entry, &mut std::io::sink(), inflated)?;
            }
        }
        if entries == 0 {
            return Err(Failure::Corrupt);
        }
        if !found {
            return Err(bad("The archive does not contain an Actual Budget db.sqlite").into());
        }
        Ok(())
    }

    /// Copies one entry while counting every inflated byte of the archive against the cap.
    fn copy_bounded(
        &self,
        input: &mut impl Read,
        output: &mut impl Write,
        already_inflated: u64,
    ) -> Result<u64, Failure> {
        let mut buffer = vec![0u8; 64 * 1024];
        let mut total = already_inflated;
        loop {
            let read = input.read(&mut buffer)?;
            if read == 0 {
                return Ok(total);
            }
            total += read as u64;
            if total > self.max_uncompressed_bytes {
                return Err(bad("The Actual Budget archive is too large once uncompressed").into());
            }
            output.write_all(&buffer[..read])?;
        }
    }
}

fn is_unsafe(name: &str) -> bool {
    name.is_empty()
        || name.starts_with('/')
        || name.contains('\\')
        || name.contains(':')
        || name.contains('\0')
        || name.split('/').any(|segment| segment == "..")
}

fn create_private_directory(root: &Path) -> std::io::Result<TempDir> {
    let path = root.join(format!("picsou-actual-{}", uuid::Uuid::new_v4().simple()));
    let mut builder = std::fs::DirBuilder::new();
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        builder.mode(0o700);
    }
    builder.create(&path)?;
    Ok(TempDir(path))
}

// --- ActualBudgetDatabaseReader ------------------------------------------------------------

enum ReadError {
    Rejected(Rejected),
    Sql(rusqlite::Error),
}

impl From<Rejected> for ReadError {
    fn from(r: Rejected) -> Self {
        Self::Rejected(r)
    }
}

impl From<rusqlite::Error> for ReadError {
    fn from(e: rusqlite::Error) -> Self {
        Self::Sql(e)
    }
}

type ReadResult<T> = Result<T, ReadError>;

struct Schema {
    tables: HashSet<String>,
    has_parent_id: bool,
}

struct AccountRow {
    id: String,
    name: String,
    off_budget: bool,
    closed: bool,
    tombstone: bool,
}

struct GroupRow {
    name: Option<String>,
    tombstone: bool,
}

struct PayeeRow {
    name: Option<String>,
    transfer_account_id: Option<String>,
}

/// A numeric cell as the driver hands it over: an integer, or text (a REAL is cast to text in
/// SQL so no floating point reaches Rust), or anything else (NULL, a blob), never a number.
enum Number {
    Integer(i64),
    Text(String),
    Invalid,
}

struct TransactionRow {
    id: String,
    account_id: Option<String>,
    category_id: Option<String>,
    amount: Number,
    payee_id: Option<String>,
    notes: Option<String>,
    date: Number,
    parent: bool,
    child: bool,
    parent_id: Option<String>,
    tombstone: bool,
    transferred_id: Option<String>,
    starting_balance: bool,
}

fn read_database(database: &Path) -> Result<ParsedBudget, Rejected> {
    let outcome = (|| -> ReadResult<ParsedBudget> {
        let connection = Connection::open_with_flags(
            database,
            OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )?;
        connection.set_limit(
            rusqlite::limits::Limit::SQLITE_LIMIT_LENGTH,
            MAX_VALUE_BYTES,
        );
        connection.execute_batch("PRAGMA query_only = ON; PRAGMA trusted_schema = OFF;")?;
        let interrupt = connection.get_interrupt_handle();
        let (done, finished) = mpsc::channel::<()>();
        let watchdog = std::thread::spawn(move || {
            if finished.recv_timeout(QUERY_TIMEOUT) == Err(mpsc::RecvTimeoutError::Timeout) {
                interrupt.interrupt();
            }
        });
        let result = (|| {
            let schema = require_schema(&connection)?;
            require_row_counts(&connection, &schema)?;
            read_budget(&connection, &schema)
        })();
        drop(done);
        let _ = watchdog.join();
        result
    })();
    outcome.map_err(|e| match e {
        ReadError::Rejected(r) => r,
        ReadError::Sql(rusqlite::Error::SqliteFailure(failure, _))
            if failure.code == ErrorCode::TooBig =>
        {
            bad("The Actual Budget file holds a value larger than 1 MiB")
        }
        ReadError::Sql(_) => bad("The file is not a readable Actual Budget database"),
    })
}

fn require_schema(connection: &Connection) -> ReadResult<Schema> {
    let mut table_types: HashMap<String, String> = HashMap::new();
    {
        let mut statement =
            connection.prepare("SELECT name, type FROM pragma_table_list WHERE schema = 'main'")?;
        let mut rows = statement.query([])?;
        while let Some(row) = rows.next()? {
            if let (Some(name), Some(kind)) = (text_at(row, 0), text_at(row, 1)) {
                table_types.insert(name, kind);
            }
        }
    }
    for (table, _) in REQUIRED_COLUMNS {
        if !table_types.contains_key(table) {
            return Err(bad(format!(
                "Not an Actual Budget database: table '{table}' is missing"
            ))
            .into());
        }
    }
    let read = REQUIRED_COLUMNS.iter().chain(
        OPTIONAL_COLUMNS
            .iter()
            .filter(|(table, _)| table_types.contains_key(*table)),
    );
    let mut has_parent_id = false;
    for (table, required) in read {
        if table_types.get(*table).map(String::as_str) != Some("table") {
            return Err(bad(format!(
                "Unsupported Actual Budget database: '{table}' is not a plain table"
            ))
            .into());
        }
        let columns = columns(connection, table)?;
        for column in *required {
            require_stored_column(&columns, table, column)?;
        }
        if *table == "transactions" && columns.contains_key("parent_id") {
            require_stored_column(&columns, table, "parent_id")?;
            has_parent_id = true;
        }
    }
    Ok(Schema {
        tables: table_types.into_keys().collect(),
        has_parent_id,
    })
}

/// Column name to whether its value is computed (a generated or hidden column).
fn columns(connection: &Connection, table: &str) -> ReadResult<HashMap<String, bool>> {
    let mut statement = connection.prepare("SELECT name, hidden FROM pragma_table_xinfo(?1)")?;
    let mut rows = statement.query([table])?;
    let mut columns = HashMap::new();
    while let Some(row) = rows.next()? {
        if let Some(name) = text_at(row, 0) {
            columns.insert(name, int_at(row, 1) != 0);
        }
    }
    Ok(columns)
}

fn require_stored_column(
    columns: &HashMap<String, bool>,
    table: &str,
    column: &str,
) -> Result<(), Rejected> {
    match columns.get(column) {
        None => Err(bad(format!(
            "Unsupported Actual Budget database: column '{table}.{column}' is missing"
        ))),
        Some(true) => Err(bad(format!(
            "Unsupported Actual Budget database: column '{table}.{column}' is generated"
        ))),
        Some(false) => Ok(()),
    }
}

/// Counted before reading, so an oversized table is refused without loading a row of it.
fn require_row_counts(connection: &Connection, schema: &Schema) -> ReadResult<()> {
    for (table, cap) in MAX_ROWS {
        if !schema.tables.contains(table) {
            continue;
        }
        let count: i64 =
            connection.query_row(&format!("SELECT count(*) FROM {table}"), [], |row| {
                row.get(0)
            })?;
        if count > cap {
            return Err(bad(format!(
                "The Actual Budget file has more than {cap} rows in '{table}'"
            ))
            .into());
        }
    }
    Ok(())
}

fn read_budget(connection: &Connection, schema: &Schema) -> ReadResult<ParsedBudget> {
    let all_accounts = read_accounts(connection)?;
    let accounts: Vec<SourceAccount> = all_accounts
        .iter()
        .filter(|a| !a.tombstone)
        .map(|a| SourceAccount {
            id: a.id.clone(),
            name: a.name.clone(),
            off_budget: a.off_budget,
            closed: a.closed,
        })
        .collect();
    if accounts.is_empty() {
        return Err(bad("The Actual Budget file contains no accounts").into());
    }
    if accounts.len() > MAX_ACCOUNTS {
        return Err(bad(format!(
            "The Actual Budget file has more than {MAX_ACCOUNTS} accounts"
        ))
        .into());
    }
    let categories = read_categories(connection)?;
    let category_mapping = if schema.tables.contains("category_mapping") {
        read_mapping(
            connection,
            "category_mapping",
            "transferId",
            MAX_CATEGORY_ID,
        )?
    } else {
        HashMap::new()
    };
    let payees = read_payees(connection, &all_accounts)?;
    let payee_mapping = if schema.tables.contains("payee_mapping") {
        read_mapping(connection, "payee_mapping", "targetId", MAX_PAYEE_ID)?
    } else {
        HashMap::new()
    };
    let live: HashSet<&str> = accounts.iter().map(|a| a.id.as_str()).collect();
    let transactions = resolve_transactions(
        read_transactions(connection, schema.has_parent_id)?,
        &live,
        &categories,
        &category_mapping,
        &payees,
        &payee_mapping,
    )?;
    let currency = if schema.tables.contains("preferences") {
        read_currency(connection)
    } else {
        None
    };
    Ok(ParsedBudget {
        currency,
        accounts,
        categories: categories.into_iter().map(|(_, c)| c).collect(),
        transactions,
    })
}

/// Accounts in rowid order; a later row with the same id replaces the earlier one in place.
fn read_accounts(connection: &Connection) -> ReadResult<Vec<AccountRow>> {
    let sql = format!(
        "SELECT {}, {}, {}, {}, {} FROM accounts ORDER BY rowid",
        id_column("id", MAX_ACCOUNT_ID),
        text_column("name"),
        flag_column("offbudget"),
        flag_column("closed"),
        flag_column("tombstone"),
    );
    let mut statement = connection.prepare(&sql)?;
    let mut rows = statement.query([])?;
    let mut accounts: Vec<AccountRow> = Vec::new();
    let mut index: HashMap<String, usize> = HashMap::new();
    while let Some(row) = rows.next()? {
        let Some(id) = id_at(row, 0, MAX_ACCOUNT_ID)? else {
            continue;
        };
        let account = AccountRow {
            id: id.clone(),
            name: name_or(text_at(row, 1), "Actual account"),
            off_budget: int_at(row, 2) != 0,
            closed: int_at(row, 3) != 0,
            tombstone: int_at(row, 4) != 0,
        };
        match index.get(&id) {
            Some(&i) => accounts[i] = account,
            None => {
                index.insert(id, accounts.len());
                accounts.push(account);
            }
        }
    }
    Ok(accounts)
}

/// Live categories of live groups in rowid order, keyed by id (a repeated id keeps its slot).
fn read_categories(connection: &Connection) -> ReadResult<Vec<(String, SourceCategory)>> {
    let mut groups: HashMap<String, GroupRow> = HashMap::new();
    {
        let sql = format!(
            "SELECT {}, {}, {} FROM category_groups",
            id_column("id", MAX_CATEGORY_ID),
            text_column("name"),
            flag_column("tombstone"),
        );
        let mut statement = connection.prepare(&sql)?;
        let mut rows = statement.query([])?;
        while let Some(row) = rows.next()? {
            if let Some(id) = id_at(row, 0, MAX_CATEGORY_ID)? {
                groups.insert(
                    id,
                    GroupRow {
                        name: text_at(row, 1),
                        tombstone: int_at(row, 2) != 0,
                    },
                );
            }
        }
    }
    let sql = format!(
        "SELECT {}, {}, {}, {}, {} FROM categories ORDER BY rowid",
        id_column("id", MAX_CATEGORY_ID),
        text_column("name"),
        flag_column("is_income"),
        id_column("cat_group", MAX_CATEGORY_ID),
        flag_column("tombstone"),
    );
    let mut statement = connection.prepare(&sql)?;
    let mut rows = statement.query([])?;
    let mut categories: Vec<(String, SourceCategory)> = Vec::new();
    while let Some(row) = rows.next()? {
        let id = id_at(row, 0, MAX_CATEGORY_ID)?;
        let group_id = id_at(row, 3, MAX_CATEGORY_ID)?;
        let group = group_id.as_ref().and_then(|g| groups.get(g));
        let Some(id) = id else {
            continue;
        };
        if int_at(row, 4) != 0 || group.is_some_and(|g| g.tombstone) {
            continue;
        }
        let category = SourceCategory {
            id: id.clone(),
            name: name_or(text_at(row, 1), "Actual category"),
            group_id: group_id.clone(),
            group_name: group.and_then(|g| g.name.clone()),
            income: int_at(row, 2) != 0,
        };
        match categories.iter_mut().find(|(k, _)| *k == id) {
            Some(slot) => slot.1 = category,
            None => categories.push((id, category)),
        }
        if categories.len() > MAX_CATEGORIES {
            return Err(bad(format!(
                "The Actual Budget file has more than {MAX_CATEGORIES} categories"
            ))
            .into());
        }
    }
    Ok(categories)
}

fn read_payees(
    connection: &Connection,
    accounts: &[AccountRow],
) -> ReadResult<HashMap<String, PayeeRow>> {
    let names: HashMap<&str, &str> = accounts
        .iter()
        .map(|a| (a.id.as_str(), a.name.as_str()))
        .collect();
    let sql = format!(
        "SELECT {}, {}, {} FROM payees",
        id_column("id", MAX_PAYEE_ID),
        text_column("name"),
        id_column("transfer_acct", MAX_ACCOUNT_ID),
    );
    let mut statement = connection.prepare(&sql)?;
    let mut rows = statement.query([])?;
    let mut payees = HashMap::new();
    while let Some(row) = rows.next()? {
        let Some(id) = id_at(row, 0, MAX_PAYEE_ID)? else {
            continue;
        };
        let transfer_account = id_at(row, 2, MAX_ACCOUNT_ID)?;
        let name = match transfer_account.as_deref().and_then(|t| names.get(t)) {
            Some(other) => Some((*other).to_owned()),
            None => text_at(row, 1),
        };
        payees.insert(
            id,
            PayeeRow {
                name,
                transfer_account_id: transfer_account,
            },
        );
    }
    Ok(payees)
}

fn read_mapping(
    connection: &Connection,
    table: &str,
    target_column: &str,
    max_id: usize,
) -> ReadResult<HashMap<String, String>> {
    let sql = format!(
        "SELECT {}, {} FROM {table}",
        id_column("id", max_id),
        id_column(target_column, max_id),
    );
    let mut statement = connection.prepare(&sql)?;
    let mut rows = statement.query([])?;
    let mut mapping = HashMap::new();
    while let Some(row) = rows.next()? {
        let id = id_at(row, 0, max_id)?;
        let target = id_at(row, 1, max_id)?;
        if let (Some(id), Some(target)) = (id, target) {
            mapping.insert(id, target);
        }
    }
    Ok(mapping)
}

fn read_transactions(
    connection: &Connection,
    has_parent_id: bool,
) -> ReadResult<Vec<TransactionRow>> {
    let parent_column = if has_parent_id {
        id_column("parent_id", MAX_TRANSACTION_ID)
    } else {
        "NULL AS parent_id".to_owned()
    };
    let sql = format!(
        "SELECT {}, {}, {}, {}, {}, {}, {}, {}, {}, {parent_column}, {}, {}, {} \
         FROM transactions ORDER BY date, rowid",
        id_column("id", MAX_TRANSACTION_ID),
        id_column("acct", MAX_ACCOUNT_ID),
        id_column("category", MAX_CATEGORY_ID),
        number_column("amount"),
        id_column("description", MAX_PAYEE_ID),
        text_column("notes"),
        number_column("date"),
        flag_column("isParent"),
        flag_column("isChild"),
        flag_column("tombstone"),
        id_column("transferred_id", MAX_TRANSACTION_ID),
        flag_column("starting_balance_flag"),
    );
    let mut statement = connection.prepare(&sql)?;
    let mut rows = statement.query([])?;
    let mut transactions = Vec::new();
    while let Some(row) = rows.next()? {
        let Some(id) = id_at(row, 0, MAX_TRANSACTION_ID)? else {
            continue;
        };
        let child = int_at(row, 8) != 0;
        let mut parent_id = id_at(row, 9, MAX_TRANSACTION_ID)?;
        if child && parent_id.is_none() {
            // Splits written before the parent_id column encoded the parent in the child id.
            if let Some((parent, _)) = id.split_once('/') {
                parent_id = Some(parent.to_owned());
            }
        }
        transactions.push(TransactionRow {
            account_id: id_at(row, 1, MAX_ACCOUNT_ID)?,
            category_id: id_at(row, 2, MAX_CATEGORY_ID)?,
            amount: number_at(row, 3),
            payee_id: id_at(row, 4, MAX_PAYEE_ID)?,
            notes: text_at(row, 5),
            date: number_at(row, 6),
            parent: int_at(row, 7) != 0,
            child,
            parent_id,
            tombstone: int_at(row, 10) != 0,
            transferred_id: id_at(row, 11, MAX_TRANSACTION_ID)?,
            starting_balance: int_at(row, 12) != 0,
            id,
        });
    }
    Ok(transactions)
}

/// Actual's visibility rules. A split is imported as its children, not its parent: the
/// children carry the categories, sum to the parent, and Actual computes balances from
/// non-parent rows the same way. A parent whose children are all deleted is imported as is.
fn resolve_transactions(
    rows: Vec<TransactionRow>,
    live_accounts: &HashSet<&str>,
    categories: &[(String, SourceCategory)],
    category_mapping: &HashMap<String, String>,
    payees: &HashMap<String, PayeeRow>,
    payee_mapping: &HashMap<String, String>,
) -> Result<Vec<SourceTransaction>, Rejected> {
    let category_ids: HashSet<&str> = categories.iter().map(|(id, _)| id.as_str()).collect();
    let mut parents: HashMap<&str, &TransactionRow> = HashMap::new();
    let mut live_children: HashMap<&str, usize> = HashMap::new();
    for row in &rows {
        if row.parent {
            parents.insert(row.id.as_str(), row);
        }
    }
    for row in &rows {
        if row.child
            && !row.tombstone
            && let Some(parent) = row.parent_id.as_deref()
        {
            *live_children.entry(parent).or_default() += 1;
        }
    }
    let resolve_payee = |payee_id: Option<&str>| -> Option<&PayeeRow> {
        let payee_id = payee_id?;
        let target = payee_mapping.get(payee_id).map_or(payee_id, String::as_str);
        payees.get(target)
    };
    let mut transactions = Vec::new();
    let mut ids: HashSet<&str> = HashSet::new();
    for row in &rows {
        if !ids.insert(row.id.as_str()) {
            return Err(bad("The Actual Budget file has duplicate transaction ids"));
        }
        let live = row
            .account_id
            .as_deref()
            .is_some_and(|a| live_accounts.contains(a));
        if row.tombstone || !live {
            continue;
        }
        if row.parent && live_children.get(row.id.as_str()).copied().unwrap_or(0) > 0 {
            continue;
        }
        let parent = if row.child {
            row.parent_id
                .as_deref()
                .and_then(|p| parents.get(p).copied())
        } else {
            None
        };
        if row.child && parent.is_none_or(|p| p.tombstone) {
            continue;
        }
        let mut payee = resolve_payee(row.payee_id.as_deref());
        if payee.is_none()
            && let Some(parent) = parent
        {
            payee = resolve_payee(parent.payee_id.as_deref());
        }
        let notes = row
            .notes
            .clone()
            .or_else(|| parent.and_then(|p| p.notes.clone()));
        let transfer =
            row.transferred_id.is_some() || payee.is_some_and(|p| p.transfer_account_id.is_some());
        let kind = if row.starting_balance {
            Kind::StartingBalance
        } else if transfer {
            Kind::Transfer
        } else {
            Kind::Regular
        };
        let category_id = if kind == Kind::Regular {
            row.category_id.as_deref().and_then(|c| {
                let target = category_mapping.get(c).map_or(c, String::as_str);
                category_ids.contains(target).then(|| target.to_owned())
            })
        } else {
            None
        };
        let date = date(&row.date)?;
        let amount = amount(&row.amount)?;
        transactions.push(SourceTransaction {
            id: row.id.clone(),
            account_id: row.account_id.clone().unwrap_or_default(),
            date,
            amount,
            payee: payee.and_then(|p| p.name.clone()),
            notes,
            category_id,
            kind,
        });
        if transactions.len() > MAX_TRANSACTIONS {
            return Err(bad(format!(
                "The Actual Budget file has more than {MAX_TRANSACTIONS} transactions"
            )));
        }
    }
    Ok(transactions)
}

fn read_currency(connection: &Connection) -> Option<String> {
    let value: Option<String> = connection
        .query_row(
            "SELECT substr(value, 1, 8) FROM preferences WHERE id = 'defaultCurrencyCode'",
            [],
            |row| Ok(text_at(row, 0)),
        )
        .ok()
        .flatten();
    let value = java_trim(&value?).to_uppercase();
    (value.len() == 3 && value.bytes().all(|b| b.is_ascii_uppercase())).then_some(value)
}

/// YYYYMMDD is a calendar date: built from its parts, never through a zone. The parts are cut
/// to 32 bits as Java's (int) casts do.
fn date(value: &Number) -> Result<NaiveDate, Rejected> {
    let invalid = || bad("Invalid transaction date in the Actual Budget file");
    let raw = integer(value, "date")?;
    let year = (raw / 10_000) as i32;
    let month = (raw / 100 % 100) as i32;
    let day = (raw % 100) as i32;
    if !(1900..=2200).contains(&year) {
        return Err(invalid());
    }
    let month = u32::try_from(month).map_err(|_| invalid())?;
    let day = u32::try_from(day).map_err(|_| invalid())?;
    NaiveDate::from_ymd_opt(year, month, day).ok_or_else(invalid)
}

/// Integer hundredths, kept signed: an outflow stays negative.
fn amount(value: &Number) -> Result<Decimal, Rejected> {
    let raw = integer(value, "amount")?;
    if raw.unsigned_abs() > MAX_AMOUNT_HUNDREDTHS as u64 {
        return Err(bad(
            "Transaction amount out of range in the Actual Budget file",
        ));
    }
    Ok(Decimal::new(raw, 2))
}

fn integer(value: &Number, field: &str) -> Result<i64, Rejected> {
    let parsed = match value {
        Number::Integer(i) => Some(*i),
        Number::Text(text) if text.chars().count() <= MAX_NUMBER_TEXT => {
            exact_long(java_trim(text))
        }
        _ => None,
    };
    parsed.ok_or_else(|| {
        bad(format!(
            "Invalid transaction {field} in the Actual Budget file"
        ))
    })
}

/// new BigDecimal(text).longValueExact(): a decimal with an optional sign, fraction and
/// exponent, accepted only when it is a whole number within the long range.
fn exact_long(text: &str) -> Option<i64> {
    let (negative, rest) = match text.as_bytes().first()? {
        b'-' => (true, &text[1..]),
        b'+' => (false, &text[1..]),
        _ => (false, text),
    };
    let (mantissa, exponent) = match rest.find(['e', 'E']) {
        Some(i) => {
            let exp = &rest[i + 1..];
            let digits = exp.strip_prefix(['+', '-']).unwrap_or(exp);
            if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) {
                return None;
            }
            (&rest[..i], exp.parse::<i64>().ok()?)
        }
        None => (rest, 0),
    };
    let (whole, fraction) = mantissa.split_once('.').unwrap_or((mantissa, ""));
    if (whole.is_empty() && fraction.is_empty())
        || !whole
            .bytes()
            .chain(fraction.bytes())
            .all(|b| b.is_ascii_digit())
    {
        return None;
    }
    let all = format!("{whole}{fraction}");
    let significant = all.trim_start_matches('0');
    if significant.is_empty() {
        return Some(0);
    }
    let digits = significant.trim_end_matches('0');
    let trailing = (significant.len() - digits.len()) as i64;
    let shift = trailing
        .checked_add(exponent)?
        .checked_sub(fraction.len() as i64)?;
    if shift < 0 || digits.len() as i64 + shift > 19 {
        return None;
    }
    let mut value: i128 = digits.parse().ok()?;
    for _ in 0..shift {
        value *= 10;
    }
    i64::try_from(if negative { -value } else { value }).ok()
}

/// Selects an id cut one past its cap, so an overlong one is detected without being copied out.
fn id_column(column: &str, max: usize) -> String {
    format!("substr({column}, 1, {}) AS {column}", max + 1)
}

/// Selects free text cut to the width Picsou stores.
fn text_column(column: &str) -> String {
    format!("substr({column}, 1, {MAX_TEXT}) AS {column}")
}

/// Keeps integers as integers and turns a REAL into its text; text in a numeric column is cut,
/// since no valid number is longer.
fn number_column(column: &str) -> String {
    format!(
        "CASE WHEN typeof({column}) = 'integer' THEN {column} \
         WHEN typeof({column}) = 'real' THEN CAST({column} AS TEXT) \
         ELSE substr({column}, 1, {}) END AS {column}",
        MAX_NUMBER_TEXT + 1
    )
}

/// The driver's getInt: NULL is 0, a real or text is converted the way SQLite casts it.
fn flag_column(column: &str) -> String {
    format!("CAST({column} AS INTEGER) AS {column}")
}

fn text_at(row: &Row<'_>, index: usize) -> Option<String> {
    match row.get_ref(index).ok()? {
        ValueRef::Text(bytes) | ValueRef::Blob(bytes) => {
            Some(String::from_utf8_lossy(bytes).into_owned())
        }
        ValueRef::Integer(i) => Some(i.to_string()),
        ValueRef::Null | ValueRef::Real(_) => None,
    }
}

fn int_at(row: &Row<'_>, index: usize) -> i64 {
    match row.get_ref(index) {
        Ok(ValueRef::Integer(i)) => i,
        _ => 0,
    }
}

fn number_at(row: &Row<'_>, index: usize) -> Number {
    match row.get_ref(index) {
        Ok(ValueRef::Integer(i)) => Number::Integer(i),
        Ok(ValueRef::Text(bytes)) => Number::Text(String::from_utf8_lossy(bytes).into_owned()),
        _ => Number::Invalid,
    }
}

/// Source ids become Picsou external ids and slugs, whose columns are bounded.
fn id_at(row: &Row<'_>, index: usize, max: usize) -> Result<Option<String>, Rejected> {
    let id = text_at(row, index);
    if id.as_ref().is_some_and(|id| id.chars().count() > max) {
        return Err(bad("Unsupported identifier in the Actual Budget file"));
    }
    Ok(id)
}

fn name_or(value: Option<String>, fallback: &str) -> String {
    match value {
        Some(v) if !java_is_blank(&v) => super::java_strip(&v).to_owned(),
        _ => fallback.to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SCHEMA: &str = "CREATE TABLE accounts (id TEXT PRIMARY KEY, name TEXT, offbudget INTEGER DEFAULT 0, \
        closed INTEGER DEFAULT 0, tombstone INTEGER DEFAULT 0); \
        CREATE TABLE category_groups (id TEXT, name TEXT, is_income INTEGER, tombstone INTEGER); \
        CREATE TABLE categories (id TEXT, name TEXT, is_income INTEGER, cat_group TEXT, tombstone INTEGER); \
        CREATE TABLE payees (id TEXT, name TEXT, transfer_acct TEXT, tombstone INTEGER); \
        CREATE TABLE transactions (id TEXT PRIMARY KEY, acct TEXT, category TEXT, amount INTEGER, \
        description TEXT, notes TEXT, date INTEGER, isParent INTEGER DEFAULT 0, isChild INTEGER DEFAULT 0, \
        tombstone INTEGER DEFAULT 0, transferred_id TEXT, starting_balance_flag INTEGER DEFAULT 0); \
        INSERT INTO accounts (id, name) VALUES ('a', '  Cash  '); \
        INSERT INTO transactions (id, acct, amount, date) VALUES ('t', 'a', '-1.25e2', 20240229);";

    fn database(root: &Path) -> Vec<u8> {
        let path = root.join("fixture.sqlite");
        Connection::open(&path)
            .unwrap()
            .execute_batch(SCHEMA)
            .unwrap();
        std::fs::read(path).unwrap()
    }

    fn private_root() -> PathBuf {
        let root = std::env::temp_dir().join(format!("actual-test-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        root
    }

    fn copies(root: &Path) -> usize {
        std::fs::read_dir(root)
            .unwrap()
            .filter(|e| {
                e.as_ref()
                    .unwrap()
                    .file_name()
                    .to_string_lossy()
                    .starts_with("picsou-actual-")
            })
            .count()
    }

    #[test]
    fn deletes_its_temporary_copy_after_success_and_failure() {
        let root = private_root();
        let parser = FileParser::with_limits(
            DEFAULT_MAX_ENTRIES,
            DEFAULT_MAX_UNCOMPRESSED_BYTES,
            root.clone(),
        );
        let budget = parser.parse(&database(&root)).unwrap();
        assert_eq!(budget.accounts[0].name, "Cash");
        assert_eq!(budget.transactions[0].amount, Decimal::new(-125, 2));
        assert_eq!(
            budget.transactions[0].date,
            NaiveDate::from_ymd_opt(2024, 2, 29).unwrap()
        );
        assert!(parser.parse(b"SQLite format 3\0garbage").is_err());
        assert_eq!(copies(&root), 0);
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn a_bare_database_over_the_cap_is_refused() {
        let root = private_root();
        let parser = FileParser::with_limits(DEFAULT_MAX_ENTRIES, 16, root.clone());
        assert_eq!(
            parser.parse(&database(&root)),
            Err(bad("The Actual Budget database is too large"))
        );
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn numbers_follow_big_decimal_long_value_exact() {
        for (text, expected) in [
            ("12", Some(12)),
            ("+12", Some(12)),
            ("-12.00", Some(-12)),
            ("1e3", Some(1000)),
            ("1.5E+1", Some(15)),
            ("1200e-2", Some(12)),
            ("0.000", Some(0)),
            (".5", None),
            ("5.", Some(5)),
            ("12.5", None),
            ("1e-1", None),
            ("abc", None),
            ("", None),
            ("1e", None),
            ("9223372036854775807", Some(i64::MAX)),
            ("9223372036854775808", None),
            ("-9223372036854775808", Some(i64::MIN)),
        ] {
            assert_eq!(exact_long(text), expected, "{text}");
        }
    }

    #[test]
    fn unsafe_entry_names() {
        for name in ["", "/etc/x", "a\\b", "c:x", "a/../b", "..", "a\0"] {
            assert!(is_unsafe(name), "{name:?}");
        }
        for name in ["db.sqlite", "dir/db.sqlite", "a..b"] {
            assert!(!is_unsafe(name), "{name:?}");
        }
    }

    #[test]
    fn a_preferences_view_is_refused() {
        let root = private_root();
        let path = root.join("view.sqlite");
        let conn = Connection::open(&path).unwrap();
        conn.execute_batch(SCHEMA).unwrap();
        conn.execute_batch(
            "CREATE VIEW preferences AS SELECT 'defaultCurrencyCode' AS id, 'EUR' AS value;",
        )
        .unwrap();
        drop(conn);
        let bytes = std::fs::read(&path).unwrap();
        let parser = FileParser::with_limits(
            DEFAULT_MAX_ENTRIES,
            DEFAULT_MAX_UNCOMPRESSED_BYTES,
            root.clone(),
        );
        assert_eq!(
            parser.parse(&bytes),
            Err(bad(
                "Unsupported Actual Budget database: 'preferences' is not a plain table"
            ))
        );
        std::fs::remove_dir_all(root).unwrap();
    }
}
