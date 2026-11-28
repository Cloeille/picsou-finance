//! Encrypted portable archive of a Picsou instance (`picsou export-archive <out>` and
//! `picsou import-archive <in> [--replace]`), the way to move data between the Docker and the
//! desktop install (issue #865, slice 1).
//!
//! The archive is a zip (`manifest.json`, `picsou.db`, `secrets/crypto_key` and, when the
//! instance has one, `keys/enablebanking-private.pem`) sealed as a whole:
//! `b"PICSOUA1" | salt (32) | nonce (12) | AES-256-GCM(zip)`, the key being
//! PBKDF2-HMAC-SHA256(passphrase, salt, 600 000). The JWT secret, the sidecar key, the PostgreSQL
//! password, logs and backups never enter it.
//!
//! Messages carry paths, table names and counts only: never the passphrase, a key or SQL text.

use std::collections::BTreeMap;
use std::io::{Cursor, Read, Write};
use std::path::{Path, PathBuf};

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use rand::RngExt;
use rusqlite::{Connection, OpenFlags};
use serde::{Deserialize, Serialize};
use sqlx::{Connection as _, PgConnection, PgPool};

use super::sqlite::export_pg::{ExportError, export_pg_to_sqlite};
use super::sqlite::migrate::{HISTORY_TABLE, migrations};
use super::sqlite::{SqliteDb, SqliteError};
use super::{archive_pg, connect_options, flyway, schema};
use crate::clock::{Clock, SystemClock};
use crate::config::{Datasource, Env};

pub const FORMAT_VERSION: u32 = 1;
pub const PASSPHRASE_VARIABLE: &str = "PICSOU_ARCHIVE_PASSPHRASE";
pub const MIN_PASSPHRASE_CHARS: usize = 12;

const MAGIC: &[u8; 8] = b"PICSOUA1";
const SALT_BYTES: usize = 32;
const NONCE_BYTES: usize = 12;
const TAG_BYTES: usize = 16;
const PBKDF2_ITERATIONS: u32 = 600_000;
const MANIFEST_ENTRY: &str = "manifest.json";
const DB_ENTRY: &str = "picsou.db";
const CRYPTO_KEY_ENTRY: &str = "secrets/crypto_key";
const ENABLEBANKING_ENTRY: &str = "keys/enablebanking-private.pem";
const MAX_MANIFEST_BYTES: u64 = 1 << 20;
/// The whole archive is held in memory, so one entry is capped well below AES-GCM's limit.
const MAX_ENTRY_BYTES: u64 = 1 << 30;
const DEFAULT_ENABLEBANKING_KEY_PATH: &str = "/data/keys/enablebanking-private.pem";

#[derive(Debug, thiserror::Error)]
pub enum ArchiveError {
    #[error("{PASSPHRASE_VARIABLE} is required")]
    PassphraseMissing,
    #[error("{PASSPHRASE_VARIABLE} must have at least {MIN_PASSPHRASE_CHARS} characters")]
    PassphraseTooShort,
    #[error("output file already exists: {0}")]
    OutputExists(PathBuf),
    #[error(
        "no encryption key to archive: set CRYPTO_ENCRYPTION_KEY or put crypto_key in PICSOU_SECRETS_DIR"
    )]
    NoCryptoKey,
    #[error("SQLite database file does not exist: {0}")]
    SourceMissing(PathBuf),
    #[error("not a Picsou archive")]
    NotAnArchive,
    #[error("wrong passphrase or corrupted archive")]
    Decrypt,
    #[error("corrupted archive: {0}")]
    Corrupt(String),
    #[error(
        "archive format {0} is newer than this binary supports ({FORMAT_VERSION}): update Picsou"
    )]
    NewerFormat(u32),
    #[error(
        "archive has SQLite migration V{0}, which this binary does not know: update Picsou first"
    )]
    NewerSchema(String),
    #[error("target database already holds data: {0} (pass --replace to move it to backups/)")]
    TargetNotEmpty(PathBuf),
    #[error(
        "target database already holds data (pass --replace to overwrite it; PostgreSQL data is not backed up, only the key files move to backups/)"
    )]
    DatabaseNotEmpty,
    #[error(
        "no place for the encryption key: set APP_CRYPTO_KEY_PATH or PICSOU_SECRETS_DIR on the target"
    )]
    NoKeyDestination,
    #[error(
        "CRYPTO_ENCRYPTION_KEY is set on the target and differs from the archived key: the server would ignore the imported key, unset it first"
    )]
    KeyOverridden,
    #[error("imported database failed verification: {0}")]
    Verification(String),
    #[error("{context}: {source}")]
    Io {
        context: &'static str,
        source: std::io::Error,
    },
    #[error("SQLite error: {0}")]
    Sqlite(#[from] SqliteError),
    #[error("SQLite driver error: {0}")]
    Rusqlite(#[from] rusqlite::Error),
    #[error("PostgreSQL export failed: {0}")]
    Export(#[from] ExportError),
    /// The driver error is kept for its SQLSTATE only: its text can quote a value.
    #[error("PostgreSQL import failed on [{table}]")]
    Import { table: String, source: sqlx::Error },
    #[error("a value of [{table}.{column}] cannot be converted for PostgreSQL")]
    Convert { table: String, column: String },
    #[error("PostgreSQL migration failed: {0}")]
    Migrate(#[from] flyway::MigrationError),
    #[error(
        "no data folder to stage an import in: use a file-backed SQLite database or set PICSOU_SECRETS_DIR"
    )]
    StagingUnavailable,
}

impl ArchiveError {
    /// A stable machine-readable code for the HTTP API and the status of a staged import. It
    /// never carries a path, a table name or any other detail of the instance.
    pub fn code(&self) -> &'static str {
        match self {
            Self::PassphraseMissing => "PASSPHRASE_REQUIRED",
            Self::PassphraseTooShort => "PASSPHRASE_TOO_SHORT",
            Self::NotAnArchive => "NOT_AN_ARCHIVE",
            Self::Decrypt => "WRONG_PASSPHRASE",
            Self::Corrupt(_) => "CORRUPT_ARCHIVE",
            Self::NewerFormat(_) | Self::NewerSchema(_) => "ARCHIVE_TOO_NEW",
            Self::KeyOverridden => "KEY_OVERRIDDEN",
            Self::NoKeyDestination | Self::StagingUnavailable => "IMPORT_UNAVAILABLE",
            Self::TargetNotEmpty(_) | Self::DatabaseNotEmpty => "TARGET_NOT_EMPTY",
            Self::Verification(_) => "VERIFICATION_FAILED",
            _ => "ARCHIVE_FAILED",
        }
    }
}

pub(super) fn io_err(context: &'static str) -> impl FnOnce(std::io::Error) -> ArchiveError {
    move |source| ArchiveError::Io { context, source }
}

fn corrupt(reason: impl Into<String>) -> ArchiveError {
    ArchiveError::Corrupt(reason.into())
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SourceEngine {
    Postgres,
    Sqlite,
}

impl SourceEngine {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Postgres => "postgres",
            Self::Sqlite => "sqlite",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Manifest {
    pub format_version: u32,
    pub app_version: String,
    pub source_engine: SourceEngine,
    /// Latest SQLite migration applied to the archived database.
    pub sqlite_migration: String,
    pub table_counts: BTreeMap<String, u64>,
    pub created_at: String,
}

/// What an archive holds once opened. Key material is zeroed on drop.
#[derive(Debug)]
pub struct Contents {
    pub manifest: Manifest,
    pub database: Vec<u8>,
    pub crypto_key: Vec<u8>,
    pub enablebanking_key: Option<Vec<u8>>,
}

impl Drop for Contents {
    fn drop(&mut self) {
        self.crypto_key.fill(0);
        if let Some(key) = self.enablebanking_key.as_mut() {
            key.fill(0);
        }
    }
}

#[derive(Debug, Clone)]
pub struct ExportReport {
    pub source_engine: SourceEngine,
    pub tables: usize,
    pub rows: u64,
    pub enablebanking_key: bool,
}

#[derive(Debug, Clone)]
pub struct ImportReport {
    pub tables: usize,
    pub rows: u64,
    pub backup_dir: Option<PathBuf>,
    pub enablebanking_key: bool,
}

pub enum ExportSource<'a> {
    Postgres(&'a PgPool),
    Sqlite(&'a Path),
}

/// A passphrase is required and has at least 12 characters.
pub fn check_passphrase(value: &str) -> Result<(), ArchiveError> {
    if value.is_empty() {
        return Err(ArchiveError::PassphraseMissing);
    }
    if value.chars().count() < MIN_PASSPHRASE_CHARS {
        return Err(ArchiveError::PassphraseTooShort);
    }
    Ok(())
}

/// Reads the passphrase of the command line from `PICSOU_ARCHIVE_PASSPHRASE`.
pub fn passphrase(env: &Env) -> Result<String, ArchiveError> {
    let value = env
        .raw(PASSPHRASE_VARIABLE)
        .ok_or(ArchiveError::PassphraseMissing)?;
    check_passphrase(value)?;
    Ok(value.to_owned())
}

fn derive_cipher(passphrase: &str, salt: &[u8]) -> Result<Aes256Gcm, ArchiveError> {
    let mut key = [0u8; 32];
    pbkdf2::pbkdf2_hmac::<sha2::Sha256>(passphrase.as_bytes(), salt, PBKDF2_ITERATIONS, &mut key);
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|_| ArchiveError::Decrypt);
    key.fill(0);
    cipher
}

/// Seals `plain` under the passphrase: header (magic, salt, nonce), then the AES-GCM ciphertext,
/// with the header as associated data.
pub fn seal(plain: &[u8], passphrase: &str) -> Result<Vec<u8>, ArchiveError> {
    let mut header = Vec::with_capacity(MAGIC.len() + SALT_BYTES + NONCE_BYTES);
    header.extend_from_slice(MAGIC);
    let mut random = [0u8; SALT_BYTES + NONCE_BYTES];
    rand::rng().fill(&mut random[..]);
    header.extend_from_slice(&random);
    let cipher = derive_cipher(passphrase, &header[MAGIC.len()..MAGIC.len() + SALT_BYTES])?;
    let nonce = Nonce::try_from(&header[MAGIC.len() + SALT_BYTES..])
        .map_err(|_| corrupt("invalid nonce"))?;
    let ciphertext = cipher
        .encrypt(
            &nonce,
            Payload {
                msg: plain,
                aad: &header,
            },
        )
        .map_err(|_| corrupt("encryption failed"))?;
    header.extend_from_slice(&ciphertext);
    Ok(header)
}

/// Opens a sealed archive. A wrong passphrase and a modified byte are the same error.
pub fn open(file: &[u8], passphrase: &str) -> Result<Vec<u8>, ArchiveError> {
    let header_len = MAGIC.len() + SALT_BYTES + NONCE_BYTES;
    if file.len() < header_len || &file[..MAGIC.len()] != MAGIC {
        return Err(ArchiveError::NotAnArchive);
    }
    if file.len() < header_len + TAG_BYTES {
        return Err(ArchiveError::Decrypt);
    }
    let (header, ciphertext) = file.split_at(header_len);
    let cipher = derive_cipher(passphrase, &header[MAGIC.len()..MAGIC.len() + SALT_BYTES])?;
    let nonce =
        Nonce::try_from(&header[MAGIC.len() + SALT_BYTES..]).map_err(|_| ArchiveError::Decrypt)?;
    cipher
        .decrypt(
            &nonce,
            Payload {
                msg: ciphertext,
                aad: header,
            },
        )
        .map_err(|_| ArchiveError::Decrypt)
}

/// Builds the zip of an archive.
pub fn pack(contents: &Contents) -> Result<Vec<u8>, ArchiveError> {
    let manifest = serde_json::to_vec_pretty(&contents.manifest)
        .map_err(|_| corrupt("manifest cannot be written"))?;
    let mut entries: Vec<(&str, &[u8])> = vec![
        (MANIFEST_ENTRY, &manifest),
        (DB_ENTRY, &contents.database),
        (CRYPTO_KEY_ENTRY, &contents.crypto_key),
    ];
    if let Some(key) = &contents.enablebanking_key {
        entries.push((ENABLEBANKING_ENTRY, key));
    }
    let mut zip = zip::ZipWriter::new(Cursor::new(Vec::new()));
    let options = zip::write::SimpleFileOptions::default();
    for (name, bytes) in entries {
        zip.start_file(name, options)
            .map_err(|_| corrupt(format!("cannot add {name}")))?;
        zip.write_all(bytes)
            .map_err(io_err("cannot add an entry"))?;
    }
    let mut bytes = zip
        .finish()
        .map_err(|_| corrupt("cannot finish the zip"))?
        .into_inner();
    bytes.shrink_to_fit();
    Ok(bytes)
}

fn read_entry<R: Read + std::io::Seek>(
    zip: &mut zip::ZipArchive<R>,
    name: &str,
    limit: u64,
) -> Result<Option<Vec<u8>>, ArchiveError> {
    let entry = match zip.by_name(name) {
        Ok(entry) => entry,
        Err(zip::result::ZipError::FileNotFound) => return Ok(None),
        Err(_) => return Err(corrupt(format!("cannot read {name}"))),
    };
    let mut bytes = Vec::new();
    entry
        .take(limit + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| corrupt(format!("cannot read {name}")))?;
    if bytes.len() as u64 > limit {
        bytes.fill(0);
        return Err(corrupt(format!("{name} is too large")));
    }
    Ok(Some(bytes))
}

/// Parses the zip of an archive and checks the manifest (format and migration) before anything
/// else is looked at.
pub fn unpack(zip_bytes: &[u8]) -> Result<Contents, ArchiveError> {
    let mut zip = zip::ZipArchive::new(Cursor::new(zip_bytes))
        .map_err(|_| corrupt("the content is not a zip"))?;
    let manifest_bytes = read_entry(&mut zip, MANIFEST_ENTRY, MAX_MANIFEST_BYTES)?
        .ok_or_else(|| corrupt("manifest.json is missing"))?;
    let manifest = parse_manifest(&manifest_bytes)?;
    let mut required = |name: &str| {
        read_entry(&mut zip, name, MAX_ENTRY_BYTES)?
            .ok_or_else(|| corrupt(format!("{name} is missing")))
    };
    let database = required(DB_ENTRY)?;
    let crypto_key = required(CRYPTO_KEY_ENTRY)?;
    if crypto_key.is_empty() {
        return Err(corrupt("secrets/crypto_key is empty"));
    }
    let enablebanking_key = read_entry(&mut zip, ENABLEBANKING_ENTRY, MAX_ENTRY_BYTES)?;
    Ok(Contents {
        manifest,
        database,
        crypto_key,
        enablebanking_key,
    })
}

fn parse_manifest(bytes: &[u8]) -> Result<Manifest, ArchiveError> {
    let value: serde_json::Value =
        serde_json::from_slice(bytes).map_err(|_| corrupt("manifest.json is not valid JSON"))?;
    let version = value
        .get("format_version")
        .and_then(serde_json::Value::as_u64)
        .ok_or_else(|| corrupt("manifest.json has no format_version"))?;
    let version = u32::try_from(version).unwrap_or(u32::MAX);
    if version > FORMAT_VERSION {
        return Err(ArchiveError::NewerFormat(version));
    }
    let manifest: Manifest =
        serde_json::from_value(value).map_err(|_| corrupt("manifest.json is malformed"))?;
    if manifest.format_version != FORMAT_VERSION {
        return Err(corrupt("unsupported format_version"));
    }
    if !migrations()
        .iter()
        .any(|m| m.version == manifest.sqlite_migration)
    {
        return Err(ArchiveError::NewerSchema(manifest.sqlite_migration));
    }
    Ok(manifest)
}

pub(super) fn quote_ident(name: &str) -> String {
    format!("\"{}\"", name.replace('"', "\"\""))
}

/// Row count of every application table (the migration history is not one).
fn table_counts(conn: &Connection) -> Result<BTreeMap<String, u64>, rusqlite::Error> {
    let mut stmt = conn.prepare(
        "SELECT name FROM sqlite_master WHERE type = 'table' \
         AND substr(name, 1, 7) <> 'sqlite_' AND name <> ?1 ORDER BY name",
    )?;
    let names = stmt
        .query_map([HISTORY_TABLE], |r| r.get::<_, String>(0))?
        .collect::<Result<Vec<_>, _>>()?;
    names
        .into_iter()
        .map(|name| {
            let sql = format!("SELECT COUNT(*) FROM {}", quote_ident(&name));
            let count: i64 = conn.query_row(&sql, [], |r| r.get(0))?;
            Ok((name, u64::try_from(count).unwrap_or(0)))
        })
        .collect()
}

/// The highest migration version the history table records, by numeric order.
fn latest_migration(conn: &Connection) -> Result<String, ArchiveError> {
    let mut stmt = conn.prepare(&format!("SELECT version FROM {HISTORY_TABLE}"))?;
    let versions = stmt
        .query_map([], |r| r.get::<_, String>(0))?
        .collect::<Result<Vec<_>, _>>()?;
    versions
        .into_iter()
        .max_by_key(|v| v.parse::<u64>().unwrap_or(0))
        .ok_or_else(|| corrupt("the database has no migration history"))
}

fn open_read_only(path: &Path) -> Result<Connection, rusqlite::Error> {
    Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY)
}

#[cfg(unix)]
pub(super) fn set_mode(path: &Path, mode: u32) {
    use std::os::unix::fs::PermissionsExt;
    let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode));
}

#[cfg(not(unix))]
pub(super) fn set_mode(_path: &Path, _mode: u32) {}

/// Creates a new file readable by its owner only; never overwrites.
pub(super) fn write_private(path: &Path, bytes: &[u8]) -> Result<(), ArchiveError> {
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(path).map_err(io_err("cannot write a file"))?;
    file.write_all(bytes)
        .and_then(|()| file.sync_all())
        .map_err(io_err("cannot write a file"))
}

pub(super) fn ensure_dir(dir: &Path) -> Result<(), ArchiveError> {
    if dir.as_os_str().is_empty() || dir.exists() {
        return Ok(());
    }
    std::fs::create_dir_all(dir).map_err(io_err("cannot create a directory"))?;
    set_mode(dir, 0o700);
    Ok(())
}

/// A private scratch directory beside the output, removed on every path.
pub struct Scratch(PathBuf);

impl Scratch {
    pub fn path(&self) -> &Path {
        &self.0
    }

    pub fn create(parent: &Path) -> Result<Self, ArchiveError> {
        let name = format!(
            ".picsou-archive-{}-{:016x}",
            std::process::id(),
            rand::rng().random::<u64>()
        );
        let dir = parent.join(name);
        std::fs::create_dir_all(&dir).map_err(io_err("cannot create a scratch directory"))?;
        set_mode(&dir, 0o700);
        Ok(Self(dir))
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn crypto_key_for_export(env: &Env) -> Result<Vec<u8>, ArchiveError> {
    if let Some(key) = env.raw("CRYPTO_ENCRYPTION_KEY").filter(|v| !v.is_empty()) {
        return Ok(key.as_bytes().to_vec());
    }
    let dir = env
        .raw("PICSOU_SECRETS_DIR")
        .filter(|v| !v.is_empty())
        .ok_or(ArchiveError::NoCryptoKey)?;
    match std::fs::read(Path::new(dir).join("crypto_key")) {
        Ok(key) if !key.is_empty() => Ok(key),
        _ => Err(ArchiveError::NoCryptoKey),
    }
}

/// The secret loader reads a key file without its trailing newlines.
fn trim_newline(bytes: &[u8]) -> &[u8] {
    let end = bytes.iter().rposition(|b| *b != b'\n').map_or(0, |i| i + 1);
    &bytes[..end]
}

fn enablebanking_key_path(env: &Env) -> PathBuf {
    PathBuf::from(
        env.raw("ENABLEBANKING_PRIVATE_KEY_PATH")
            .filter(|v| !v.is_empty())
            .unwrap_or(DEFAULT_ENABLEBANKING_KEY_PATH),
    )
}

/// Writes a sealed archive of the instance to `output`, which must not exist.
pub async fn export_archive(
    source: ExportSource<'_>,
    output: &Path,
    passphrase: &str,
    env: &Env,
) -> Result<ExportReport, ArchiveError> {
    check_passphrase(passphrase)?;
    if output.exists() {
        return Err(ArchiveError::OutputExists(output.to_path_buf()));
    }
    let crypto_key = crypto_key_for_export(env)?;
    let enablebanking_key = std::fs::read(enablebanking_key_path(env)).ok();
    let parent = output
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    ensure_dir(parent)?;
    let scratch = Scratch::create(parent)?;
    let snapshot = scratch.0.join(DB_ENTRY);
    let source_engine = match source {
        ExportSource::Postgres(pool) => {
            export_pg_to_sqlite(pool, &snapshot).await?;
            SourceEngine::Postgres
        }
        ExportSource::Sqlite(path) => {
            snapshot_sqlite(path, &snapshot).await?;
            SourceEngine::Sqlite
        }
    };
    let (sqlite_migration, table_counts) = {
        let conn = open_read_only(&snapshot)?;
        (latest_migration(&conn)?, table_counts(&conn)?)
    };
    if !migrations().iter().any(|m| m.version == sqlite_migration) {
        return Err(ArchiveError::NewerSchema(sqlite_migration));
    }
    let report = ExportReport {
        source_engine,
        tables: table_counts.len(),
        rows: table_counts.values().sum(),
        enablebanking_key: enablebanking_key.is_some(),
    };
    let contents = Contents {
        manifest: Manifest {
            format_version: FORMAT_VERSION,
            app_version: env
                .raw("APP_VERSION")
                .filter(|v| !v.is_empty())
                .unwrap_or(env!("CARGO_PKG_VERSION"))
                .to_owned(),
            source_engine,
            sqlite_migration,
            table_counts,
            created_at: SystemClock.now().to_rfc3339(),
        },
        database: std::fs::read(&snapshot).map_err(io_err("cannot read the snapshot"))?,
        crypto_key,
        enablebanking_key,
    };
    let mut zip = pack(&contents)?;
    let sealed = seal(&zip, passphrase);
    zip.fill(0);
    let sealed = sealed?;
    let staged = scratch.0.join("archive");
    write_private(&staged, &sealed)?;
    if output.exists() {
        return Err(ArchiveError::OutputExists(output.to_path_buf()));
    }
    std::fs::rename(&staged, output).map_err(io_err("cannot move the archive into place"))?;
    Ok(report)
}

/// A consistent copy of a live SQLite database (`VACUUM INTO`).
async fn snapshot_sqlite(source: &Path, snapshot: &Path) -> Result<(), ArchiveError> {
    if !source.exists() {
        return Err(ArchiveError::SourceMissing(source.to_path_buf()));
    }
    let target = snapshot
        .to_str()
        .ok_or_else(|| corrupt("the scratch path is not valid UTF-8"))?
        .to_owned();
    let db = SqliteDb::open(source)?;
    let copied = db.with_conn(|conn| {
        conn.execute("VACUUM INTO ?1", [&target])?;
        Ok(())
    });
    db.close().await;
    copied?;
    set_mode(snapshot, 0o600);
    Ok(())
}

fn sidecars(db: &Path) -> impl Iterator<Item = PathBuf> + '_ {
    ["", "-wal", "-shm"].into_iter().map(|suffix| {
        let mut name = db.as_os_str().to_os_string();
        name.push(suffix);
        PathBuf::from(name)
    })
}

fn move_file(from: &Path, to: &Path) -> std::io::Result<()> {
    if std::fs::rename(from, to).is_ok() {
        return Ok(());
    }
    std::fs::copy(from, to)?;
    set_mode(to, 0o600);
    std::fs::remove_file(from)
}

/// Files moved aside and files placed by an import, so a failed import can be undone.
#[derive(Default)]
struct Placement {
    moved: Vec<(PathBuf, PathBuf)>,
    placed: Vec<PathBuf>,
    backup_dir: Option<PathBuf>,
    backup_root: PathBuf,
}

impl Placement {
    fn move_aside(&mut self, original: &Path) -> Result<(), ArchiveError> {
        if !original.exists() {
            return Ok(());
        }
        let dir = match &self.backup_dir {
            Some(dir) => dir.clone(),
            None => {
                ensure_dir(&self.backup_root)?;
                let secs = SystemClock.now().timestamp();
                let mut dir = self.backup_root.join(format!("{secs}-before-import"));
                let mut attempt = 1;
                while dir.exists() {
                    attempt += 1;
                    dir = self
                        .backup_root
                        .join(format!("{secs}-before-import-{attempt}"));
                }
                std::fs::create_dir(&dir).map_err(io_err("cannot create the backup directory"))?;
                set_mode(&dir, 0o700);
                self.backup_dir = Some(dir.clone());
                dir
            }
        };
        let name = original.file_name().unwrap_or_default();
        let mut backup = dir.join(name);
        if backup.exists() {
            let mut renamed = name.to_os_string();
            renamed.push(".2");
            backup = dir.join(renamed);
        }
        move_file(original, &backup).map_err(io_err("cannot move a file to backups"))?;
        set_mode(&backup, 0o600);
        self.moved.push((original.to_path_buf(), backup));
        Ok(())
    }

    fn place(&mut self, dest: &Path, bytes: &[u8]) -> Result<(), ArchiveError> {
        if let Some(parent) = dest.parent() {
            ensure_dir(parent)?;
        }
        write_private(dest, bytes)?;
        self.placed.push(dest.to_path_buf());
        Ok(())
    }

    fn rollback(&mut self) {
        for path in self.placed.drain(..).rev() {
            let _ = std::fs::remove_file(path);
        }
        for (original, backup) in self.moved.drain(..).rev() {
            let _ = move_file(&backup, &original);
        }
        if let Some(dir) = self.backup_dir.take() {
            let _ = std::fs::remove_dir(dir);
        }
    }
}

/// Whether the SQLite file at `path` holds an `app_user` row. A file that cannot be read counts
/// as holding data: it is not ours to overwrite without `--replace`.
fn holds_data(path: &Path) -> bool {
    if !path.exists() {
        return false;
    }
    let Ok(conn) = open_read_only(path) else {
        return true;
    };
    let table = conn.query_row(
        "SELECT EXISTS (SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'app_user')",
        [],
        |r| r.get::<_, bool>(0),
    );
    match table {
        Ok(false) => false,
        Ok(true) => conn
            .query_row("SELECT EXISTS (SELECT 1 FROM app_user)", [], |r| {
                r.get::<_, bool>(0)
            })
            .unwrap_or(true),
        Err(_) => true,
    }
}

/// Opens the placed database like the server, brings it to this binary's schema and compares
/// every archived table's row count to the manifest.
async fn verify_placed(db_path: &Path, manifest: &Manifest) -> Result<u64, ArchiveError> {
    let db = SqliteDb::open(db_path)?;
    let outcome = check_placed(&db, manifest);
    db.close().await;
    outcome
}

fn check_placed(db: &SqliteDb, manifest: &Manifest) -> Result<u64, ArchiveError> {
    check_database(db)?;
    let counts = db.with_conn(|conn| Ok(table_counts(conn)?))?;
    for (table, expected) in &manifest.table_counts {
        let actual = counts.get(table).copied().unwrap_or(0);
        if actual != *expected {
            return Err(ArchiveError::Verification(format!(
                "row count mismatch on table [{table}]: archive had {expected}, database has {actual}"
            )));
        }
    }
    Ok(manifest.table_counts.values().sum())
}

fn check_database(db: &SqliteDb) -> Result<(), ArchiveError> {
    db.migrate()?;
    db.verify_schema()?;
    let integrity = db.check_integrity()?;
    if !integrity.integrity_ok {
        return Err(ArchiveError::Verification("integrity_check failed".into()));
    }
    if !integrity.foreign_key_violations.is_empty() {
        return Err(ArchiveError::Verification(format!(
            "foreign_key_check reports {} violation(s)",
            integrity.foreign_key_violations.len()
        )));
    }
    Ok(())
}

/// Where an archive is restored.
#[derive(Clone, Copy)]
pub enum ImportTarget<'a> {
    Sqlite(&'a Path),
    Postgres(&'a Datasource),
}

/// An archive that passed every check that needs no access to the target's database.
struct Prepared {
    contents: Contents,
    key_dest: PathBuf,
    enablebanking_dest: Option<PathBuf>,
}

impl Prepared {
    /// Decrypts and parses the archive, then refuses a key the server would ignore.
    fn open(archive: &Path, passphrase: &str, env: &Env) -> Result<Self, ArchiveError> {
        let file = std::fs::read(archive).map_err(io_err("cannot read the archive"))?;
        let (mut zip, prepared) = Self::open_keeping_zip(&file, passphrase, env)?;
        zip.fill(0);
        Ok(prepared)
    }

    /// As [`Prepared::open`] on the bytes of a sealed archive, also returning the plain zip: the
    /// caller must zero it.
    fn open_keeping_zip(
        file: &[u8],
        passphrase: &str,
        env: &Env,
    ) -> Result<(Vec<u8>, Self), ArchiveError> {
        check_passphrase(passphrase)?;
        let mut zip = open(file, passphrase)?;
        match Self::from_zip(&zip, env) {
            Ok(prepared) => Ok((zip, prepared)),
            Err(err) => {
                zip.fill(0);
                Err(err)
            }
        }
    }

    fn from_zip(zip: &[u8], env: &Env) -> Result<Self, ArchiveError> {
        Self::from_contents(unpack(zip)?, env)
    }

    fn from_contents(contents: Contents, env: &Env) -> Result<Self, ArchiveError> {
        let key_dest = env
            .raw("APP_CRYPTO_KEY_PATH")
            .filter(|v| !v.is_empty())
            .map(PathBuf::from)
            .or_else(|| secrets_dir(env).map(|dir| dir.join("crypto_key")))
            .ok_or(ArchiveError::NoKeyDestination)?;
        if env
            .raw("CRYPTO_ENCRYPTION_KEY")
            .filter(|v| !v.is_empty())
            .is_some_and(|key| key.as_bytes() != trim_newline(&contents.crypto_key))
        {
            return Err(ArchiveError::KeyOverridden);
        }
        let enablebanking_dest = contents
            .enablebanking_key
            .is_some()
            .then(|| enablebanking_key_path(env));
        Ok(Self {
            contents,
            key_dest,
            enablebanking_dest,
        })
    }

    /// Moves the target's keys to backups/ and places the archived ones.
    fn place_keys(&self, placement: &mut Placement) -> Result<(), ArchiveError> {
        placement.move_aside(&self.key_dest)?;
        if let Some(dest) = &self.enablebanking_dest {
            placement.move_aside(dest)?;
        }
        placement.place(&self.key_dest, &self.contents.crypto_key)?;
        if let (Some(dest), Some(key)) =
            (&self.enablebanking_dest, &self.contents.enablebanking_key)
        {
            placement.place(dest, key)?;
        }
        Ok(())
    }

    fn report(&self, rows: u64, placement: &Placement) -> ImportReport {
        ImportReport {
            tables: self.contents.manifest.table_counts.len(),
            rows,
            backup_dir: placement.backup_dir.clone(),
            enablebanking_key: self.enablebanking_dest.is_some(),
        }
    }
}

fn secrets_dir(env: &Env) -> Option<PathBuf> {
    env.raw("PICSOU_SECRETS_DIR")
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
}

/// Restores an archive into the SQLite database file `db_path` (see [`import_archive_into`]).
pub async fn import_archive(
    archive: &Path,
    db_path: &Path,
    passphrase: &str,
    replace: bool,
    env: &Env,
) -> Result<ImportReport, ArchiveError> {
    import_archive_into(
        archive,
        ImportTarget::Sqlite(db_path),
        passphrase,
        replace,
        env,
    )
    .await
}

/// Restores an archive into a SQLite file or a PostgreSQL database.
///
/// Nothing is touched before the passphrase, the format, the migration and the key are accepted.
/// A target that holds users is refused unless `replace`. The target's `jwt_secret` is left alone.
///
/// SQLite: the old database and keys move to `<db dir>/backups/<unix-secs>-before-import/`, and
/// come back if the imported database fails verification.
///
/// PostgreSQL: the archived database is extracted to a scratch directory, brought to this
/// binary's SQLite level and checked, the target is migrated with Flyway, then every table is
/// reloaded in one transaction. The keys are placed first (the old ones go to `backups/` under
/// the parent of `PICSOU_SECRETS_DIR`) and put back when the transaction rolls back: restoring
/// local files cannot fail halfway the way a commit followed by a failed key write would, which
/// would leave data the key cannot decrypt. The PostgreSQL data itself has no backup copy.
pub async fn import_archive_into(
    archive: &Path,
    target: ImportTarget<'_>,
    passphrase: &str,
    replace: bool,
    env: &Env,
) -> Result<ImportReport, ArchiveError> {
    let prepared = Prepared::open(archive, passphrase, env)?;
    place(&prepared, target, replace, env).await
}

async fn place(
    prepared: &Prepared,
    target: ImportTarget<'_>,
    replace: bool,
    env: &Env,
) -> Result<ImportReport, ArchiveError> {
    match target {
        ImportTarget::Sqlite(db_path) => import_sqlite(prepared, db_path, replace).await,
        ImportTarget::Postgres(datasource) => {
            import_postgres(prepared, datasource, replace, env).await
        }
    }
}

/// What a staged import will bring, known before anything is placed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StagedSummary {
    pub tables: usize,
    pub rows: u64,
}

/// What became of the import staged by the previous run, as `/api/setup/status` reports it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ImportOutcome {
    Done { tables: usize, rows: u64 },
    Failed { code: &'static str },
}

/// The folder (inside the data folder) holding an import waiting for the next start.
pub const STAGING_DIR: &str = "import-pending";
const STAGED_ARCHIVE: &str = "archive.zip";

/// Checks everything about an uploaded archive that needs no access to the target's database
/// (passphrase, format, migration, key, the archived database itself) and stages it for the next
/// start: the decrypted zip goes to `<data dir>/import-pending/archive.zip`, in a private folder
/// built beside it and renamed into place, so a crash never leaves a half-staged import. It
/// replaces an import staged earlier and never touches the live data. The passphrase is not kept.
pub async fn stage_import(
    data_dir: &Path,
    file: Vec<u8>,
    passphrase: String,
    env: &Env,
) -> Result<StagedSummary, ArchiveError> {
    let blocking_env = env.clone();
    let (mut zip, prepared) = tokio::task::spawn_blocking(move || {
        Prepared::open_keeping_zip(&file, &passphrase, &blocking_env)
    })
    .await
    .map_err(|_| corrupt("the archive check was interrupted"))??;
    let summary = StagedSummary {
        tables: prepared.contents.manifest.table_counts.len(),
        rows: prepared.contents.manifest.table_counts.values().sum(),
    };
    let staged = place_in_staging(data_dir, &prepared, &zip).await;
    zip.fill(0);
    staged?;
    Ok(summary)
}

async fn place_in_staging(
    data_dir: &Path,
    prepared: &Prepared,
    zip: &[u8],
) -> Result<(), ArchiveError> {
    ensure_dir(data_dir)?;
    {
        let check = Scratch::create(data_dir)?;
        let database = check.path().join(DB_ENTRY);
        write_private(&database, &prepared.contents.database)?;
        check_staged(&database).await.map_err(|err| match err {
            ArchiveError::Verification(message) => ArchiveError::Verification(message),
            _ => ArchiveError::Verification("the archived database cannot be opened".into()),
        })?;
    }
    let staging = Scratch::create(data_dir)?;
    write_private(&staging.path().join(STAGED_ARCHIVE), zip)?;
    let target = data_dir.join(STAGING_DIR);
    if target.exists() {
        std::fs::remove_dir_all(&target).map_err(io_err("cannot replace the staged import"))?;
    }
    std::fs::rename(staging.path(), &target).map_err(io_err("cannot stage the import"))
}

/// Applies the import staged by the previous run, if any, to the target `config` names, before
/// the server opens its database. The setup was empty when it was staged; the target is checked
/// again, so an account created since then makes the import fail (`TARGET_NOT_EMPTY`) instead
/// of overwriting data. On SQLite the old, empty files go to `backups/`; on PostgreSQL the key
/// files do.
///
/// Done: the staging folder is removed and the key just placed is the one to use.
/// Failed: the previous data stays (the import rolls back), the staging folder moves to
/// `backups/<unix-secs>-failed-import/` and the outcome carries a stable code.
/// An unreachable PostgreSQL is neither: the staging is kept and the start fails, so the next
/// start tries again.
pub async fn apply_pending(
    config: &crate::config::Config,
    now: chrono::DateTime<chrono::Utc>,
) -> Result<Option<ImportOutcome>, ArchiveError> {
    let Some(data_dir) = config.data_dir() else {
        return Ok(None);
    };
    let staging = data_dir.join(STAGING_DIR);
    if !staging.is_dir() {
        return Ok(None);
    }
    let target = match &config.database {
        crate::config::DatabaseConfig::Sqlite(crate::config::SqliteTarget::File(path)) => {
            ImportTarget::Sqlite(path)
        }
        crate::config::DatabaseConfig::Postgres(datasource) => ImportTarget::Postgres(datasource),
        crate::config::DatabaseConfig::Sqlite(crate::config::SqliteTarget::InMemory) => {
            return Ok(None);
        }
    };
    let mut zip = match std::fs::read(staging.join(STAGED_ARCHIVE)) {
        Ok(zip) => zip,
        Err(_) => {
            return Ok(Some(set_aside(
                &data_dir,
                &staging,
                now,
                corrupt("the staged import is unreadable").code(),
            )));
        }
    };
    let outcome = import_staged(&zip, target, config.archive_env.env()).await;
    zip.fill(0);
    match outcome {
        Ok(report) => {
            if let Err(error) = std::fs::remove_dir_all(&staging) {
                tracing::error!(
                    "the staged import was applied but its folder cannot be removed ({error}): remove {} before the next start",
                    staging.display()
                );
            }
            tracing::info!(
                "Applied the staged archive import: {} tables, {} rows",
                report.tables,
                report.rows
            );
            Ok(Some(ImportOutcome::Done {
                tables: report.tables,
                rows: report.rows,
            }))
        }
        Err(ArchiveError::Import { table, source }) if table == "<connection>" => {
            Err(ArchiveError::Import { table, source })
        }
        Err(error) => {
            tracing::error!("The staged archive import failed, the previous data is kept: {error}");
            Ok(Some(set_aside(&data_dir, &staging, now, error.code())))
        }
    }
}

/// Moves a staging folder that cannot be applied to `backups/`, or deletes it when that fails:
/// it must never be applied twice.
fn set_aside(
    data_dir: &Path,
    staging: &Path,
    now: chrono::DateTime<chrono::Utc>,
    code: &'static str,
) -> ImportOutcome {
    let backups = data_dir.join("backups");
    let moved = ensure_dir(&backups).is_ok() && {
        let secs = now.timestamp();
        let mut dest = backups.join(format!("{secs}-failed-import"));
        let mut attempt = 1;
        while dest.exists() {
            attempt += 1;
            dest = backups.join(format!("{secs}-failed-import-{attempt}"));
        }
        std::fs::rename(staging, &dest).is_ok()
    };
    if !moved {
        let _ = std::fs::remove_dir_all(staging);
    }
    ImportOutcome::Failed { code }
}

/// Opens a plain zip staged by [`stage_import`] and places it into a target without users.
async fn import_staged(
    zip: &[u8],
    target: ImportTarget<'_>,
    env: &Env,
) -> Result<ImportReport, ArchiveError> {
    let prepared = Prepared::from_zip(zip, env)?;
    place(&prepared, target, false, env).await
}

async fn import_sqlite(
    prepared: &Prepared,
    db_path: &Path,
    replace: bool,
) -> Result<ImportReport, ArchiveError> {
    if !replace && holds_data(db_path) {
        return Err(ArchiveError::TargetNotEmpty(db_path.to_path_buf()));
    }
    let mut placement = Placement {
        backup_root: db_path
            .parent()
            .filter(|p| !p.as_os_str().is_empty())
            .unwrap_or(Path::new("."))
            .join("backups"),
        ..Placement::default()
    };
    let outcome = async {
        for path in sidecars(db_path) {
            placement.move_aside(&path)?;
        }
        prepared.place_keys(&mut placement)?;
        placement.place(db_path, &prepared.contents.database)?;
        verify_placed(db_path, &prepared.contents.manifest).await
    }
    .await;
    match outcome {
        Ok(rows) => Ok(prepared.report(rows, &placement)),
        Err(err) => {
            for path in sidecars(db_path) {
                let _ = std::fs::remove_file(path);
            }
            placement.rollback();
            Err(err)
        }
    }
}

pub(super) fn pg_err(table: &str) -> impl FnOnce(sqlx::Error) -> ArchiveError {
    let table = table.to_owned();
    move |source| ArchiveError::Import { table, source }
}

async fn import_postgres(
    prepared: &Prepared,
    datasource: &Datasource,
    replace: bool,
    env: &Env,
) -> Result<ImportReport, ArchiveError> {
    let secrets_parent = secrets_dir(env)
        .and_then(|dir| dir.parent().map(Path::to_path_buf))
        .filter(|p| !p.as_os_str().is_empty());
    let scratch = Scratch::create(&secrets_parent.clone().unwrap_or_else(std::env::temp_dir))?;
    let staged = scratch.0.join(DB_ENTRY);
    write_private(&staged, &prepared.contents.database)?;
    check_staged(&staged).await?;

    let mut conn = PgConnection::connect_with(&connect_options(datasource))
        .await
        .map_err(pg_err("<connection>"))?;
    let outcome = load_postgres(prepared, &mut conn, &staged, secrets_parent, replace).await;
    let _ = conn.close().await;
    outcome
}

async fn load_postgres(
    prepared: &Prepared,
    conn: &mut PgConnection,
    staged: &Path,
    secrets_parent: Option<PathBuf>,
    replace: bool,
) -> Result<ImportReport, ArchiveError> {
    if !replace && archive_pg::holds_data(conn).await? {
        return Err(ArchiveError::DatabaseNotEmpty);
    }
    flyway::migrate(conn).await?;
    schema::check(conn).await?;

    let mut placement = Placement {
        backup_root: secrets_parent
            .or_else(|| prepared.key_dest.parent().map(Path::to_path_buf))
            .unwrap_or_else(|| PathBuf::from("."))
            .join("backups"),
        ..Placement::default()
    };
    let outcome = async {
        prepared.place_keys(&mut placement)?;
        archive_pg::load(conn, staged, &prepared.contents.manifest).await
    }
    .await;
    match outcome {
        Ok(rows) => Ok(prepared.report(rows, &placement)),
        Err(err) => {
            placement.rollback();
            Err(err)
        }
    }
}

/// Brings the extracted database to this binary's SQLite level and checks it, without comparing
/// row counts (PostgreSQL does that after the load).
async fn check_staged(path: &Path) -> Result<(), ArchiveError> {
    let db = SqliteDb::open(path)?;
    let outcome = check_database(&db);
    db.close().await;
    outcome
}

#[cfg(test)]
mod tests {
    use super::*;

    const PASSPHRASE: &str = "correct horse battery";

    fn manifest(version: u32, migration: &str) -> Manifest {
        Manifest {
            format_version: version,
            app_version: "test".into(),
            source_engine: SourceEngine::Sqlite,
            sqlite_migration: migration.into(),
            table_counts: BTreeMap::from([("app_user".to_owned(), 2)]),
            created_at: "2026-01-01T00:00:00+00:00".into(),
        }
    }

    fn contents(manifest: Manifest) -> Contents {
        Contents {
            manifest,
            database: b"SQLite format 3\0 pretend".to_vec(),
            crypto_key: b"0123456789abcdef0123456789abcdef".to_vec(),
            enablebanking_key: Some(b"-----BEGIN PRIVATE KEY-----".to_vec()),
        }
    }

    fn latest() -> String {
        migrations().last().expect("a migration").version.to_owned()
    }

    #[test]
    fn seal_then_open_returns_the_plaintext() {
        let sealed = seal(b"zip bytes", PASSPHRASE).expect("seal");
        assert_eq!(&sealed[..8], MAGIC);
        assert_eq!(open(&sealed, PASSPHRASE).expect("open"), b"zip bytes");
    }

    #[test]
    fn a_wrong_passphrase_is_rejected() {
        let sealed = seal(b"zip bytes", PASSPHRASE).expect("seal");
        assert!(matches!(
            open(&sealed, "another passphrase"),
            Err(ArchiveError::Decrypt)
        ));
    }

    #[test]
    fn a_tampered_byte_is_rejected_anywhere_after_the_magic() {
        let sealed = seal(b"zip bytes", PASSPHRASE).expect("seal");
        for index in [
            8,
            8 + SALT_BYTES,
            8 + SALT_BYTES + NONCE_BYTES,
            sealed.len() - 1,
        ] {
            let mut tampered = sealed.clone();
            tampered[index] ^= 1;
            assert!(
                matches!(open(&tampered, PASSPHRASE), Err(ArchiveError::Decrypt)),
                "byte {index}"
            );
        }
        let mut other = sealed;
        other[0] ^= 1;
        assert!(matches!(
            open(&other, PASSPHRASE),
            Err(ArchiveError::NotAnArchive)
        ));
    }

    #[test]
    fn the_passphrase_must_be_set_and_long_enough() {
        let with = |value: Option<&str>| {
            passphrase(&Env::from_map(value.map(|v| (PASSPHRASE_VARIABLE, v))))
        };
        assert!(matches!(with(None), Err(ArchiveError::PassphraseMissing)));
        assert!(matches!(
            with(Some("")),
            Err(ArchiveError::PassphraseMissing)
        ));
        assert!(matches!(
            with(Some("eleven char")),
            Err(ArchiveError::PassphraseTooShort)
        ));
        assert_eq!(
            with(Some("twelve chars")).expect("accepted"),
            "twelve chars"
        );
    }

    #[test]
    fn pack_then_unpack_keeps_every_entry() {
        let original = contents(manifest(FORMAT_VERSION, &latest()));
        let opened = unpack(&pack(&original).expect("pack")).expect("unpack");
        assert_eq!(opened.manifest, original.manifest);
        assert_eq!(opened.database, original.database);
        assert_eq!(opened.crypto_key, original.crypto_key);
        assert_eq!(opened.enablebanking_key, original.enablebanking_key);
    }

    #[test]
    fn a_newer_format_is_rejected() {
        let zip = pack(&contents(manifest(FORMAT_VERSION + 1, &latest()))).expect("pack");
        assert!(matches!(
            unpack(&zip),
            Err(ArchiveError::NewerFormat(v)) if v == FORMAT_VERSION + 1
        ));
    }

    #[test]
    fn a_migration_this_binary_does_not_know_is_rejected() {
        let zip = pack(&contents(manifest(FORMAT_VERSION, "999999"))).expect("pack");
        assert!(matches!(unpack(&zip), Err(ArchiveError::NewerSchema(v)) if v == "999999"));
    }

    #[test]
    fn an_archive_without_a_database_is_corrupt() {
        let mut zip = zip::ZipWriter::new(Cursor::new(Vec::new()));
        zip.start_file(MANIFEST_ENTRY, zip::write::SimpleFileOptions::default())
            .expect("entry");
        zip.write_all(&serde_json::to_vec(&manifest(FORMAT_VERSION, &latest())).expect("json"))
            .expect("write");
        let bytes = zip.finish().expect("finish").into_inner();
        assert!(matches!(unpack(&bytes), Err(ArchiveError::Corrupt(_))));
    }
}
