//! Applies backend/src/main/resources/db/migration exactly as Flyway 11 does, so a database can
//! move between the Java and the Rust backend in both directions: same flyway_schema_history
//! rows (version, description, type, script, CRC32 checksum, installed_by, installed_rank), out of
//! order allowed, no applied version may be missing locally, plus the startup callback
//! LegacyMigrationRenumberingCallback and the baseline check of ReleaseMigrationCli.

use std::cmp::Ordering;
use std::time::Instant;

use include_dir::{Dir, include_dir};
use sqlx::{Connection, PgConnection, Row};

static MIGRATIONS: Dir<'_> =
    include_dir!("$CARGO_MANIFEST_DIR/../backend/src/main/resources/db/migration");

const TABLE: &str = "flyway_schema_history";
const QUOTED_TABLE: &str = "\"public\".\"flyway_schema_history\"";

#[derive(Debug, thiserror::Error)]
pub enum MigrationError {
    #[error("migration file {0} is not named V<version>__<description>.sql")]
    BadName(String),
    #[error("Validate failed: Migrations have failed validation\n{0}")]
    Validation(String),
    #[error("Schema baseline {0} is newer than the available migrations")]
    HighBaseline(String),
    #[error(
        "{0} group(s) of active accounts share an external_account_id and member. Pending migration V47 would \
         delete all but the newest account of each group, with its history. Soft-delete the extra accounts or \
         change their external_account_id, then start again. See docs/features/docker-deployment.md#upgrading-a-10x-database-to-110"
    )]
    DuplicateExternalAccounts(i64),
    #[error("Migration {script} failed: {source}")]
    Failed { script: String, source: sqlx::Error },
    #[error("database error: {0}")]
    Database(#[from] sqlx::Error),
}

/// A Flyway version: dot-separated numeric parts, compared numerically, trailing zeros ignored.
#[derive(Debug, Clone, Eq)]
pub struct Version(Vec<u64>, String);

impl Version {
    pub fn parse(raw: &str) -> Option<Self> {
        let parts = raw
            .replace('_', ".")
            .split('.')
            .map(|p| p.parse::<u64>().ok())
            .collect::<Option<Vec<_>>>()?;
        Some(Self(parts, raw.to_owned()))
    }

    pub fn as_str(&self) -> &str {
        &self.1
    }

    fn trimmed(&self) -> &[u64] {
        let end = self.0.iter().rposition(|p| *p != 0).map_or(0, |i| i + 1);
        &self.0[..end]
    }
}

impl PartialEq for Version {
    fn eq(&self, other: &Self) -> bool {
        self.trimmed() == other.trimmed()
    }
}

impl Ord for Version {
    fn cmp(&self, other: &Self) -> Ordering {
        let (a, b) = (self.trimmed(), other.trimmed());
        for i in 0..a.len().max(b.len()) {
            match a
                .get(i)
                .copied()
                .unwrap_or(0)
                .cmp(&b.get(i).copied().unwrap_or(0))
            {
                Ordering::Equal => continue,
                other => return other,
            }
        }
        Ordering::Equal
    }
}

impl PartialOrd for Version {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

/// One embedded migration file.
#[derive(Debug, Clone)]
pub struct Migration {
    pub version: Version,
    pub description: String,
    pub script: String,
    pub checksum: i32,
    pub sql: String,
}

/// Flyway's ChecksumCalculator: CRC32 over the UTF-8 bytes of every line, line breaks excluded,
/// a leading BOM dropped.
pub fn checksum(content: &str) -> i32 {
    let content = content.strip_prefix('\u{feff}').unwrap_or(content);
    let mut hasher = crc32fast::Hasher::new();
    let bytes = content.as_bytes();
    let mut start = 0;
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'\n' => {
                hasher.update(&bytes[start..i]);
                i += 1;
                start = i;
            }
            b'\r' => {
                hasher.update(&bytes[start..i]);
                i += if bytes.get(i + 1) == Some(&b'\n') {
                    2
                } else {
                    1
                };
                start = i;
            }
            _ => i += 1,
        }
    }
    if start < bytes.len() {
        hasher.update(&bytes[start..]);
    }
    hasher.finalize() as i32
}

/// The embedded migrations, sorted by version.
pub fn migrations() -> Result<Vec<Migration>, MigrationError> {
    let mut out = Vec::new();
    for file in MIGRATIONS.files() {
        let script = file
            .path()
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or_default()
            .to_owned();
        let bad = || MigrationError::BadName(script.clone());
        let stem = script.strip_suffix(".sql").ok_or_else(bad)?;
        let (version, description) = stem
            .strip_prefix('V')
            .and_then(|s| s.split_once("__"))
            .ok_or_else(bad)?;
        let sql = file.contents_utf8().ok_or_else(bad)?.to_owned();
        out.push(Migration {
            version: Version::parse(version).ok_or_else(bad)?,
            description: description.replace('_', " "),
            checksum: checksum(&sql),
            script,
            sql,
        });
    }
    out.sort_by(|a, b| a.version.cmp(&b.version));
    Ok(out)
}

#[derive(Debug, Clone)]
struct Applied {
    version: Option<String>,
    description: String,
    kind: String,
    checksum: Option<i32>,
    success: bool,
}

/// One applied file that now lives under new_version (issues #174 and #265).
struct Renumbering {
    old_version: &'static str,
    new_version: &'static str,
    description: &'static str,
    checksum: i32,
    new_checksum: i32,
}

const fn moved(
    old_version: &'static str,
    new_version: &'static str,
    description: &'static str,
    checksum: i32,
) -> Renumbering {
    Renumbering {
        old_version,
        new_version,
        description,
        checksum,
        new_checksum: checksum,
    }
}

const fn moved_commented(
    old_version: &'static str,
    new_version: &'static str,
    description: &'static str,
    checksum: i32,
    new_checksum: i32,
) -> Renumbering {
    Renumbering {
        old_version,
        new_version,
        description,
        checksum,
        new_checksum,
    }
}

// Applied in list order inside one transaction: a move whose target still holds an older row of
// the same chain comes after the move that frees it.
const RENUMBERINGS: &[Renumbering] = &[
    moved("80", "93", "fortuneo session", -1569596521),
    moved(
        "80",
        "94",
        "backfill tr crypto transaction tickers",
        2050086936,
    ),
    moved("81", "95", "fortuneo integration", -76224459),
    moved("81", "96", "backfill trade republic valuations", 664629777),
    moved("86", "97", "security profile isin", 1417570777),
    moved("87", "98", "security profile status", -797426580),
    moved("88", "99", "security profile fund facts", 140413632),
    moved("64", "96", "backfill trade republic valuations", 664629777),
    moved(
        "63",
        "65",
        "constrain bourse direct sync errors",
        -1764174252,
    ),
    moved("62", "64", "backfill bourse direct valuations", 88940041),
    moved("61", "63", "harden bourse direct sync", -840345283),
    moved("60", "62", "bourse direct integration", 236247500),
    moved("59", "61", "bourse direct session", 2053764575),
    moved_commented("58", "60", "account cash balance", -694849918, -1212750843),
    moved_commented("57", "59", "ibkr connection", 1520765453, -1382846890),
    moved_commented(
        "56",
        "58",
        "persistent session previous token",
        720404896,
        1251005780,
    ),
    moved_commented(
        "55",
        "57",
        "wallet evm account name",
        -1244807568,
        -1770899403,
    ),
    moved("54", "56", "wallet ethereum to evm", 903440536),
    moved_commented("51", "51", "requisition oauth state", 1952390840, 960451194),
    moved(
        "38",
        "94",
        "backfill tr crypto transaction tickers",
        2050086936,
    ),
    moved("33", "36", "transaction security name", -924862297),
];

/// Flyway's PostgreSQL advisory lock number: "Flyway" in ASCII plus the table's hash.
fn advisory_lock_id() -> i64 {
    let magic: i64 = (0x46 << 40) + (0x6C << 32) + (0x79 << 24) + (0x77 << 16) + (0x61 << 8) + 0x79;
    let hash = QUOTED_TABLE
        .encode_utf16()
        .fold(0i32, |h, c| h.wrapping_mul(31).wrapping_add(i32::from(c)));
    magic + i64::from(hash)
}

/// Migrates the database to the embedded schema and reports how many files it applied.
pub async fn migrate(conn: &mut PgConnection) -> Result<usize, MigrationError> {
    let lock = advisory_lock_id();
    sqlx::query("SELECT pg_advisory_lock($1)")
        .bind(lock)
        .execute(&mut *conn)
        .await?;
    let outcome = migrate_locked(conn).await;
    sqlx::query("SELECT pg_advisory_unlock($1)")
        .bind(lock)
        .execute(&mut *conn)
        .await?;
    outcome
}

async fn migrate_locked(conn: &mut PgConnection) -> Result<usize, MigrationError> {
    let local = migrations()?;
    ensure_history_table(conn).await?;

    let mut tx = conn.begin().await?;
    renumber(&mut tx).await?;
    let duplicates = duplicate_groups_before_v47(&mut tx).await?;
    if duplicates > 0 {
        return Err(MigrationError::DuplicateExternalAccounts(duplicates));
    }
    tx.commit().await?;

    let applied = applied_rows(conn).await?;
    validate(&local, &applied)?;
    reject_high_baseline(&local, &applied)?;

    let pending = pending_of(&local, &applied);

    let installed_by: String = sqlx::query_scalar("SELECT current_user::text")
        .fetch_one(&mut *conn)
        .await?;
    for migration in &pending {
        apply(conn, migration, &installed_by).await?;
    }
    Ok(pending.len())
}

/// The scripts `migrate` would apply, read without the lock or any write: the desktop app backs the
/// data up first when there are any. A database without a history table has every script pending.
pub async fn pending(conn: &mut PgConnection) -> Result<Vec<String>, MigrationError> {
    let local = migrations()?;
    let applied = if table_exists(conn, TABLE).await? {
        applied_rows(conn).await?
    } else {
        Vec::new()
    };
    Ok(pending_of(&local, &applied)
        .into_iter()
        .map(|m| m.script.clone())
        .collect())
}

fn pending_of<'a>(local: &'a [Migration], applied: &[Applied]) -> Vec<&'a Migration> {
    let baseline = applied
        .iter()
        .filter(|a| a.kind == "BASELINE")
        .filter_map(|a| a.version.as_deref().and_then(Version::parse))
        .max();
    let applied_versions: Vec<Version> = applied
        .iter()
        .filter(|a| a.success)
        .filter_map(|a| a.version.as_deref().and_then(Version::parse))
        .collect();
    local
        .iter()
        .filter(|m| !applied_versions.contains(&m.version))
        .filter(|m| baseline.as_ref().is_none_or(|b| m.version > *b))
        .collect()
}

async fn ensure_history_table(conn: &mut PgConnection) -> Result<(), MigrationError> {
    if table_exists(conn, TABLE).await? {
        return Ok(());
    }
    let mut tx = conn.begin().await?;
    sqlx::raw_sql(
        "CREATE TABLE \"public\".\"flyway_schema_history\" (\n\
         \"installed_rank\" INT NOT NULL,\n\
         \"version\" VARCHAR(50),\n\
         \"description\" VARCHAR(200) NOT NULL,\n\
         \"type\" VARCHAR(20) NOT NULL,\n\
         \"script\" VARCHAR(1000) NOT NULL,\n\
         \"checksum\" INT,\n\
         \"installed_by\" VARCHAR(100) NOT NULL,\n\
         \"installed_on\" TIMESTAMP NOT NULL DEFAULT now(),\n\
         \"execution_time\" INT NOT NULL,\n\
         \"success\" BOOLEAN NOT NULL\n\
         );\n\
         ALTER TABLE \"public\".\"flyway_schema_history\" ADD CONSTRAINT \"flyway_schema_history_pk\" PRIMARY KEY (\"installed_rank\");\n\
         CREATE INDEX \"flyway_schema_history_s_idx\" ON \"public\".\"flyway_schema_history\" (\"success\");",
    )
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(())
}

async fn table_exists(conn: &mut PgConnection, table: &str) -> Result<bool, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = $1)",
    )
    .bind(table)
    .fetch_one(conn)
    .await
}

async fn column_exists(
    conn: &mut PgConnection,
    table: &str,
    column: &str,
) -> Result<bool, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT EXISTS (SELECT 1 FROM information_schema.columns \
         WHERE table_schema = 'public' AND table_name = $1 AND column_name = $2)",
    )
    .bind(table)
    .bind(column)
    .fetch_one(conn)
    .await
}

async fn renumber(conn: &mut PgConnection) -> Result<(), sqlx::Error> {
    if !table_exists(conn, TABLE).await? {
        return Ok(());
    }
    let sql = format!(
        "UPDATE {QUOTED_TABLE} AS h SET \"version\" = $1, \"script\" = $2, \"checksum\" = $3 \
         WHERE h.\"version\" = $4 AND h.\"description\" = $5 AND h.\"checksum\" = $6 \
         AND NOT EXISTS (SELECT 1 FROM {QUOTED_TABLE} o WHERE o.\"version\" = $1 AND o.\"installed_rank\" <> h.\"installed_rank\")"
    );
    let mut renumbered = Vec::new();
    for r in RENUMBERINGS {
        let script = format!(
            "V{}__{}.sql",
            r.new_version,
            r.description.replace(' ', "_")
        );
        let done = sqlx::query(&sql)
            .bind(r.new_version)
            .bind(&script)
            .bind(r.new_checksum)
            .bind(r.old_version)
            .bind(r.description)
            .bind(r.checksum)
            .execute(&mut *conn)
            .await?;
        if done.rows_affected() > 0 {
            renumbered.push(format!(
                "V{} '{}' -> V{}",
                r.old_version, r.description, r.new_version
            ));
        }
    }
    if !renumbered.is_empty() {
        tracing::info!(
            "Renumbered {} legacy Flyway schema-history row(s) (issues #174, #265): {}",
            renumbered.len(),
            renumbered.join(", ")
        );
    }
    Ok(())
}

async fn duplicate_groups_before_v47(conn: &mut PgConnection) -> Result<i64, sqlx::Error> {
    if !table_exists(conn, TABLE).await?
        || !column_exists(conn, "account", "external_account_id").await?
    {
        return Ok(0);
    }
    let v47 = Version::parse("47").expect("static version");
    let rows = sqlx::query(&format!(
        "SELECT \"version\", \"type\" FROM {QUOTED_TABLE} WHERE \"version\" IS NOT NULL"
    ))
    .fetch_all(&mut *conn)
    .await?;
    for row in rows {
        let version: String = row.get(0);
        let kind: String = row.get(1);
        if let Some(v) = Version::parse(&version)
            && (v == v47 || (kind == "BASELINE" && v >= v47))
        {
            return Ok(0);
        }
    }
    duplicate_groups(conn).await
}

async fn duplicate_groups(conn: &mut PgConnection) -> Result<i64, sqlx::Error> {
    let member = if column_exists(conn, "account", "member_id").await? {
        ", member_id"
    } else {
        ""
    };
    let active = if column_exists(conn, "account", "deleted_at").await? {
        " AND deleted_at IS NULL"
    } else {
        ""
    };
    sqlx::query_scalar(&format!(
        "SELECT count(*) FROM (SELECT 1 FROM \"public\".account WHERE external_account_id IS NOT NULL{active} \
         GROUP BY external_account_id{member} HAVING count(*) > 1) d"
    ))
    .fetch_one(conn)
    .await
}

async fn applied_rows(conn: &mut PgConnection) -> Result<Vec<Applied>, sqlx::Error> {
    let rows = sqlx::query(&format!(
        "SELECT version, description, type, checksum, success FROM {QUOTED_TABLE} ORDER BY installed_rank"
    ))
    .fetch_all(conn)
    .await?;
    Ok(rows
        .into_iter()
        .map(|r| Applied {
            version: r.get(0),
            description: r.get(1),
            kind: r.get(2),
            checksum: r.get(3),
            success: r.get(4),
        })
        .collect())
}

/// Flyway's validate with ignoreMigrationPatterns empty: a failed row, an applied version this
/// binary lacks, or a changed description or checksum all fail startup.
fn validate(local: &[Migration], applied: &[Applied]) -> Result<(), MigrationError> {
    let mut problems = Vec::new();
    for row in applied
        .iter()
        .filter(|r| r.kind != "BASELINE" && r.kind != "SCHEMA")
    {
        let Some(raw) = &row.version else { continue };
        let Some(version) = Version::parse(raw) else {
            problems.push(format!(
                "Detected applied migration with an invalid version: {raw}"
            ));
            continue;
        };
        if !row.success {
            problems.push(format!(
                "Detected failed migration to version {raw} ({}). Please remove any half-completed changes then run repair to fix the schema history.",
                row.description
            ));
            continue;
        }
        match local.iter().find(|m| m.version == version) {
            None => problems.push(format!(
                "Detected applied migration not resolved locally: {raw}. If you removed this migration intentionally, run repair to mark the migration as deleted."
            )),
            Some(m) if m.description != row.description => problems.push(format!(
                "Migration description mismatch for migration version {raw}\n-> Applied to database : {}\n-> Resolved locally    : {}",
                row.description, m.description
            )),
            Some(m) if Some(m.checksum) != row.checksum => problems.push(format!(
                "Migration checksum mismatch for migration version {raw}\n-> Applied to database : {}\n-> Resolved locally    : {}",
                row.checksum.map_or_else(|| "null".to_owned(), |c| c.to_string()),
                m.checksum
            )),
            Some(_) => {}
        }
    }
    if problems.is_empty() {
        Ok(())
    } else {
        Err(MigrationError::Validation(problems.join("\n")))
    }
}

fn reject_high_baseline(local: &[Migration], applied: &[Applied]) -> Result<(), MigrationError> {
    let Some(baseline) = applied
        .iter()
        .find(|a| a.kind == "BASELINE")
        .and_then(|a| a.version.as_deref())
        .and_then(Version::parse)
    else {
        return Ok(());
    };
    match local.last() {
        Some(latest) if baseline > latest.version => {
            Err(MigrationError::HighBaseline(baseline.as_str().to_owned()))
        }
        _ => Ok(()),
    }
}

async fn apply(
    conn: &mut PgConnection,
    migration: &Migration,
    installed_by: &str,
) -> Result<(), MigrationError> {
    let started = Instant::now();
    let failed = |source| MigrationError::Failed {
        script: migration.script.clone(),
        source,
    };
    let mut tx = conn.begin().await?;
    if migration.version == Version::parse("47").expect("static version") {
        // The up-front check committed already; lock account writes until V47 commits so the
        // groups counted here are the groups V47 deletes.
        sqlx::query("LOCK TABLE \"public\".account IN SHARE ROW EXCLUSIVE MODE")
            .execute(&mut *tx)
            .await?;
        let groups = duplicate_groups(&mut tx).await?;
        if groups > 0 {
            return Err(MigrationError::DuplicateExternalAccounts(groups));
        }
    }
    sqlx::raw_sql(&migration.sql)
        .execute(&mut *tx)
        .await
        .map_err(failed)?;
    let rank: i32 = sqlx::query_scalar(&format!(
        "SELECT COALESCE(MAX(installed_rank), 0) + 1 FROM {QUOTED_TABLE}"
    ))
    .fetch_one(&mut *tx)
    .await?;
    let elapsed = i32::try_from(started.elapsed().as_millis()).unwrap_or(i32::MAX);
    sqlx::query(&format!(
        "INSERT INTO {QUOTED_TABLE} (\"installed_rank\", \"version\", \"description\", \"type\", \"script\", \
         \"checksum\", \"installed_by\", \"execution_time\", \"success\") VALUES ($1, $2, $3, 'SQL', $4, $5, $6, $7, true)"
    ))
    .bind(rank)
    .bind(migration.version.as_str())
    .bind(&migration.description)
    .bind(&migration.script)
    .bind(migration.checksum)
    .bind(installed_by)
    .bind(elapsed)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    tracing::info!(
        "Migrating schema \"public\" to version \"{} - {}\"",
        migration.version.as_str(),
        migration.description
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn checksum_ignores_line_endings() {
        assert_eq!(checksum("a\nb\n"), checksum("a\r\nb"));
        assert_eq!(checksum("\u{feff}a\nb"), checksum("a\nb"));
    }

    #[test]
    fn versions_compare_numerically() {
        let v = |s| Version::parse(s).unwrap();
        assert!(v("10") > v("9"));
        assert_eq!(v("47"), v("47.0"));
        assert!(v("1.1") > v("1"));
    }

    #[test]
    fn advisory_lock_matches_flyway() {
        assert_eq!(
            advisory_lock_id(),
            77_431_708_279_161 + i64::from(java_hash(QUOTED_TABLE))
        );
    }

    fn java_hash(s: &str) -> i32 {
        s.chars()
            .fold(0i32, |h, c| h.wrapping_mul(31).wrapping_add(c as i32))
    }
}
