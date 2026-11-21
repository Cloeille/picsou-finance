//! Keeps a copy of the data before a new version migrates it, and tells why a start failed.
//!
//! `app-version` in the data folder names the last version that reached the dashboard. A start
//! with another version copies `postgres/` and `secrets/` to `backups/<seconds>-<from>-to-<to>/`
//! first, while PostgreSQL is stopped, and keeps the last two copies. A start that finds pending
//! migrations also takes a `pg_dump` to `backups/<seconds>-before-<version>.dump` before the
//! backend applies them, and keeps the last three.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::ExitStatus;

use picsou::db::flyway::MigrationError;
use semver::Version;

const MARKER: &str = "app-version";
const KEPT: usize = 2;
const DUMPS_KEPT: usize = 3;
const COPIED: [&str; 2] = ["postgres", "secrets"];
/// Runtime files of a running cluster: PostgreSQL recreates them, a copy must not carry them.
const SKIPPED: [&str; 2] = ["postmaster.pid", "postmaster.opts"];

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
    fs::read_to_string(data.join(MARKER)).ok().map(|v| v.trim().to_owned()).filter(|v| !v.is_empty())
}

pub fn record(data: &Path, version: &str) -> io::Result<()> {
    fs::write(data.join(MARKER), version)
}

/// `None` with an existing cluster means data from a build without the marker: an update too.
pub fn compare(recorded: Option<&str>, current: &str) -> Before {
    let unknown = || Before::Upgrade { from: "unknown".to_owned() };
    let Some(from) = recorded else { return unknown() };
    let (Ok(old), Ok(new)) = (Version::parse(from), Version::parse(current)) else {
        // An unreadable marker is treated as an update: a needless copy costs less than none.
        return unknown();
    };
    match old.cmp(&new) {
        std::cmp::Ordering::Greater => Before::Downgrade { from: old.to_string() },
        std::cmp::Ordering::Less => Before::Upgrade { from: old.to_string() },
        std::cmp::Ordering::Equal => Before::Nothing,
    }
}

/// Copies a tree with the folders' permission bits (PostgreSQL refuses a data folder that is not
/// 0700 or 0750) and the files' (secrets stay 0600), flushed so a power cut cannot leave a
/// complete-looking copy of empty files.
fn copy_dir(from: &Path, to: &Path) -> io::Result<()> {
    fs::create_dir_all(to)?;
    fs::set_permissions(to, fs::metadata(from)?.permissions())?;
    for entry in fs::read_dir(from)? {
        let entry = entry?;
        let name = entry.file_name();
        if SKIPPED.iter().any(|s| name == *s) {
            continue;
        }
        let target = to.join(&name);
        if entry.file_type()?.is_dir() {
            copy_dir(&entry.path(), &target)?;
        } else {
            fs::copy(entry.path(), &target)?;
            // Opened for writing: Windows' FlushFileBuffers refuses a read-only handle.
            fs::OpenOptions::new().write(true).open(&target)?.sync_all()?;
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

/// The copy taken before the `from` → `to` update. A retried update reuses its first copy, so it
/// never keeps a half-migrated one; but not once a later start succeeded (`record` touched the
/// marker since), or a restore would bring back data from before weeks of use.
pub fn backup(data: &Path, from: &str, to: &str, now_secs: u64) -> io::Result<PathBuf> {
    let suffix = format!("-{from}-to-{to}");
    let marker = modified(&data.join(MARKER));
    let reusable = backups(data).into_iter().rev().find(|p| {
        p.to_string_lossy().ends_with(&suffix) && modified(p).zip(marker).is_none_or(|(copy, last)| copy >= last)
    });
    if let Some(existing) = reusable {
        return Ok(existing);
    }
    let dir = backups_dir(data)?;
    for stale in fs::read_dir(&dir)?.flatten().filter(|e| e.file_name().to_string_lossy().ends_with(".partial")) {
        let _ = fs::remove_dir_all(stale.path());
    }
    let target = dir.join(format!("{now_secs:012}{suffix}"));
    let partial = dir.join(format!("{now_secs:012}{suffix}.partial"));
    let copied = COPIED
        .iter()
        .filter(|name| data.join(name).exists())
        .try_for_each(|name| copy_dir(&data.join(name), &partial.join(name)));
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
    backups(data).into_iter().rev().find(|p| p.to_string_lossy().ends_with(&format!("-to-{to}")))
}

fn aside(data: &Path, name: &str, now_secs: u64) -> PathBuf {
    (0..).map(|n| data.join(format!("{name}.failed-{now_secs}-{n}"))).find(|p| !p.exists()).expect("a free name")
}

/// Puts the copy back. It is staged next to the data first, and the current data is moved aside
/// (never deleted) only once every staged copy is complete: a failed restore changes nothing.
pub fn restore(data: &Path, copy: &Path, now_secs: u64) -> io::Result<()> {
    let names: Vec<&str> = COPIED.into_iter().filter(|name| copy.join(name).exists()).collect();
    let staged = |name: &str| data.join(format!("{name}.restoring"));
    let copied = names.iter().try_for_each(|name| {
        let _ = fs::remove_dir_all(staged(name));
        copy_dir(&copy.join(name), &staged(name))
    });
    if let Err(e) = copied {
        for name in &names {
            let _ = fs::remove_dir_all(staged(name));
        }
        return Err(e);
    }
    for name in names {
        let current = data.join(name);
        if current.exists() {
            fs::rename(&current, aside(data, name, now_secs))?;
        }
        fs::rename(staged(name), current)?;
    }
    Ok(())
}

/// Runs `pg_dump` (given the file to write) into `backups/<seconds>-before-<version>.dump`, where
/// `first_pending` is the first migration script the backend will apply. The file appears only
/// once the dump succeeded, and only the last DUMPS_KEPT dumps are kept.
pub fn dump(data: &Path, first_pending: &str, now_secs: u64, pg_dump: impl FnOnce(&Path) -> io::Result<ExitStatus>) -> io::Result<PathBuf> {
    let dir = backups_dir(data)?;
    for stale in fs::read_dir(&dir)?.flatten().filter(|e| e.file_name().to_string_lossy().ends_with(".dump.partial")) {
        let _ = fs::remove_file(stale.path());
    }
    let version = first_pending.split("__").next().unwrap_or(first_pending);
    let target = dir.join(format!("{now_secs:012}-before-{version}.dump"));
    let partial = dir.join(format!("{now_secs:012}-before-{version}.dump.partial"));
    let dumped = pg_dump(&partial).and_then(|status| {
        if status.success() {
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                fs::set_permissions(&partial, fs::Permissions::from_mode(0o600))?;
            }
            fs::rename(&partial, &target)
        } else {
            Err(io::Error::other(format!("pg_dump failed ({status})")))
        }
    });
    if let Err(e) = dumped {
        let _ = fs::remove_file(&partial);
        return Err(e);
    }
    let mut dumps: Vec<PathBuf> = fs::read_dir(&dir)?
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.is_file() && p.to_string_lossy().ends_with(".dump"))
        .collect();
    dumps.sort();
    for old in &dumps[..dumps.len().saturating_sub(DUMPS_KEPT)] {
        let _ = fs::remove_file(old);
    }
    Ok(target)
}

/// Why the backend did not start, when its migrations are the reason.
pub fn classify(error: &anyhow::Error) -> Option<Failure> {
    let migration = error.chain().find_map(|e| e.downcast_ref::<MigrationError>())?;
    if let MigrationError::Validation(problems) = migration {
        if problems.contains("Detected applied migration not resolved locally") {
            return Some(Failure::NewerData);
        }
    }
    let text = migration.to_string();
    let reason: Vec<&str> = text.lines().map(str::trim).filter(|line| !line.is_empty()).take(2).collect();
    Some(Failure::Migration(reason.join(". ").chars().take(300).collect()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("picsou-upgrade-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(dir.join("postgres/base")).unwrap();
        fs::create_dir_all(dir.join("secrets")).unwrap();
        fs::write(dir.join("postgres/base/1"), "rows").unwrap();
        fs::write(dir.join("postgres/postmaster.pid"), "4242").unwrap();
        fs::write(dir.join("secrets/crypto_key"), "key").unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            for d in ["postgres", "postgres/base", "secrets"] {
                fs::set_permissions(dir.join(d), fs::Permissions::from_mode(0o700)).unwrap();
            }
            fs::set_permissions(dir.join("secrets/crypto_key"), fs::Permissions::from_mode(0o600)).unwrap();
        }
        dir
    }

    #[test]
    fn versions_compare_as_semver_and_a_missing_or_unreadable_marker_is_an_update() {
        let unknown = Before::Upgrade { from: "unknown".into() };
        assert_eq!(compare(None, "1.3.0"), unknown);
        assert_eq!(compare(Some("../../etc"), "1.3.0"), unknown);
        assert_eq!(compare(Some("1.3.0"), "1.3.0"), Before::Nothing);
        assert_eq!(compare(Some("1.2.9"), "1.3.0"), Before::Upgrade { from: "1.2.9".into() });
        assert_eq!(compare(Some("1.10.0"), "1.9.0"), Before::Downgrade { from: "1.10.0".into() });
        assert_eq!(compare(Some("1.2.0"), "1.2.0-rc.1"), Before::Downgrade { from: "1.2.0".into() });
    }

    #[test]
    fn an_update_is_copied_once_and_two_copies_are_kept() {
        let data = scratch("rotate");
        let first = backup(&data, "1.0.0", "1.1.0", 1).unwrap();
        fs::write(data.join("postgres/base/1"), "half-migrated").unwrap();

        assert_eq!(backup(&data, "1.0.0", "1.1.0", 2).unwrap(), first);
        assert_eq!(fs::read_to_string(first.join("postgres/base/1")).unwrap(), "rows");
        assert_eq!(fs::read_to_string(first.join("secrets/crypto_key")).unwrap(), "key");
        assert!(!first.join("postgres/postmaster.pid").exists());

        backup(&data, "1.1.0", "1.2.0", 3).unwrap();
        let last = backup(&data, "1.2.0", "1.3.0", 4).unwrap();
        let names: Vec<_> = backups(&data).iter().map(|p| p.file_name().unwrap().to_string_lossy().into_owned()).collect();
        assert_eq!(names, ["000000000003-1.1.0-to-1.2.0", "000000000004-1.2.0-to-1.3.0"]);
        assert_eq!(latest_backup(&data, "1.3.0"), Some(last));
    }

    #[test]
    fn a_copy_older_than_the_last_successful_start_is_not_reused() {
        let data = scratch("stale");
        let first = backup(&data, "1.0.0", "1.1.0", 1).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(20));
        // The user went back to 1.0.0 and used it: its start recorded the version again.
        record(&data, "1.0.0").unwrap();
        fs::write(data.join("postgres/base/1"), "weeks of use").unwrap();

        let second = backup(&data, "1.0.0", "1.1.0", 2).unwrap();

        assert_ne!(second, first);
        assert_eq!(fs::read_to_string(second.join("postgres/base/1")).unwrap(), "weeks of use");
    }

    #[cfg(unix)]
    #[test]
    fn copies_and_restores_keep_the_modes_postgres_requires() {
        use std::os::unix::fs::PermissionsExt;
        let mode = |p: &Path| fs::metadata(p).unwrap().permissions().mode() & 0o777;
        let data = scratch("modes");
        let copy = backup(&data, "1.0.0", "1.1.0", 1).unwrap();
        restore(&data, &copy, 9).unwrap();

        for dir in [copy.join("postgres"), copy.join("postgres/base"), data.join("postgres"), data.join("postgres/base"), data.join("backups")] {
            assert_eq!(mode(&dir), 0o700, "{}", dir.display());
        }
        assert_eq!(mode(&data.join("secrets/crypto_key")), 0o600);
    }

    #[test]
    fn restoring_puts_the_copy_back_and_keeps_the_failed_data_aside() {
        let data = scratch("restore");
        let copy = backup(&data, "1.0.0", "1.1.0", 1).unwrap();
        fs::write(data.join("postgres/base/1"), "half-migrated").unwrap();

        restore(&data, &copy, 9).unwrap();
        restore(&data, &copy, 9).unwrap();

        assert_eq!(fs::read_to_string(data.join("postgres/base/1")).unwrap(), "rows");
        assert_eq!(fs::read_to_string(data.join("postgres.failed-9-0/base/1")).unwrap(), "half-migrated");
        assert!(data.join("postgres.failed-9-1").exists());
        assert!(copy.join("postgres/base/1").exists());
    }

    #[test]
    fn a_restore_from_a_broken_copy_leaves_the_data_alone() {
        let data = scratch("broken");
        let copy = data.join("broken-copy");
        fs::create_dir_all(copy.join("postgres")).unwrap();
        fs::write(copy.join("postgres/1"), "rows").unwrap();
        // A dangling symlink where a file is expected makes the staging copy fail.
        #[cfg(unix)]
        std::os::unix::fs::symlink("/nonexistent/picsou", copy.join("postgres/2")).unwrap();

        assert!(restore(&data, &copy, 9).is_err());
        assert_eq!(fs::read_to_string(data.join("postgres/base/1")).unwrap(), "rows");
        assert!(!data.join("postgres.restoring").exists());
        assert!(!data.join("postgres.failed-9-0").exists());
    }

    #[test]
    fn migration_failures_are_told_apart() {
        let newer = anyhow::Error::from(MigrationError::Validation(
            "Detected applied migration not resolved locally: 146. If you removed this migration intentionally, run repair to mark the migration as deleted.".into(),
        ));
        let checksum = anyhow::Error::from(MigrationError::Validation("Migration checksum mismatch for migration version 2".into()));
        let baseline = anyhow::Error::from(MigrationError::HighBaseline("200".into())).context("startup");

        assert_eq!(classify(&newer), Some(Failure::NewerData));
        assert_eq!(
            classify(&checksum),
            Some(Failure::Migration("Validate failed: Migrations have failed validation. Migration checksum mismatch for migration version 2".into()))
        );
        assert_eq!(classify(&baseline), Some(Failure::Migration("Schema baseline 200 is newer than the available migrations".into())));
        assert_eq!(classify(&anyhow::anyhow!("connection refused")), None);
    }

    fn exited(code: i32) -> ExitStatus {
        #[cfg(unix)]
        return std::os::unix::process::ExitStatusExt::from_raw(code << 8);
        #[cfg(windows)]
        return std::os::windows::process::ExitStatusExt::from_raw(code as u32);
    }

    #[test]
    fn a_dump_appears_only_once_complete_and_the_last_three_are_kept() {
        let data = scratch("dump");
        for (now, script) in [(1, "V150__a.sql"), (2, "V151__b.sql"), (3, "V152__c.sql"), (4, "V153__d.sql")] {
            dump(&data, script, now, |target| fs::write(target, "dump").map(|_| exited(0))).unwrap();
        }
        let failed = dump(&data, "V154__e.sql", 5, |target| fs::write(target, "half").map(|_| exited(1)));

        assert!(failed.is_err());
        let mut names: Vec<_> = fs::read_dir(data.join("backups")).unwrap().flatten().map(|e| e.file_name().to_string_lossy().into_owned()).collect();
        names.sort();
        assert_eq!(names, ["000000000002-before-V151.dump", "000000000003-before-V152.dump", "000000000004-before-V153.dump"]);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = fs::metadata(data.join("backups").join(&names[2])).unwrap().permissions().mode();
            assert_eq!(mode & 0o777, 0o600);
        }
        // A dump is not a copy the restore button could pick.
        assert_eq!(latest_backup(&data, "1.3.0"), None);
    }
}
