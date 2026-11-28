//! `picsou export-archive` / `import-archive`: the encrypted portable archive of issue #865.

use std::path::{Path, PathBuf};

use picsou::config::{Datasource, Env};
use picsou::db::archive::{
    self, ArchiveError, ExportSource, ImportTarget, SourceEngine, import_archive,
    import_archive_into,
};
use picsou::db::sqlite::SqliteDb;
use picsou::testing::{DB_HOST, DB_PORT, DB_USER, TestApp, TestDatabase, fresh_database};
use sqlx::PgPool;
use uuid::Uuid;

const PASSPHRASE: &str = "a long enough passphrase";
const CRYPTO_KEY: &[u8] = b"0123456789abcdef0123456789abcdef";

/// One instance's files: `picsou.db`, `secrets/` and the Enable Banking key path, in a unique
/// directory removed on drop.
struct Instance {
    root: PathBuf,
}

impl Instance {
    fn new(tag: &str) -> Self {
        let root = std::env::temp_dir().join(format!(
            "picsou_archive_{tag}_{}_{}",
            std::process::id(),
            Uuid::new_v4().simple()
        ));
        std::fs::create_dir_all(&root).expect("create the instance directory");
        Self { root }
    }

    fn db(&self) -> PathBuf {
        self.root.join("picsou.db")
    }

    fn secrets(&self) -> PathBuf {
        self.root.join("secrets")
    }

    fn crypto_key(&self) -> PathBuf {
        self.secrets().join("crypto_key")
    }

    fn jwt_secret(&self) -> PathBuf {
        self.secrets().join("jwt_secret")
    }

    fn banking_key(&self) -> PathBuf {
        self.root.join("keys").join("enablebanking-private.pem")
    }

    fn env(&self) -> Env {
        Env::from_map([
            ("PICSOU_ARCHIVE_PASSPHRASE", PASSPHRASE.to_owned()),
            ("PICSOU_SECRETS_DIR", self.secrets().display().to_string()),
            (
                "APP_CRYPTO_KEY_PATH",
                self.crypto_key().display().to_string(),
            ),
            (
                "ENABLEBANKING_PRIVATE_KEY_PATH",
                self.banking_key().display().to_string(),
            ),
        ])
    }

    fn write_secrets(&self, crypto_key: &[u8]) {
        std::fs::create_dir_all(self.secrets()).expect("create secrets");
        std::fs::write(self.crypto_key(), crypto_key).expect("write crypto_key");
        std::fs::write(self.jwt_secret(), b"jwt-of-this-instance").expect("write jwt_secret");
    }

    /// A migrated SQLite database holding one member and one user per name.
    fn seed_sqlite(&self, users: &[&str]) {
        let db = SqliteDb::open(&self.db()).expect("open the database");
        db.migrate().expect("migrate");
        db.with_conn(|conn| {
            for (i, name) in users.iter().enumerate() {
                let id = i64::try_from(i).expect("small index") + 1;
                conn.execute(
                    "INSERT INTO family_member (id, display_name) VALUES (?1, ?2)",
                    rusqlite::params![id, name],
                )?;
                conn.execute(
                    "INSERT INTO app_user (username, password_hash, member_id) VALUES (?1, 'hash', ?2)",
                    rusqlite::params![name, id],
                )?;
            }
            Ok(())
        })
        .expect("seed rows");
    }

    fn usernames(&self) -> Vec<String> {
        let conn = rusqlite::Connection::open_with_flags(
            self.db(),
            rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY,
        )
        .expect("open read-only");
        let mut stmt = conn
            .prepare("SELECT username FROM app_user ORDER BY username")
            .expect("prepare");
        stmt.query_map([], |r| r.get::<_, String>(0))
            .expect("query")
            .collect::<Result<_, _>>()
            .expect("rows")
    }
}

impl Drop for Instance {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.root);
    }
}

fn backups(instance: &Instance) -> Vec<PathBuf> {
    let Ok(entries) = std::fs::read_dir(instance.root.join("backups")) else {
        return Vec::new();
    };
    entries.map(|e| e.expect("entry").path()).collect()
}

async fn export_sqlite(source: &Instance, out: &Path) -> archive::ExportReport {
    archive::export_archive(ExportSource::Sqlite(&source.db()), out, &source.env())
        .await
        .expect("export the archive")
}

fn mode(path: &Path) -> u32 {
    use std::os::unix::fs::PermissionsExt;
    std::fs::metadata(path)
        .expect("metadata")
        .permissions()
        .mode()
        & 0o777
}

#[tokio::test]
async fn a_sqlite_instance_round_trips_into_a_fresh_one() {
    let source = Instance::new("src");
    source.write_secrets(CRYPTO_KEY);
    std::fs::create_dir_all(source.banking_key().parent().expect("parent")).expect("keys dir");
    std::fs::write(source.banking_key(), b"-----BEGIN PRIVATE KEY-----").expect("pem");
    source.seed_sqlite(&["alice", "bob"]);
    let out = source.root.join("out.picsou-archive");

    let exported = export_sqlite(&source, &out).await;
    assert_eq!(exported.source_engine, SourceEngine::Sqlite);
    assert!(exported.enablebanking_key);
    assert_eq!(mode(&out), 0o600);
    let sealed = std::fs::read(&out).expect("read the archive");
    assert_eq!(&sealed[..8], b"PICSOUA1");
    assert!(
        !sealed.windows(5).any(|w| w == b"alice"),
        "the database is not in the clear"
    );

    let target = Instance::new("dst");
    std::fs::create_dir_all(target.secrets()).expect("target secrets");
    std::fs::write(target.jwt_secret(), b"jwt-of-the-target").expect("target jwt");
    let imported = import_archive(&out, &target.db(), false, &target.env())
        .await
        .expect("import the archive");

    assert_eq!(imported.backup_dir, None);
    assert_eq!(target.usernames(), ["alice", "bob"]);
    assert_eq!(std::fs::read(target.crypto_key()).expect("key"), CRYPTO_KEY);
    assert_eq!(mode(&target.crypto_key()), 0o600);
    assert_eq!(
        std::fs::read(target.banking_key()).expect("pem"),
        b"-----BEGIN PRIVATE KEY-----"
    );
    assert_eq!(
        std::fs::read(target.jwt_secret()).expect("jwt"),
        b"jwt-of-the-target",
        "the target keeps its own jwt_secret"
    );
}

#[tokio::test]
async fn export_refuses_an_existing_output_and_a_weak_passphrase() {
    let source = Instance::new("refuse");
    source.write_secrets(CRYPTO_KEY);
    source.seed_sqlite(&["alice"]);
    let out = source.root.join("out.picsou-archive");
    std::fs::write(&out, b"precious").expect("existing file");

    let err = archive::export_archive(ExportSource::Sqlite(&source.db()), &out, &source.env())
        .await
        .expect_err("existing output");
    assert!(matches!(err, ArchiveError::OutputExists(_)));
    assert_eq!(std::fs::read(&out).expect("untouched"), b"precious");

    std::fs::remove_file(&out).expect("remove");
    let mut env = source.env();
    env.set("PICSOU_ARCHIVE_PASSPHRASE", "short");
    let err = archive::export_archive(ExportSource::Sqlite(&source.db()), &out, &env)
        .await
        .expect_err("short passphrase");
    assert!(matches!(err, ArchiveError::PassphraseTooShort));
    assert!(!out.exists());
    let leftovers: Vec<_> = std::fs::read_dir(&source.root)
        .expect("read dir")
        .filter_map(Result::ok)
        .filter(|e| {
            e.file_name()
                .to_string_lossy()
                .starts_with(".picsou-archive-")
        })
        .collect();
    assert!(leftovers.is_empty(), "scratch files are removed");
}

#[tokio::test]
async fn import_refuses_a_target_with_users_unless_replace() {
    let source = Instance::new("src2");
    source.write_secrets(CRYPTO_KEY);
    source.seed_sqlite(&["alice"]);
    let out = source.root.join("out.picsou-archive");
    export_sqlite(&source, &out).await;

    let target = Instance::new("dst2");
    target.write_secrets(b"the-target-own-crypto-key-0123456");
    target.seed_sqlite(&["carol"]);
    let before = std::fs::read(target.db()).expect("db bytes");

    let err = import_archive(&out, &target.db(), false, &target.env())
        .await
        .expect_err("a target with users");
    assert!(matches!(err, ArchiveError::TargetNotEmpty(_)));
    assert_eq!(std::fs::read(target.db()).expect("db bytes"), before);
    assert_eq!(
        std::fs::read(target.crypto_key()).expect("key"),
        b"the-target-own-crypto-key-0123456"
    );
    assert!(backups(&target).is_empty());
    assert_eq!(target.usernames(), ["carol"]);
}

#[tokio::test]
async fn replace_moves_the_old_data_into_backups() {
    let source = Instance::new("src3");
    source.write_secrets(CRYPTO_KEY);
    source.seed_sqlite(&["alice"]);
    let out = source.root.join("out.picsou-archive");
    export_sqlite(&source, &out).await;

    let target = Instance::new("dst3");
    target.write_secrets(b"the-target-own-crypto-key-0123456");
    target.seed_sqlite(&["carol"]);

    let imported = import_archive(&out, &target.db(), true, &target.env())
        .await
        .expect("import with --replace");

    assert_eq!(target.usernames(), ["alice"]);
    assert_eq!(std::fs::read(target.crypto_key()).expect("key"), CRYPTO_KEY);
    let dir = imported.backup_dir.expect("a backup directory");
    assert_eq!(backups(&target), std::slice::from_ref(&dir));
    assert!(
        dir.file_name()
            .expect("name")
            .to_string_lossy()
            .ends_with("-before-import")
    );
    assert_eq!(mode(&dir), 0o700);
    assert_eq!(
        std::fs::read(dir.join("crypto_key")).expect("old key"),
        b"the-target-own-crypto-key-0123456"
    );
    assert_eq!(mode(&dir.join("crypto_key")), 0o600);
    let old = rusqlite::Connection::open_with_flags(
        dir.join("picsou.db"),
        rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY,
    )
    .expect("open the moved database");
    let name: String = old
        .query_row("SELECT username FROM app_user", [], |r| r.get(0))
        .expect("old user");
    assert_eq!(name, "carol");
    assert!(target.jwt_secret().exists());
}

#[tokio::test]
async fn a_wrong_passphrase_writes_nothing() {
    let source = Instance::new("src4");
    source.write_secrets(CRYPTO_KEY);
    source.seed_sqlite(&["alice"]);
    let out = source.root.join("out.picsou-archive");
    export_sqlite(&source, &out).await;

    let target = Instance::new("dst4");
    let mut env = target.env();
    env.set("PICSOU_ARCHIVE_PASSPHRASE", "another long passphrase");
    let err = import_archive(&out, &target.db(), true, &env)
        .await
        .expect_err("wrong passphrase");
    assert!(matches!(err, ArchiveError::Decrypt));
    assert!(!target.db().exists());
    assert!(!target.secrets().exists());
}

#[tokio::test]
async fn a_different_key_in_the_target_environment_is_refused() {
    let source = Instance::new("src7");
    source.write_secrets(CRYPTO_KEY);
    source.seed_sqlite(&["alice"]);
    let out = source.root.join("out.picsou-archive");
    export_sqlite(&source, &out).await;

    let target = Instance::new("dst7");
    let mut env = target.env();
    env.set("CRYPTO_ENCRYPTION_KEY", "ffffffffffffffffffffffffffffffff");
    let err = import_archive(&out, &target.db(), true, &env)
        .await
        .expect_err("the server would ignore the imported key");
    assert!(matches!(err, ArchiveError::KeyOverridden));
    assert!(!target.db().exists());

    env.set(
        "CRYPTO_ENCRYPTION_KEY",
        String::from_utf8(CRYPTO_KEY.to_vec()).expect("ascii key"),
    );
    import_archive(&out, &target.db(), false, &env)
        .await
        .expect("the same key is accepted");
}

#[tokio::test]
async fn a_failed_verification_restores_the_previous_data() {
    let source = Instance::new("src5");
    source.write_secrets(CRYPTO_KEY);
    source.seed_sqlite(&["alice"]);
    let good = source.root.join("good.picsou-archive");
    export_sqlite(&source, &good).await;

    // The same archive, its manifest claiming a row that the database does not have.
    let sealed = std::fs::read(&good).expect("read the archive");
    let zip = archive::open(&sealed, PASSPHRASE).expect("open");
    let mut contents = archive::unpack(&zip).expect("unpack");
    contents
        .manifest
        .table_counts
        .insert("app_user".to_owned(), 99);
    let bad = source.root.join("bad.picsou-archive");
    std::fs::write(
        &bad,
        archive::seal(&archive::pack(&contents).expect("pack"), PASSPHRASE).expect("seal"),
    )
    .expect("write");

    let target = Instance::new("dst5");
    target.write_secrets(b"the-target-own-crypto-key-0123456");
    target.seed_sqlite(&["carol"]);

    let err = import_archive(&bad, &target.db(), true, &target.env())
        .await
        .expect_err("a count mismatch");
    assert!(matches!(err, ArchiveError::Verification(_)), "{err}");
    assert_eq!(target.usernames(), ["carol"]);
    assert_eq!(
        std::fs::read(target.crypto_key()).expect("key"),
        b"the-target-own-crypto-key-0123456"
    );
    assert!(backups(&target).is_empty());
}

#[tokio::test]
async fn a_postgres_instance_round_trips_into_sqlite() {
    let app = TestApp::builder().postgres().with_admin().spawn().await;
    let pg = app.state.db.expect_pg();
    let (_user_id, _member_id) = app.create_member("claire", "strong-pass-123").await;

    let source = Instance::new("pg");
    source.write_secrets(CRYPTO_KEY);
    let out = source.root.join("out.picsou-archive");
    let exported = archive::export_archive(ExportSource::Postgres(pg), &out, &source.env())
        .await
        .expect("export from PostgreSQL");
    assert_eq!(exported.source_engine, SourceEngine::Postgres);
    assert!(exported.rows > 0);

    let pg_users: Vec<String> =
        sqlx::query_scalar("SELECT username FROM app_user ORDER BY username")
            .fetch_all(pg)
            .await
            .expect("PostgreSQL users");
    assert_eq!(pg_users, ["admin", "claire"]);

    let target = Instance::new("pg-dst");
    let imported = import_archive(&out, &target.db(), false, &target.env())
        .await
        .expect("import into SQLite");
    assert_eq!(imported.rows, exported.rows);
    assert_eq!(target.usernames(), pg_users);
    assert_eq!(std::fs::read(target.crypto_key()).expect("key"), CRYPTO_KEY);
}

fn datasource(db: &TestDatabase) -> Datasource {
    Datasource::parse_jdbc(
        &format!("jdbc:postgresql://{DB_HOST}:{DB_PORT}/{}", db.name()),
        DB_USER,
        "",
    )
    .expect("datasource")
}

async fn connect(db: &TestDatabase) -> PgPool {
    picsou::db::connect(&datasource(db))
        .await
        .expect("connect to the test database")
}

async fn pg_usernames(pool: &PgPool) -> Vec<String> {
    sqlx::query_scalar("SELECT username FROM app_user ORDER BY username")
        .fetch_all(pool)
        .await
        .expect("PostgreSQL users")
}

async fn import_pg(
    archive: &Path,
    db: &TestDatabase,
    replace: bool,
    target: &Instance,
) -> Result<archive::ImportReport, ArchiveError> {
    import_archive_into(
        archive,
        ImportTarget::Postgres(&datasource(db)),
        replace,
        &target.env(),
    )
    .await
}

/// A migrated PostgreSQL database holding one member and one user.
async fn seed_pg_user(pool: &PgPool, name: &str) {
    let member: i64 =
        sqlx::query_scalar("INSERT INTO family_member (display_name) VALUES ($1) RETURNING id")
            .bind(name)
            .fetch_one(pool)
            .await
            .expect("member");
    sqlx::query(
        "INSERT INTO app_user (username, password_hash, member_id) VALUES ($1, 'hash', $2)",
    )
    .bind(name)
    .bind(member)
    .execute(pool)
    .await
    .expect("user");
}

fn no_scratch_left(instance: &Instance) -> bool {
    std::fs::read_dir(&instance.root)
        .expect("read dir")
        .filter_map(Result::ok)
        .all(|e| {
            !e.file_name()
                .to_string_lossy()
                .starts_with(".picsou-archive-")
        })
}

#[tokio::test]
async fn a_sqlite_instance_round_trips_into_an_empty_postgres() {
    let source = Instance::new("sq-pg");
    source.write_secrets(CRYPTO_KEY);
    source.seed_sqlite(&["alice", "bob"]);
    SqliteDb::open(&source.db())
        .expect("open")
        .with_conn(|conn| {
            // Row 1 references row 2: it is read first, so the self reference is set after the load.
            conn.execute_batch(
                "INSERT INTO account (id, name, member_id, current_balance, is_manual, created_at)
                   VALUES (2, 'parent', 1, '1234.56780000', 1, '2026-01-02T03:04:05.000000Z');
                 INSERT INTO account (id, name, member_id, current_balance, is_manual, created_at, parent_account_id)
                   VALUES (1, 'child', 1, '10.50000000', 0, '2026-03-04T05:06:07.123456Z', 2);
                 INSERT INTO instrument_logo (ticker, status, image, content_type, attempted_at, fetched_at)
                   VALUES ('CW8', 'STORED', x'89504e470d0a1a0a00ff', 'image/png',
                           '2026-02-03T04:05:06.000000Z', '2026-02-03T04:05:06.000000Z');",
            )?;
            Ok(())
        })
        .expect("seed accounts and a logo");
    let out = source.root.join("out.picsou-archive");
    let exported = export_sqlite(&source, &out).await;

    let db = fresh_database().await;
    let pool = connect(&db).await;
    let target = Instance::new("pg-dst");
    let imported = import_pg(&out, &db, false, &target)
        .await
        .expect("import into an empty PostgreSQL");

    assert_eq!(imported.rows, exported.rows);
    assert_eq!(imported.backup_dir, None);
    assert_eq!(pg_usernames(&pool).await, ["alice", "bob"]);
    let accounts: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM account")
        .fetch_one(&pool)
        .await
        .expect("count");
    assert_eq!(accounts, 2);
    let (balance, manual): (String, bool) =
        sqlx::query_as("SELECT current_balance::text, is_manual FROM account WHERE id = 2")
            .fetch_one(&pool)
            .await
            .expect("parent account");
    assert_eq!((balance.as_str(), manual), ("1234.56780000", true));
    let (parent, created): (Option<i64>, chrono::DateTime<chrono::Utc>) =
        sqlx::query_as("SELECT parent_account_id, created_at FROM account WHERE id = 1")
            .fetch_one(&pool)
            .await
            .expect("child account");
    assert_eq!(parent, Some(2));
    assert_eq!(
        created.to_rfc3339_opts(chrono::SecondsFormat::Micros, true),
        "2026-03-04T05:06:07.123456Z"
    );
    let logo: Vec<u8> =
        sqlx::query_scalar("SELECT image FROM instrument_logo WHERE ticker = 'CW8'")
            .fetch_one(&pool)
            .await
            .expect("logo");
    assert_eq!(
        logo,
        [0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0xff]
    );

    let next_member: i64 =
        sqlx::query_scalar("INSERT INTO family_member (display_name) VALUES ('new') RETURNING id")
            .fetch_one(&pool)
            .await
            .expect("a new member");
    let next_account: i64 =
        sqlx::query_scalar("INSERT INTO account (name, member_id) VALUES ('new', $1) RETURNING id")
            .bind(next_member)
            .fetch_one(&pool)
            .await
            .expect("a new account");
    assert_eq!(
        next_member, 3,
        "the member sequence follows the imported ids"
    );
    assert_eq!(
        next_account, 3,
        "the account sequence follows the imported ids"
    );

    assert_eq!(std::fs::read(target.crypto_key()).expect("key"), CRYPTO_KEY);
    assert_eq!(mode(&target.crypto_key()), 0o600);
    assert!(no_scratch_left(&target));
}

#[tokio::test]
async fn a_postgres_instance_replaces_another_one_only_with_replace() {
    let app = TestApp::builder().postgres().with_admin().spawn().await;
    let pg = app.state.db.expect_pg();
    app.create_member("claire", "strong-pass-123").await;
    let source = Instance::new("pg-pg-src");
    source.write_secrets(CRYPTO_KEY);
    let out = source.root.join("out.picsou-archive");
    let exported = archive::export_archive(ExportSource::Postgres(pg), &out, &source.env())
        .await
        .expect("export from PostgreSQL");
    let source_users = pg_usernames(pg).await;

    let db = fresh_database().await;
    let pool = connect(&db).await;
    seed_pg_user(&pool, "carol").await;
    let target = Instance::new("pg-pg-dst");
    target.write_secrets(b"the-target-own-crypto-key-0123456");

    let err = import_pg(&out, &db, false, &target)
        .await
        .expect_err("a target with users");
    assert!(matches!(err, ArchiveError::DatabaseNotEmpty), "{err}");
    assert_eq!(pg_usernames(&pool).await, ["carol"]);
    assert_eq!(
        std::fs::read(target.crypto_key()).expect("key"),
        b"the-target-own-crypto-key-0123456"
    );
    assert!(backups(&target).is_empty());

    let imported = import_pg(&out, &db, true, &target)
        .await
        .expect("import with --replace");
    assert_eq!(imported.rows, exported.rows);
    assert_eq!(pg_usernames(&pool).await, source_users);
    assert_eq!(std::fs::read(target.crypto_key()).expect("key"), CRYPTO_KEY);
    let dir = imported.backup_dir.expect("a backup directory");
    assert_eq!(
        std::fs::read(dir.join("crypto_key")).expect("old key"),
        b"the-target-own-crypto-key-0123456"
    );
    let history: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM flyway_schema_history")
        .fetch_one(&pool)
        .await
        .expect("history");
    assert!(history > 0, "the migration history is not touched");
    assert!(no_scratch_left(&target));
}

#[tokio::test]
async fn a_failed_load_into_postgres_rolls_back_rows_and_key() {
    let source = Instance::new("pg-bad-src");
    source.write_secrets(CRYPTO_KEY);
    source.seed_sqlite(&["alice"]);
    let good = source.root.join("good.picsou-archive");
    export_sqlite(&source, &good).await;
    let sealed = std::fs::read(&good).expect("read the archive");
    let zip = archive::open(&sealed, PASSPHRASE).expect("open");
    let mut contents = archive::unpack(&zip).expect("unpack");
    contents
        .manifest
        .table_counts
        .insert("app_user".to_owned(), 99);
    let bad = source.root.join("bad.picsou-archive");
    std::fs::write(
        &bad,
        archive::seal(&archive::pack(&contents).expect("pack"), PASSPHRASE).expect("seal"),
    )
    .expect("write");

    let db = fresh_database().await;
    let pool = connect(&db).await;
    seed_pg_user(&pool, "carol").await;
    let member_before: i64 = sqlx::query_scalar("SELECT MAX(id) FROM family_member")
        .fetch_one(&pool)
        .await
        .expect("max id");
    let target = Instance::new("pg-bad-dst");
    target.write_secrets(b"the-target-own-crypto-key-0123456");

    let err = import_pg(&bad, &db, true, &target)
        .await
        .expect_err("a count mismatch");
    assert!(matches!(err, ArchiveError::Verification(_)), "{err}");
    assert!(err.to_string().contains("app_user"), "{err}");
    assert_eq!(pg_usernames(&pool).await, ["carol"]);
    let member_after: i64 = sqlx::query_scalar("SELECT MAX(id) FROM family_member")
        .fetch_one(&pool)
        .await
        .expect("max id");
    assert_eq!(member_after, member_before);
    assert_eq!(
        std::fs::read(target.crypto_key()).expect("key"),
        b"the-target-own-crypto-key-0123456"
    );
    assert!(backups(&target).is_empty());
    assert!(no_scratch_left(&target));
}
