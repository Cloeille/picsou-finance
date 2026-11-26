#!/usr/bin/env python3
"""Scaffold paired PostgreSQL and SQLite database migrations."""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import shutil
import subprocess
import sys

REPO_ROOT = Path(__file__).resolve().parents[2]
PG_MIGRATIONS_REL = Path("backend-rs/migrations")
SQLITE_MIGRATIONS_REL = Path("backend-rs/src/db/sqlite/migrations")
SQLITE_MIGRATE_RS_REL = Path("backend-rs/src/db/sqlite/migrate.rs")

NAME_RE = re.compile(r"^[a-z][a-z0-9]*(_[a-z0-9]+)*$")
MIGRATION_FILE_RE = re.compile(r"^V([0-9]+(?:[._][0-9]+)*)__(.+)\.sql$")

SQLITE_CONST_RE = re.compile(
    r'^const\s+V\d+_[A-Z0-9_]+\s*:\s*&str\s*=\s*include_str!\(\s*"migrations/V\d+__[^"]+\.sql"\s*,?\s*\);',
    flags=re.MULTILINE,
)
BASELINE_CONST_RE = re.compile(
    r'^pub\s+const\s+BASELINE_SCHEMA\s*:\s*&str\s*=\s*include_str!\(\s*"schema\.sql"\s*,?\s*\);',
    flags=re.MULTILINE,
)


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
    const_matches = list(SQLITE_CONST_RE.finditer(migrate_rs))
    if const_matches:
        insert_at = const_matches[-1].end()
    else:
        baseline = BASELINE_CONST_RE.search(migrate_rs)
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
        "-- Write PostgreSQL DDL/DML below, then update backend-rs/src/db/schema.tsv and run `make test-backend`.\n",
        encoding="utf-8",
        newline="\n",
    )
    sqlite_path.write_text(
        f"-- TODO: describe V{next_sqlite}__{name} (PostgreSQL V{next_pg}__{name}).\n"
        "-- Write SQLite DDL/DML below (use TEXT for NUMERIC columns; switch migrate.rs to\n"
        "-- MigrationMode::TableRebuild if rebuilding a table), then run `make test-backend`.\n",
        encoding="utf-8",
        newline="\n",
    )
    migrate_rs_path.write_text(migrate_rs_after, encoding="utf-8", newline="\n")
    if rustfmt := shutil.which("rustfmt"):
        subprocess.run([rustfmt, "--edition", "2024", str(migrate_rs_path)], check=False)

    print(
        f"Created {pg_path.relative_to(repo_root)}\n"
        f"Created {sqlite_path.relative_to(repo_root)}\n"
        f"Updated {migrate_rs_path.relative_to(repo_root)}\n"
        "Next: edit both SQL files (switch MigrationMode::TableRebuild if needed), "
        "update backend-rs/src/db/schema.tsv (PICSOU_WRITE_SCHEMA=1 cargo test the_expected_schema), "
        "and run `make test-backend`."
    )
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Picsou database migration helper.")
    sub = parser.add_subparsers(dest="command", required=True)
    new_p = sub.add_parser("new", help="Scaffold the next paired PostgreSQL + SQLite migration.")
    new_p.add_argument("name", help="Migration description in snake_case.")
    new_p.add_argument("--remote", default="origin", help="Git remote to inspect (default: origin).")
    new_p.add_argument("--dry-run", action="store_true", help="Print planned versions/files without writing.")
    new_p.add_argument("--no-fetch", action="store_true", help="Skip git fetch --prune.")
    return parser


def main(argv: list[str] | None = None, *, repo_root: Path = REPO_ROOT) -> int:
    args = build_parser().parse_args(argv)
    try:
        return cmd_new(repo_root, args.name, remote=args.remote, dry_run=args.dry_run, fetch=not args.no_fetch)
    except MigrationToolError as err:
        print(f"error: {err}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
