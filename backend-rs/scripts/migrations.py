#!/usr/bin/env python3
"""Scaffold paired PostgreSQL/SQLite migrations and regenerate schema fixtures."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zlib

REPO_ROOT = Path(__file__).resolve().parents[2]
PG_MIGRATIONS_REL = Path("backend-rs/migrations")
SQLITE_MIGRATIONS_REL = Path("backend-rs/src/db/sqlite/migrations")
SQLITE_MIGRATE_RS_REL = Path("backend-rs/src/db/sqlite/migrate.rs")
SCHEMA_TSV_REL = Path("backend-rs/src/db/schema.tsv")
FLYWAY_HISTORY_REL = Path("backend-rs/tests/integration/fixtures/java_flyway_history.tsv")

NAME_RE = re.compile(r"^[a-z][a-z0-9]*(_[a-z0-9]+)*$")
MIGRATION_FILE_RE = re.compile(r"^V([0-9]+(?:[._][0-9]+)*)__(.+)\.sql$")

SCHEMA_TEST = "foundation::migrations::the_expected_schema_is_the_one_the_migrations_build"
HISTORY_TEST = "foundation::migrations::writes_the_history_flyway_writes"
SQLITE_PARITY_TEST = "sqlite_foundation::schema_parity_matches_postgresql_schema_tsv"


class MigrationToolError(RuntimeError):
    """Raised on validation, git, or file-structure errors."""


def validate_name(name: str) -> str:
    if not NAME_RE.fullmatch(name):
        raise MigrationToolError(
            f"invalid migration NAME {name!r}: expected lowercase snake_case "
            "(letters, digits, single underscores, starting with a letter)"
        )
    return name


def parse_leading_version(filename: str) -> int | None:
    match = MIGRATION_FILE_RE.fullmatch(filename)
    if not match:
        return None
    return int(re.split(r"[._]", match.group(1), maxsplit=1)[0])


def flyway_version_key(raw_version: str) -> tuple[int, ...]:
    """Numeric Flyway version key matching backend-rs/src/db/flyway.rs::Version."""
    parts = [int(p) for p in raw_version.replace("_", ".").split(".")]
    while parts and parts[-1] == 0:
        parts.pop()
    return tuple(parts)


def flyway_checksum(content: str) -> int:
    """Signed 32-bit CRC32 excluding CR/LF and leading BOM (matches flyway.rs::checksum)."""
    stripped = content.removeprefix("\ufeff").encode("utf-8").translate(None, b"\r\n")
    crc = zlib.crc32(stripped) & 0xFFFFFFFF
    return crc - 0x100000000 if crc >= 0x80000000 else crc


def _run_git(repo_root: Path, *args: str) -> str:
    result = subprocess.run(["git", *args], cwd=repo_root, capture_output=True, text=True)
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or f"exit {result.returncode}"
        raise MigrationToolError(f"git {' '.join(args)} failed: {detail}")
    return result.stdout


def list_target_refs(repo_root: Path, remote: str, *, fetch: bool) -> list[str]:
    if fetch:
        _run_git(repo_root, "fetch", "--prune", remote)
    main_ref = f"refs/remotes/{remote}/main"
    output = _run_git(
        repo_root, "for-each-ref", "--format=%(refname)", main_ref, f"refs/remotes/{remote}/release/*"
    )
    refs = [line.strip() for line in output.splitlines() if line.strip()]
    if main_ref not in refs:
        raise MigrationToolError(
            f"remote branch '{remote}/main' not found; check REMOTE (e.g. REMOTE=upstream on a fork)"
        )
    return refs


def _versions_in_ref(repo_root: Path, ref: str, rel_dir: Path) -> set[int]:
    output = _run_git(repo_root, "ls-tree", "-z", "--name-only", ref, "--", f"{rel_dir.as_posix()}/")
    return {v for e in output.split("\0") if e and (v := parse_leading_version(Path(e).name)) is not None}


def _versions_in_dir(directory: Path) -> set[int]:
    if not directory.is_dir():
        return set()
    return {v for e in directory.iterdir() if e.is_file() and (v := parse_leading_version(e.name)) is not None}


def compute_next_versions(repo_root: Path, remote: str, *, fetch: bool = True) -> tuple[int, int, list[str]]:
    """Compute the next free (pg_version, sqlite_version) across main, release/* and local tree."""
    refs = list_target_refs(repo_root, remote, fetch=fetch)
    pg_versions: set[int] = set()
    sqlite_versions: set[int] = {1}  # V1 is the frozen baseline in schema.sql
    for ref in refs:
        pg_versions |= _versions_in_ref(repo_root, ref, PG_MIGRATIONS_REL)
        sqlite_versions |= _versions_in_ref(repo_root, ref, SQLITE_MIGRATIONS_REL)
    pg_versions |= _versions_in_dir(repo_root / PG_MIGRATIONS_REL)
    sqlite_versions |= _versions_in_dir(repo_root / SQLITE_MIGRATIONS_REL)
    return (max(pg_versions) if pg_versions else 0) + 1, max(sqlite_versions) + 1, refs


def register_sqlite_migration(migrate_rs: str, sqlite_version: int, name: str) -> str:
    """Register V<sqlite_version>__<name>.sql in backend-rs/src/db/sqlite/migrate.rs."""
    const_name = f"V{sqlite_version}_{name.upper()}"
    include = f'include_str!("migrations/V{sqlite_version}__{name}.sql");'
    if re.search(rf"\bconst\s+{re.escape(const_name)}\b", migrate_rs):
        raise MigrationToolError(f"{const_name} is already declared in migrate.rs")

    single = f"const {const_name}: &str = {include}"
    const_decl = single if len(single) <= 100 else f"const {const_name}: &str =\n    {include}"
    const_matches = list(re.finditer(
        r'^const V\d+_[A-Z0-9_]+: &str =\s*\n?\s*include_str!\("migrations/V\d+__[^"]+\.sql"\);',
        migrate_rs, flags=re.MULTILINE,
    ))
    if const_matches:
        insert_at = const_matches[-1].end()
    else:
        baseline = re.search(
            r'^pub const BASELINE_SCHEMA: &str = include_str!\("schema\.sql"\);', migrate_rs, flags=re.MULTILINE
        )
        if not baseline:
            raise MigrationToolError("could not find BASELINE_SCHEMA or migration constants in migrate.rs")
        insert_at = baseline.end()

    updated = f"{migrate_rs[:insert_at]}\n\n{const_decl}{migrate_rs[insert_at:]}"
    fn_match = re.search(
        r"(pub fn migrations\(\) -> Vec<SqliteMigration> \{\n\s*vec!\[\n)(.*?)(\n    \]\n\})",
        updated, flags=re.DOTALL,
    )
    if not fn_match:
        raise MigrationToolError("could not find `pub fn migrations()` vec! block in migrate.rs")
    if re.search(rf'\bversion:\s*"{sqlite_version}"', fn_match.group(2)):
        raise MigrationToolError(f'SQLite migration version "{sqlite_version}" is already in migrations()')

    entry = (
        "\n        SqliteMigration {\n"
        f'            version: "{sqlite_version}",\n'
        f'            description: "{name.replace("_", " ")}",\n'
        f"            sql: {const_name},\n"
        f"            checksum: sha256_checksum({const_name}),\n"
        "            mode: MigrationMode::Transactional,\n"
        "        },"
    )
    return f"{updated[:fn_match.start(3)]}{entry}{updated[fn_match.start(3):]}"


def cmd_new(
    repo_root: Path, name: str, *, remote: str = "origin", dry_run: bool = False, fetch: bool = True
) -> int:
    validate_name(name)
    next_pg, next_sqlite, refs = compute_next_versions(repo_root, remote, fetch=fetch)
    pg_path = repo_root / PG_MIGRATIONS_REL / f"V{next_pg}__{name}.sql"
    sqlite_path = repo_root / SQLITE_MIGRATIONS_REL / f"V{next_sqlite}__{name}.sql"
    migrate_rs_path = repo_root / SQLITE_MIGRATE_RS_REL

    for path in (pg_path, sqlite_path):
        if path.exists():
            raise MigrationToolError(f"file already exists: {path.relative_to(repo_root)}")
    if not migrate_rs_path.is_file():
        raise MigrationToolError(f"missing SQLite migrator: {SQLITE_MIGRATE_RS_REL}")

    migrate_rs_after = register_sqlite_migration(migrate_rs_path.read_text(encoding="utf-8"), next_sqlite, name)

    short_refs = ", ".join(r.removeprefix("refs/remotes/") for r in refs)
    print(f"Checked refs: {short_refs} + working tree")
    print(f"Next PostgreSQL migration: V{next_pg}__{name}.sql")
    print(f"Next SQLite migration:     V{next_sqlite}__{name}.sql")
    if dry_run:
        print(f"Dry run — no files written:\n  create {pg_path.relative_to(repo_root)}\n"
              f"  create {sqlite_path.relative_to(repo_root)}\n  update {migrate_rs_path.relative_to(repo_root)}")
        return 0

    pg_path.write_text(
        f"-- TODO: describe V{next_pg}__{name} (SQLite counterpart: backend-rs/src/db/sqlite/migrations/V{next_sqlite}__{name}.sql).\n"
        "-- Write PostgreSQL DDL/DML below, then run `make sync-schema`.\n",
        encoding="utf-8",
    )
    sqlite_path.write_text(
        f"-- TODO: describe V{next_sqlite}__{name} (PostgreSQL V{next_pg}__{name}).\n"
        "-- Write SQLite DDL/DML below (use TEXT for NUMERIC columns; switch migrate.rs to\n"
        "-- MigrationMode::TableRebuild if rebuilding a table), then run `make sync-schema`.\n",
        encoding="utf-8",
    )
    migrate_rs_path.write_text(migrate_rs_after, encoding="utf-8")
    if rustfmt := shutil.which("rustfmt"):
        subprocess.run([rustfmt, "--edition", "2024", str(migrate_rs_path)], check=False)

    print(f"Created {pg_path.relative_to(repo_root)}\nCreated {sqlite_path.relative_to(repo_root)}\n"
          f"Updated {migrate_rs_path.relative_to(repo_root)}\n"
          "Next: edit both SQL files (switch MigrationMode::TableRebuild if needed), then run `make sync-schema`.")
    return 0


def render_flyway_history(migrations_dir: Path) -> str:
    """Render java_flyway_history.tsv for every migration in backend-rs/migrations/."""
    if not migrations_dir.is_dir():
        raise MigrationToolError(f"migrations directory not found: {migrations_dir}")
    entries: list[tuple[tuple[int, ...], str, str, str, int]] = []
    seen_keys: dict[tuple[int, ...], str] = {}
    for path in sorted(migrations_dir.iterdir()):
        if not path.is_file():
            continue
        match = MIGRATION_FILE_RE.fullmatch(path.name)
        if not match:
            raise MigrationToolError(f"migration file {path.name!r} is not named V<version>__<description>.sql")
        raw_ver, raw_desc = match.group(1), match.group(2)
        key = flyway_version_key(raw_ver)
        if key in seen_keys:
            raise MigrationToolError(f"duplicate migration version {raw_ver}: {seen_keys[key]} and {path.name}")
        seen_keys[key] = path.name
        sql = path.read_text(encoding="utf-8", newline="")
        entries.append((key, raw_ver, raw_desc.replace("_", " "), path.name, flyway_checksum(sql)))
    if not entries:
        raise MigrationToolError(f"no migrations found in {migrations_dir}")
    entries.sort(key=lambda item: item[0])
    return "\n".join(
        f"{rank}\t{ver}\t{desc}\tSQL\t{script}\t{cs}\tt"
        for rank, (_, ver, desc, script, cs) in enumerate(entries, start=1)
    )


def _run_cargo_tests(backend_dir: Path, test_names: list[str], *, write_schema: bool) -> int:
    env = os.environ.copy()
    if write_schema:
        env["PICSOU_WRITE_SCHEMA"] = "1"
    else:
        env.pop("PICSOU_WRITE_SCHEMA", None)
    cmd = ["cargo", "test", "--locked", "--test", "integration", "--", "--exact", *test_names]
    return subprocess.run(cmd, cwd=backend_dir, env=env).returncode


def cmd_sync_schema(repo_root: Path, *, dry_run: bool = False) -> int:
    history_path = repo_root / FLYWAY_HISTORY_REL
    schema_path = repo_root / SCHEMA_TSV_REL
    backend_dir = repo_root / "backend-rs"

    expected_history = render_flyway_history(repo_root / PG_MIGRATIONS_REL).encode("utf-8")
    history_changed = expected_history != (history_path.read_bytes() if history_path.exists() else None)
    if dry_run:
        print(f"{FLYWAY_HISTORY_REL}: {'would update' if history_changed else 'up to date'}")
        print(f"{SCHEMA_TSV_REL}: would run PICSOU_WRITE_SCHEMA=1 cargo test {SCHEMA_TEST}")
        return 0

    if history_changed:
        history_path.parent.mkdir(parents=True, exist_ok=True)
        history_path.write_bytes(expected_history)
        print(f"Updated {FLYWAY_HISTORY_REL}")
    else:
        print(f"Unchanged {FLYWAY_HISTORY_REL}")

    schema_before = schema_path.read_bytes() if schema_path.exists() else None
    rc = _run_cargo_tests(backend_dir, [SCHEMA_TEST], write_schema=True)
    schema_changed = (schema_path.read_bytes() if schema_path.exists() else None) != schema_before
    if rc != 0 and not schema_changed:
        print(
            "sync-schema failed before updating backend-rs/src/db/schema.tsv (ensure PostgreSQL 16 "
            "is running on 127.0.0.1:55432 with trust auth for user picsou and check your migration SQL).",
            file=sys.stderr,
        )
        return rc
    if schema_changed:
        print(f"Updated {SCHEMA_TSV_REL}; re-running verification with the new schema...")
        verify_tests = [SCHEMA_TEST, HISTORY_TEST, SQLITE_PARITY_TEST]
    else:
        print(f"Unchanged {SCHEMA_TSV_REL}; verifying history and SQLite parity...")
        verify_tests = [HISTORY_TEST, SQLITE_PARITY_TEST]
    verify_rc = _run_cargo_tests(backend_dir, verify_tests, write_schema=False)
    if verify_rc == 0:
        print("Schema fixtures and SQLite parity verified.")
    return verify_rc


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Picsou database migration helper.")
    sub = parser.add_subparsers(dest="command", required=True)
    new_p = sub.add_parser("new", help="Scaffold the next paired PostgreSQL + SQLite migration.")
    new_p.add_argument("name", help="Migration description in snake_case.")
    new_p.add_argument("--remote", default="origin", help="Git remote to inspect (default: origin).")
    new_p.add_argument("--dry-run", action="store_true", help="Print planned versions/files without writing.")
    new_p.add_argument("--no-fetch", action="store_true", help="Skip git fetch --prune.")
    sync_p = sub.add_parser("sync-schema", help="Regenerate schema fixtures and verify PG + SQLite parity.")
    sync_p.add_argument("--dry-run", action="store_true", help="Report history diff without writing or running cargo.")
    return parser


def main(argv: list[str] | None = None, *, repo_root: Path = REPO_ROOT) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "new":
            return cmd_new(repo_root, args.name, remote=args.remote, dry_run=args.dry_run, fetch=not args.no_fetch)
        return cmd_sync_schema(repo_root, dry_run=args.dry_run)
    except MigrationToolError as err:
        print(f"error: {err}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
