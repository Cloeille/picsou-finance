//! Keeps a copy of the data before a new version migrates it, and tells why a start failed.
//!
//! `app-version` in the data folder names the last version that reached the dashboard. A start
//! with another version copies `picsou.db` (plus `-wal` and `-shm` if present) and `secrets/`
//! to `backups/<seconds>-<from>-to-<to>/` first, while the backend is stopped, and keeps the
//! last two copies.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use picsou::db::flyway::MigrationError;
use picsou::db::sqlite::migrate::SqliteMigrationError;
use semver::Version;

const MARKER: &str = "app-version";
const KEPT: usize = 2;
const SQLITE_MAIN: &str = "picsou.db";
const SQLITE_SIDECARS: [&str; 2] = ["picsou.db-wal", "picsou.db-shm"];
const SQLITE_FILES: [&str; 3] = [SQLITE_MAIN, "picsou.db-wal", "picsou.db-shm"];
const SECRETS_DIR: &str = "secrets";

#[derive(Debug, PartialEq)]
pub enum Before {
    /// No data yet, or the same version as last time.
    Nothing,
    /// `from` is a valid version (so safe in a folder name), or `unknown`.
    Upgrade { from: String },
    /// The data was last used by a newer version: starting would hit migrations this one lacks.
    Downgrade { from: String },
}

#[derive(Debug, PartialEq)]
pub enum Failure {
    /// The database has migrations this version does not ship (#554).
    NewerData,
    Migration(String),
}

pub fn recorded(data: &Path) -> Option<String> {
    fs::read_to_string(data.join(MARKER))
        .ok()
        .map(|v| v.trim().to_owned())
        .filter(|v| !v.is_empty())
}

pub fn record(data: &Path, version: &str) -> io::Result<()> {
    fs::write(data.join(MARKER), version)
}

/// `None` with an existing database means data from a build without the marker: an update too.
pub fn compare(recorded: Option<&str>, current: &str) -> Before {
    let unknown = || Before::Upgrade {
        from: "unknown".to_owned(),
    };
    let Some(from) = recorded else {
        return unknown();
    };
    let (Ok(old), Ok(new)) = (Version::parse(from), Version::parse(current)) else {
        // An unreadable marker is treated as an update: a needless copy costs less than none.
        return unknown();
    };
    match old.cmp(&new) {
        std::cmp::Ordering::Greater => Before::Downgrade {
            from: old.to_string(),
        },
        std::cmp::Ordering::Less => Before::Upgrade {
            from: old.to_string(),
        },
        std::cmp::Ordering::Equal => Before::Nothing,
    }
}

/// Copies a single file with 0600 permissions on Unix, flushed so a power cut cannot leave a
/// complete-looking copy of an empty file.
fn copy_private_file(from: &Path, to: &Path) -> io::Result<()> {
    fs::copy(from, to)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(to, fs::Permissions::from_mode(0o600))?;
    }
    // Opened for writing: Windows' FlushFileBuffers refuses a read-only handle.
    fs::OpenOptions::new().write(true).open(to)?.sync_all()?;
    Ok(())
}

/// Copies a tree with the folders' permission bits (0700) and the files' (secrets stay 0600),
/// flushed so a power cut cannot leave a complete-looking copy of empty files.
fn copy_dir(from: &Path, to: &Path) -> io::Result<()> {
    fs::create_dir_all(to)?;
    fs::set_permissions(to, fs::metadata(from)?.permissions())?;
    for entry in fs::read_dir(from)? {
        let entry = entry?;
        let name = entry.file_name();
        let target = to.join(&name);
        if entry.file_type()?.is_dir() {
            copy_dir(&entry.path(), &target)?;
        } else {
            fs::copy(entry.path(), &target)?;
            // Opened for writing: Windows' FlushFileBuffers refuses a read-only handle.
            fs::OpenOptions::new()
                .write(true)
                .open(&target)?
                .sync_all()?;
        }
    }
    Ok(())
}

fn backups_dir(data: &Path) -> io::Result<PathBuf> {
    let dir = data.join("backups");
    fs::create_dir_all(&dir)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&dir, fs::Permissions::from_mode(0o700))?;
    }
    Ok(dir)
}

fn modified(path: &Path) -> Option<std::time::SystemTime> {
    fs::metadata(path).and_then(|m| m.modified()).ok()
}

fn backups(data: &Path) -> Vec<PathBuf> {
    let mut found: Vec<PathBuf> = fs::read_dir(data.join("backups"))
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path())
        // Not `extension()`: the versions in the name have dots, so every copy has one.
        .filter(|p| p.is_dir() && !p.to_string_lossy().ends_with(".partial"))
        .collect();
    // Names start with zero-padded seconds, so the name order is the age order.
    found.sort();
    found
}

/// The copy taken before the `from` → `to` update. Copies `picsou.db` (plus `-wal` and `-shm`
/// if present) with 0600 perms and `secrets/` while the backend is not running. A retried update
/// reuses its first copy, so it never keeps a half-migrated one; but not once a later start
/// succeeded (`record` touched the marker since), or a restore would bring back stale data.
pub fn backup(data: &Path, from: &str, to: &str, now_secs: u64) -> io::Result<PathBuf> {
    let suffix = format!("-{from}-to-{to}");
    let marker = modified(&data.join(MARKER));
    let reusable = backups(data).into_iter().rev().find(|p| {
        p.to_string_lossy().ends_with(&suffix)
            && modified(p)
                .zip(marker)
                .is_none_or(|(copy, last)| copy >= last)
    });
    if let Some(existing) = reusable {
        return Ok(existing);
    }
    let dir = backups_dir(data)?;
    for stale in fs::read_dir(&dir)?
        .flatten()
        .filter(|e| e.file_name().to_string_lossy().ends_with(".partial"))
    {
        let _ = fs::remove_dir_all(stale.path());
    }
    let target = dir.join(format!("{now_secs:012}{suffix}"));
    let partial = dir.join(format!("{now_secs:012}{suffix}.partial"));
    let copied = (|| -> io::Result<()> {
        fs::create_dir_all(&partial)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&partial, fs::Permissions::from_mode(0o700))?;
        }
        for name in SQLITE_FILES {
            let src = data.join(name);
            if src.exists() {
                copy_private_file(&src, &partial.join(name))?;
            }
        }
        let secrets = data.join(SECRETS_DIR);
        if secrets.is_dir() {
            copy_dir(&secrets, &partial.join(SECRETS_DIR))?;
        }
        Ok(())
    })();
    // A copy only counts once complete; a failed one must not fill a full disk further.
    if let Err(e) = copied.and_then(|_| fs::rename(&partial, &target)) {
        let _ = fs::remove_dir_all(&partial);
        return Err(e);
    }
    let all = backups(data);
    for old in &all[..all.len().saturating_sub(KEPT)] {
        // The new copy is safe: a failed cleanup must not stop Picsou from starting.
        let _ = fs::remove_dir_all(old);
    }
    Ok(target)
}

pub fn latest_backup(data: &Path, to: &str) -> Option<PathBuf> {
    backups(data)
        .into_iter()
        .rev()
        .find(|p| p.to_string_lossy().ends_with(&format!("-to-{to}")))
}

fn aside(data: &Path, name: &str, now_secs: u64) -> PathBuf {
    (0..)
        .map(|n| data.join(format!("{name}.failed-{now_secs}-{n}")))
        .find(|p| !p.exists())
        .expect("a free name")
}

/// Puts the copy back. Staged next to the data first so a failed restore changes nothing.
/// Before swapping `picsou.db` back into place, any stale `-wal` and `-shm` sidecars left by
/// the failed run are removed so SQLite never replays failed frames onto the restored file.
pub fn restore(data: &Path, copy: &Path, now_secs: u64) -> io::Result<()> {
    if !copy.join(SQLITE_MAIN).exists() {
        return Err(io::Error::new(
            io::ErrorKind::NotFound,
            "backup is missing picsou.db",
        ));
    }
    let db_files: Vec<&str> = SQLITE_FILES
        .into_iter()
        .filter(|name| copy.join(name).exists())
        .collect();
    let has_secrets = copy.join(SECRETS_DIR).is_dir();
    let staged = |name: &str| data.join(format!("{name}.restoring"));

    let cleanup = || {
        for name in &db_files {
            let _ = fs::remove_file(staged(name));
        }
        if has_secrets {
            let _ = fs::remove_dir_all(staged(SECRETS_DIR));
        }
    };

    let stage_result = (|| -> io::Result<()> {
        for name in &db_files {
            let _ = fs::remove_file(staged(name));
            copy_private_file(&copy.join(name), &staged(name))?;
        }
        if has_secrets {
            let _ = fs::remove_dir_all(staged(SECRETS_DIR));
            copy_dir(&copy.join(SECRETS_DIR), &staged(SECRETS_DIR))?;
        }
        Ok(())
    })();

    if let Err(e) = stage_result {
        cleanup();
        return Err(e);
    }

    // Remove stale WAL/SHM files before moving the restored database into place.
    for sidecar in SQLITE_SIDECARS {
        let path = data.join(sidecar);
        if path.exists() {
            fs::remove_file(&path)?;
        }
    }
    let current_db = data.join(SQLITE_MAIN);
    if current_db.exists() {
        fs::rename(&current_db, aside(data, SQLITE_MAIN, now_secs))?;
    }
    for name in &db_files {
        fs::rename(staged(name), data.join(name))?;
    }
    if has_secrets {
        let current_secrets = data.join(SECRETS_DIR);
        if current_secrets.exists() {
            fs::rename(&current_secrets, aside(data, SECRETS_DIR, now_secs))?;
        }
        fs::rename(staged(SECRETS_DIR), current_secrets)?;
    }
    Ok(())
}

fn format_migration_reason(text: &str) -> String {
    let reason: Vec<&str> = text
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .take(2)
        .collect();
    reason.join(". ").chars().take(300).collect()
}

/// Why the backend did not start, when its migrations are the reason.
pub fn classify(error: &anyhow::Error) -> Option<Failure> {
    if let Some(sqlite) = error
        .chain()
        .find_map(|e| e.downcast_ref::<SqliteMigrationError>())
    {
        if let SqliteMigrationError::Validation(problems) = sqlite {
            if problems.contains("is not known to this binary") {
                return Some(Failure::NewerData);
            }
        }
        return Some(Failure::Migration(format_migration_reason(
            &sqlite.to_string(),
        )));
    }
    let migration = error
        .chain()
        .find_map(|e| e.downcast_ref::<MigrationError>())?;
    if let MigrationError::Validation(problems) = migration {
        if problems.contains("Detected applied migration not resolved locally") {
            return Some(Failure::NewerData);
        }
    }
    Some(Failure::Migration(format_migration_reason(
        &migration.to_string(),
    )))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch(name: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("picsou-upgrade-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(dir.join("secrets")).unwrap();
        fs::write(dir.join("picsou.db"), "sqlite-rows").unwrap();
        fs::write(dir.join("picsou.db-wal"), "wal-frames").unwrap();
        fs::write(dir.join("picsou.db-shm"), "shm-index").unwrap();
        fs::write(dir.join("secrets/crypto_key"), "key").unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(dir.join("secrets"), fs::Permissions::from_mode(0o700)).unwrap();
            for f in [
                "picsou.db",
                "picsou.db-wal",
                "picsou.db-shm",
                "secrets/crypto_key",
            ] {
                fs::set_permissions(dir.join(f), fs::Permissions::from_mode(0o600)).unwrap();
            }
        }
        dir
    }

    #[test]
    fn versions_compare_as_semver_and_a_missing_or_unreadable_marker_is_an_update() {
        let unknown = Before::Upgrade {
            from: "unknown".into(),
        };
        assert_eq!(compare(None, "1.3.0"), unknown);
        assert_eq!(compare(Some("../../etc"), "1.3.0"), unknown);
        assert_eq!(compare(Some("1.3.0"), "1.3.0"), Before::Nothing);
        assert_eq!(
            compare(Some("1.2.9"), "1.3.0"),
            Before::Upgrade {
                from: "1.2.9".into()
            }
        );
        assert_eq!(
            compare(Some("1.10.0"), "1.9.0"),
            Before::Downgrade {
                from: "1.10.0".into()
            }
        );
        assert_eq!(
            compare(Some("1.2.0"), "1.2.0-rc.1"),
            Before::Downgrade {
                from: "1.2.0".into()
            }
        );
    }

    #[test]
    fn an_update_is_copied_once_and_two_copies_are_kept() {
        let data = scratch("rotate");
        let first = backup(&data, "1.0.0", "1.1.0", 1).unwrap();
        fs::write(data.join("picsou.db"), "half-migrated").unwrap();

        assert_eq!(backup(&data, "1.0.0", "1.1.0", 2).unwrap(), first);
        assert_eq!(
            fs::read_to_string(first.join("picsou.db")).unwrap(),
            "sqlite-rows"
        );
        assert_eq!(
            fs::read_to_string(first.join("picsou.db-wal")).unwrap(),
            "wal-frames"
        );
        assert_eq!(
            fs::read_to_string(first.join("picsou.db-shm")).unwrap(),
            "shm-index"
        );
        assert_eq!(
            fs::read_to_string(first.join("secrets/crypto_key")).unwrap(),
            "key"
        );

        backup(&data, "1.1.0", "1.2.0", 3).unwrap();
        let last = backup(&data, "1.2.0", "1.3.0", 4).unwrap();
        let names: Vec<_> = backups(&data)
            .iter()
            .map(|p| p.file_name().unwrap().to_string_lossy().into_owned())
            .collect();
        assert_eq!(
            names,
            ["000000000003-1.1.0-to-1.2.0", "000000000004-1.2.0-to-1.3.0"]
        );
        assert_eq!(latest_backup(&data, "1.3.0"), Some(last));
    }

    #[test]
    fn a_copy_older_than_the_last_successful_start_is_not_reused() {
        let data = scratch("stale");
        let first = backup(&data, "1.0.0", "1.1.0", 1).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(20));
        // The user went back to 1.0.0 and used it: its start recorded the version again.
        record(&data, "1.0.0").unwrap();
        fs::write(data.join("picsou.db"), "weeks of use").unwrap();

        let second = backup(&data, "1.0.0", "1.1.0", 2).unwrap();

        assert_ne!(second, first);
        assert_eq!(
            fs::read_to_string(second.join("picsou.db")).unwrap(),
            "weeks of use"
        );
    }

    #[cfg(unix)]
    #[test]
    fn copies_and_restores_keep_private_permissions() {
        use std::os::unix::fs::PermissionsExt;
        let mode = |p: &Path| fs::metadata(p).unwrap().permissions().mode() & 0o777;
        let data = scratch("modes");
        let copy = backup(&data, "1.0.0", "1.1.0", 1).unwrap();
        restore(&data, &copy, 9).unwrap();

        for dir in [
            copy.clone(),
            copy.join("secrets"),
            data.join("secrets"),
            data.join("backups"),
        ] {
            assert_eq!(mode(&dir), 0o700, "{}", dir.display());
        }
        for file in [
            copy.join("picsou.db"),
            copy.join("picsou.db-wal"),
            copy.join("picsou.db-shm"),
            data.join("picsou.db"),
            data.join("picsou.db-wal"),
            data.join("picsou.db-shm"),
            data.join("secrets/crypto_key"),
        ] {
            assert_eq!(mode(&file), 0o600, "{}", file.display());
        }
    }

    #[test]
    fn restoring_puts_the_copy_back_removes_stale_wal_shm_and_keeps_failed_data_aside() {
        let data = scratch("restore");
        // Backup taken when no -wal/-shm existed (clean checkpointed SQLite DB).
        fs::remove_file(data.join("picsou.db-wal")).unwrap();
        fs::remove_file(data.join("picsou.db-shm")).unwrap();
        let copy = backup(&data, "1.0.0", "1.1.0", 1).unwrap();
        assert!(!copy.join("picsou.db-wal").exists());
        assert!(!copy.join("picsou.db-shm").exists());

        // Failed upgrade modified picsou.db and left stale -wal/-shm behind.
        fs::write(data.join("picsou.db"), "half-migrated").unwrap();
        fs::write(data.join("picsou.db-wal"), "stale-wal").unwrap();
        fs::write(data.join("picsou.db-shm"), "stale-shm").unwrap();

        restore(&data, &copy, 9).unwrap();
        assert_eq!(
            fs::read_to_string(data.join("picsou.db")).unwrap(),
            "sqlite-rows"
        );
        assert!(!data.join("picsou.db-wal").exists());
        assert!(!data.join("picsou.db-shm").exists());
        assert_eq!(
            fs::read_to_string(data.join("picsou.db.failed-9-0")).unwrap(),
            "half-migrated"
        );
        assert!(copy.join("picsou.db").exists());

        // Second restore moves the previous file aside under the next free counter.
        restore(&data, &copy, 9).unwrap();
        assert!(data.join("picsou.db.failed-9-1").exists());
    }

    #[test]
    fn a_restore_from_a_broken_copy_leaves_the_data_alone() {
        let data = scratch("broken");
        let copy = data.join("broken-copy");
        fs::create_dir_all(copy.join("secrets")).unwrap();
        fs::write(copy.join("picsou.db"), "backup-rows").unwrap();
        // A dangling symlink where a file is expected makes the staging copy fail.
        #[cfg(unix)]
        std::os::unix::fs::symlink("/nonexistent/picsou", copy.join("secrets/2")).unwrap();
        #[cfg(not(unix))]
        fs::remove_file(copy.join("picsou.db")).unwrap();

        assert!(restore(&data, &copy, 9).is_err());
        assert_eq!(
            fs::read_to_string(data.join("picsou.db")).unwrap(),
            "sqlite-rows"
        );
        assert_eq!(
            fs::read_to_string(data.join("picsou.db-wal")).unwrap(),
            "wal-frames"
        );
        assert!(!data.join("picsou.db.restoring").exists());
        assert!(!data.join("picsou.db.failed-9-0").exists());
    }

    #[test]
    fn migration_failures_are_told_apart() {
        let newer = anyhow::Error::from(MigrationError::Validation(
            "Detected applied migration not resolved locally: 146. If you removed this migration intentionally, run repair to mark the migration as deleted.".into(),
        ));
        let checksum = anyhow::Error::from(MigrationError::Validation(
            "Migration checksum mismatch for migration version 2".into(),
        ));
        let baseline =
            anyhow::Error::from(MigrationError::HighBaseline("200".into())).context("startup");

        assert_eq!(classify(&newer), Some(Failure::NewerData));
        assert_eq!(
            classify(&checksum),
            Some(Failure::Migration("Validate failed: Migrations have failed validation. Migration checksum mismatch for migration version 2".into()))
        );
        assert_eq!(
            classify(&baseline),
            Some(Failure::Migration(
                "Schema baseline 200 is newer than the available migrations".into()
            ))
        );
        assert_eq!(classify(&anyhow::anyhow!("connection refused")), None);
    }

    #[test]
    fn sqlite_migration_failures_distinguish_newer_data_from_migration_errors() {
        let newer = anyhow::Error::from(picsou::db::sqlite::SqliteError::Migration(
            SqliteMigrationError::Validation(
                "applied migration V2 (future schema) is not known to this binary".into(),
            ),
        ))
        .context("startup");
        assert_eq!(classify(&newer), Some(Failure::NewerData));

        let checksum = anyhow::Error::from(SqliteMigrationError::Validation(
            "checksum mismatch for migration V1: database has aaa, binary expects bbb".into(),
        ));
        assert_eq!(
            classify(&checksum),
            Some(Failure::Migration(
                "SQLite migration validation failed: checksum mismatch for migration V1: database has aaa, binary expects bbb".into()
            ))
        );

        let schema = anyhow::Error::from(picsou::db::sqlite::SqliteError::Migration(
            SqliteMigrationError::Schema("missing column [foo] in table [account]".into()),
        ));
        assert_eq!(
            classify(&schema),
            Some(Failure::Migration(
                "SQLite schema verification failed: missing column [foo] in table [account]".into()
            ))
        );
    }
}
