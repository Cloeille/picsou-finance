"""Unit tests for backend-rs/scripts/migrations.py."""

from __future__ import annotations

import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import migrations  # noqa: E402

GIT_ID = ["-c", "user.name=Jane Doe", "-c", "user.email=contact@example.com", "-c", "commit.gpgsign=false"]

SAMPLE_MIGRATE_RS = """pub const BASELINE_SCHEMA: &str = include_str!("schema.sql");

const V4_ACCOUNT_TRACKED_ISIN: &str = include_str!("migrations/V4__account_tracked_isin.sql");

pub const HISTORY_TABLE: &str = "picsou_sqlite_migrations";

pub fn migrations() -> Vec<SqliteMigration> {
    vec![
        SqliteMigration {
            version: "1",
            description: "baseline schema v155",
            sql: BASELINE_SCHEMA,
            checksum: sha256_checksum(BASELINE_SCHEMA),
            mode: MigrationMode::Transactional,
        },
    ]
}

#[cfg(test)]
mod tests { const TEST_ONLY_VERSION: &str = "99"; }
"""


def _git(cwd: Path, *args: str) -> str:
    return subprocess.run(["git", *GIT_ID, *args], cwd=cwd, capture_output=True, text=True, check=True).stdout


def _write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


def _make_git_fixture(tmp: Path) -> tuple[Path, Path]:
    remote, work = tmp / "origin.git", tmp / "work"
    _git(tmp, "init", "--bare", "--initial-branch=main", str(remote))
    _git(tmp, "clone", str(remote), str(work))

    _write(work / "backend-rs/migrations/V1__init_schema.sql", "CREATE TABLE t (id INT);\n")
    _write(work / "backend-rs/migrations/V155__base_feature.sql", "ALTER TABLE t ADD COLUMN a INT;\n")
    _write(work / "backend-rs/src/db/sqlite/migrations/README.md", "# SQLite migrations\n")
    _write(work / "backend-rs/src/db/sqlite/migrations/V2__real_estate_energy_class_check.sql", "-- v2\n")
    _write(work / "backend-rs/src/db/sqlite/migrate.rs", SAMPLE_MIGRATE_RS)
    _git(work, "add", ".")
    _git(work, "commit", "-m", "init main")
    _git(work, "push", "-u", "origin", "main")

    for branch, pg, sq in [("release/1.3.1", "V156__patch_change.sql", "V3__patch_change.sql"),
                           ("release/1.4.0", "V157__minor_change.sql", "V4__account_tracked_isin.sql")]:
        _git(work, "checkout", "-b", branch, "main")
        _write(work / "backend-rs/migrations" / pg, "-- migration\n")
        _write(work / "backend-rs/src/db/sqlite/migrations" / sq, "-- sqlite migration\n")
        _git(work, "add", ".")
        _git(work, "commit", "-m", f"migration on {branch}")
        _git(work, "push", "-u", "origin", branch)
    return remote, work


class NameValidationTests(unittest.TestCase):
    def test_valid_and_invalid_names(self) -> None:
        for ok in ("add_flag", "v2_column", "account_tracked_isin", "simple"):
            self.assertEqual(migrations.validate_name(ok), ok)
        for bad in ("", "AddFlag", "add-flag", "-flag", "123_start", "_leading", "trailing_", "double__under", "a/b"):
            with self.subTest(bad=bad), self.assertRaises(migrations.MigrationToolError):
                migrations.validate_name(bad)

    def test_cli_rejects_leading_dash_name_after_double_dash(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            _, work = _make_git_fixture(Path(tmpdir))
            err = io.StringIO()
            with mock.patch("sys.stderr", err):
                rc = migrations.main(["new", "--no-fetch", "--", "-bad_name"], repo_root=work)
            self.assertEqual(rc, 1)
            self.assertIn("invalid migration NAME '-bad_name'", err.getvalue())


class VersionComputationTests(unittest.TestCase):
    def test_counts_main_and_every_release_branch_regardless_of_current_branch(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            _, work = _make_git_fixture(Path(tmpdir))
            _git(work, "checkout", "release/1.3.1")  # only V156/V3 locally; release/1.4.0 has V157/V4
            next_pg, next_sqlite, refs = migrations.compute_next_versions(work, "origin", fetch=True)
            self.assertEqual((next_pg, next_sqlite), (158, 5))
            self.assertEqual(
                set(refs),
                {"refs/remotes/origin/main", "refs/remotes/origin/release/1.3.1", "refs/remotes/origin/release/1.4.0"},
            )

    def test_counts_local_uncommitted_migrations_and_prunes_deleted_release_branch(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            remote, work = _make_git_fixture(Path(tmpdir))
            _git(remote, "branch", "-D", "release/1.4.0")
            _git(work, "checkout", "release/1.3.1")
            next_pg, next_sqlite, refs = migrations.compute_next_versions(work, "origin", fetch=True)
            self.assertEqual((next_pg, next_sqlite), (157, 4))
            self.assertNotIn("refs/remotes/origin/release/1.4.0", refs)

            _write(work / "backend-rs/migrations/V157__local_draft.sql", "-- draft\n")
            _write(work / "backend-rs/src/db/sqlite/migrations/V4__local_draft.sql", "-- draft\n")
            next_pg, next_sqlite, _ = migrations.compute_next_versions(work, "origin", fetch=False)
            self.assertEqual((next_pg, next_sqlite), (158, 5))

    def test_missing_remote_main_fails_clearly(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            _, work = _make_git_fixture(Path(tmpdir))
            with self.assertRaises(migrations.MigrationToolError) as ctx:
                migrations.compute_next_versions(work, "upstream", fetch=False)
            self.assertIn("upstream/main", str(ctx.exception))


class NewMigrationScaffoldTests(unittest.TestCase):
    def test_dry_run_prints_plan_without_modifying_files(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            _, work = _make_git_fixture(Path(tmpdir))
            before = (work / migrations.SQLITE_MIGRATE_RS_REL).read_text(encoding="utf-8")
            buf = io.StringIO()
            with mock.patch("sys.stdout", buf):
                rc = migrations.cmd_new(work, "portfolio_notes", remote="origin", dry_run=True, fetch=True)
            self.assertEqual(rc, 0)
            self.assertIn("V158__portfolio_notes.sql", buf.getvalue())
            self.assertIn("V5__portfolio_notes.sql", buf.getvalue())
            self.assertFalse((work / "backend-rs/migrations/V158__portfolio_notes.sql").exists())
            self.assertFalse((work / "backend-rs/src/db/sqlite/migrations/V5__portfolio_notes.sql").exists())
            self.assertEqual((work / migrations.SQLITE_MIGRATE_RS_REL).read_text(encoding="utf-8"), before)

    def test_creates_both_stubs_and_registers_sqlite_migration(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            _, work = _make_git_fixture(Path(tmpdir))
            with mock.patch("sys.stdout", io.StringIO()), mock.patch("shutil.which", return_value=None):
                self.assertEqual(migrations.cmd_new(work, "portfolio_notes", remote="origin", fetch=True), 0)
            pg_file = work / "backend-rs/migrations/V158__portfolio_notes.sql"
            sq_file = work / "backend-rs/src/db/sqlite/migrations/V5__portfolio_notes.sql"
            self.assertIn("V5__portfolio_notes.sql", pg_file.read_text(encoding="utf-8"))
            self.assertIn("V158__portfolio_notes", sq_file.read_text(encoding="utf-8"))
            self.assertNotIn(b"\r\n", pg_file.read_bytes())
            self.assertNotIn(b"\r\n", sq_file.read_bytes())

            migrate_rs = (work / migrations.SQLITE_MIGRATE_RS_REL).read_text(encoding="utf-8")
            self.assertIn('const V5_PORTFOLIO_NOTES: &str = include_str!("migrations/V5__portfolio_notes.sql");', migrate_rs)
            self.assertIn('version: "5",', migrate_rs)
            self.assertIn('description: "portfolio notes",', migrate_rs)
            self.assertIn("mode: MigrationMode::Transactional,", migrate_rs)

    def test_registers_after_multiline_wrapped_include_str_with_real_rustfmt(self) -> None:
        wrapped_migrate_rs = SAMPLE_MIGRATE_RS.replace(
            'const V4_ACCOUNT_TRACKED_ISIN: &str = include_str!("migrations/V4__account_tracked_isin.sql");',
            'const V4_ACCOUNT_TRACKED_ISIN: &str = include_str!(\n'
            '    "migrations/V4__account_tracked_isin.sql"\n'
            ');',
        )
        direct = migrations.register_sqlite_migration(wrapped_migrate_rs, 5, "after_wrapped")
        self.assertLess(direct.index("V4_ACCOUNT_TRACKED_ISIN"), direct.index("V5_AFTER_WRAPPED"))

        with tempfile.TemporaryDirectory() as tmpdir:
            _, work = _make_git_fixture(Path(tmpdir))
            migrate_rs_path = work / migrations.SQLITE_MIGRATE_RS_REL
            _write(migrate_rs_path, wrapped_migrate_rs)

            long_name = "a_very_long_migration_name_that_rustfmt_wraps_across_multiple_lines"
            with mock.patch("sys.stdout", io.StringIO()):
                self.assertEqual(migrations.cmd_new(work, long_name, remote="origin", fetch=False), 0)
                self.assertEqual(migrations.cmd_new(work, "follow_up_change", remote="origin", fetch=False), 0)

            migrate_rs = migrate_rs_path.read_text(encoding="utf-8")
            v4_pos = migrate_rs.index("const V4_ACCOUNT_TRACKED_ISIN")
            v5_pos = migrate_rs.index(f"const V5_{long_name.upper()}")
            v6_pos = migrate_rs.index("const V6_FOLLOW_UP_CHANGE")
            self.assertLess(v4_pos, v5_pos)
            self.assertLess(v5_pos, v6_pos)
            self.assertIn('version: "5",', migrate_rs)
            self.assertIn('version: "6",', migrate_rs)

    def test_register_sqlite_migration_works_on_real_migrate_rs(self) -> None:
        real = (migrations.REPO_ROOT / migrations.SQLITE_MIGRATE_RS_REL).read_text(encoding="utf-8")
        updated = migrations.register_sqlite_migration(real, 999, "example_migration")
        self.assertIn('const V999_EXAMPLE_MIGRATION: &str = include_str!("migrations/V999__example_migration.sql");', updated)
        self.assertIn('version: "999",', updated)
        with self.assertRaises(migrations.MigrationToolError):
            migrations.register_sqlite_migration(updated, 999, "example_migration")


if __name__ == "__main__":
    unittest.main()
