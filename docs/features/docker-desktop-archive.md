# Feature: Encrypted archive to move between Docker and desktop

> Last updated: 2026-11-28

## Context

A Docker install (PostgreSQL or SQLite) and the desktop app (SQLite) each keep their data and
their encryption key in places the other does not read. `picsou export-archive` packs one instance
into a single passphrase-protected file, and `picsou import-archive` restores it on a SQLite
install. Slice 1 of #865: the commands. Switching from the desktop app back to Docker, and any
in-app button, come later.

## How it works

### Usage

The passphrase comes from `PICSOU_ARCHIVE_PASSPHRASE` only (at least 12 characters, never
printed). The commands read the database from `DATABASE_URL` or `SPRING_DATASOURCE_URL`, like
`picsou migrate`.

Docker, export (PostgreSQL or SQLite source):

```bash
docker compose exec -e PICSOU_ARCHIVE_PASSPHRASE='a long passphrase' app \
  picsou export-archive /data/picsou.picsou-archive
docker compose cp app:/data/picsou.picsou-archive .
```

The archive holds the database, `crypto_key`, and the Enable Banking key when the instance has
one. It never holds `jwt_secret`, `sidecar_api_key`, `postgres_password`, logs or backups.

Desktop, import (the app closed). Set the variables the desktop shell sets, with `<data>` the
app's data directory:

```bash
PICSOU_ARCHIVE_PASSPHRASE='a long passphrase' \
DATABASE_URL=sqlite:///<data>/picsou.db \
PICSOU_SECRETS_DIR=<data>/secrets \
APP_CRYPTO_KEY_PATH=<data>/secrets/crypto_key \
ENABLEBANKING_PRIVATE_KEY_PATH=<data>/secrets/enablebanking-private.pem \
picsou import-archive picsou.picsou-archive [--replace]
```

An import into a database that has any user is refused unless `--replace` is given. With
`--replace` (or on an empty target) the old `picsou.db` (with `-wal`/`-shm`) and keys move to
`<data>/backups/<unix-seconds>-before-import/` first. The target's `jwt_secret` is kept: users
sign in again.

### Key files

- `backend-rs/src/db/archive.rs` — format, seal/open, export and import
- `backend-rs/src/db/release_cli.rs` — the two subcommands, messages and exit codes
- `backend-rs/src/db/sqlite/export_pg.rs` — PostgreSQL to SQLite conversion reused by the export

### Flow

```
export: PostgreSQL --export_pg_to_sqlite--> temp picsou.db ┐
        SQLite     --VACUUM INTO----------> temp picsou.db ┤
        + crypto_key (+ pem) + manifest.json --zip--> seal --> out (0600, temp file + rename)

import: open (passphrase) -> manifest checks -> refuse non-empty target unless --replace
        -> move old files to backups/ -> place db + keys -> migrate, verify, integrity,
           row counts == manifest -> on any failure restore the moved files
```

Format: `PICSOUA1 | salt (32) | nonce (12) | AES-256-GCM(zip)`, key = PBKDF2-HMAC-SHA256 (600 000
iterations) of the passphrase. The header is authenticated. `manifest.json` carries
`format_version` (1), `app_version`, `source_engine`, `sqlite_migration` (latest applied), per-table
row counts and `created_at`.

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Seal the whole zip | Table names, counts and the key stay hidden; one check covers every entry | Encrypt only the key file |
| Passphrase from the environment | No new dependency and no prompt to script around | Interactive prompt (needs a terminal crate) |
| `VACUUM INTO` for a SQLite source | A consistent snapshot of a live WAL database | Copying `picsou.db` and its `-wal` |
| Refuse a newer format or migration before writing | An older binary must not open data it cannot read | Import then fail in `migrate` |

## Gotchas / Pitfalls

- A wrong passphrase and a modified file give the same error: nothing is written.
- An archive from a newer Picsou (format or SQLite migration unknown to this binary) is refused:
  update Picsou first.
- Importing into PostgreSQL is not supported yet (#865): the command stops with an error.
- The archive contains the encryption key: keep the file and the passphrase apart.
- The Docker default Enable Banking key path is `/data/keys/enablebanking-private.pem`; on the
  desktop the key is placed at `ENABLEBANKING_PRIVATE_KEY_PATH`.

## Tests

- Unit (`db/archive.rs`): seal/open round trip, wrong passphrase, tampered byte, short
  passphrase, newer format, unknown migration.
- Integration (`tests/integration/archive.rs`): SQLite to SQLite, PostgreSQL to SQLite, refusal of
  a non-empty target, `--replace` backups, rollback on a failed verification.

## Links

- Related ADR: [Dual-engine database](../decisions/2026-11-22-sqlite-dual-engine.md)
- Related feature: [Desktop app](./desktop-app.md)
- Ticket: #865
