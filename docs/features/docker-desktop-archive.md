# Feature: Encrypted archive to move between Docker and desktop

> Last updated: 2026-11-28

## Context

A Docker install (PostgreSQL or SQLite) and the desktop app (SQLite) each keep their data and
their encryption key in places the other does not read. `picsou export-archive` packs one instance
into a single passphrase-protected file, and `picsou import-archive` restores it on a SQLite
install or into a Docker PostgreSQL database. Slices 1 and 3 of #865: the commands, for a SQLite
and for a PostgreSQL target. Any in-app button comes later.

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

Docker, import into PostgreSQL (slice 3). Copy the archive into the container, import, then
restart the app so its caches and the encryption key are reloaded:

```bash
docker compose cp picsou.picsou-archive app:/data/
docker compose exec -e PICSOU_ARCHIVE_PASSPHRASE='a long passphrase' app \
  picsou import-archive /data/picsou.picsou-archive --replace
docker compose restart app
```

Without `--replace` the import is refused when the database has any user. The command runs
Flyway on the target first, so an archive from a SQLite install (or an older Picsou) lands on the
current schema. `crypto_key` goes to `APP_CRYPTO_KEY_PATH` or `PICSOU_SECRETS_DIR/crypto_key`;
the old key moves to `backups/<unix-seconds>-before-import/` beside `PICSOU_SECRETS_DIR`.
`CRYPTO_ENCRYPTION_KEY`, when set and different, is refused, as for SQLite.

### Key files

- `backend-rs/src/db/archive.rs` — format, seal/open, export and import
- `backend-rs/src/db/archive_pg.rs` — the load into PostgreSQL (SQLite value to bind, FK order,
  sequences, counts)
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

PostgreSQL import, after the same open and checks (no PostgreSQL access before they pass):

```
extract picsou.db to a 0700 scratch dir beside PICSOU_SECRETS_DIR (temp dir if unset)
  -> SQLite migrate + verify_schema + integrity (this binary's level)
  -> refuse non-empty target (any app_user) unless --replace
  -> Flyway migrate + schema.tsv check on the target
  -> place keys (old ones to backups/)
  -> ONE transaction: TRUNCATE every schema.tsv table RESTART IDENTITY CASCADE,
     INSERT parents first, reset every sequence to max(id), counts == manifest == SQLite
  -> commit, or roll back the transaction and put the old keys back
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
| Insert parents first, self-references set afterwards | No foreign key is deferrable, and `session_replication_role = replica` needs a superuser the app role is not | `SET CONSTRAINTS ALL DEFERRED`; disabling triggers |
| Place keys before the PostgreSQL commit, restore them on rollback | Restoring local files cannot fail halfway; a commit followed by a failed key write would leave data the key cannot decrypt | Place keys after the commit |
| No PostgreSQL backup copy | The transaction is the rollback; `pg_dump` is the operator's tool | Dump the old rows into the archive directory |
| Refuse a newer format or migration before writing | An older binary must not open data it cannot read | Import then fail in `migrate` |

## Gotchas / Pitfalls

- A wrong passphrase and a modified file give the same error: nothing is written.
- An archive from a newer Picsou (format or SQLite migration unknown to this binary) is refused:
  update Picsou first.
- Importing into PostgreSQL gives no backup copy of the old rows: `--replace` empties every
  application table first. Take a `pg_dump` before if you may want them back.
- A PostgreSQL import is all or nothing for the data (one transaction), but the app keeps its old
  caches and key until restarted: restart it after the import.
- The archive contains the encryption key: keep the file and the passphrase apart.
- The Docker default Enable Banking key path is `/data/keys/enablebanking-private.pem`; on the
  desktop the key is placed at `ENABLEBANKING_PRIVATE_KEY_PATH`.

## Tests

- Unit (`db/archive.rs`): seal/open round trip, wrong passphrase, tampered byte, short
  passphrase, newer format, unknown migration.
- Integration (`tests/integration/archive.rs`): SQLite to SQLite, PostgreSQL to SQLite, refusal of
  a non-empty target, `--replace` backups, rollback on a failed verification; SQLite to an empty
  PostgreSQL (decimal, boolean, timestamp, blob, self-reference, sequences), PostgreSQL to
  PostgreSQL with `--replace` and refusal without it, rollback of rows and key on a failed load.

## Links

- Related ADR: [Dual-engine database](../decisions/2026-11-22-sqlite-dual-engine.md)
- Related feature: [Desktop app](./desktop-app.md)
- Ticket: #865
