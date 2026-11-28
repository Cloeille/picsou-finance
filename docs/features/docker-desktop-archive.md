# Feature: Encrypted archive to move between Docker and desktop

> Last updated: 2026-11-28

## Context

A Docker install (PostgreSQL or SQLite) and the desktop app (SQLite) each keep their data and
their encryption key in places the other does not read. `picsou export-archive` packs one instance
into a single passphrase-protected file, and `picsou import-archive` restores it on a SQLite
install or into a Docker PostgreSQL database. Slices 1 to 3 of #865: the commands, for a SQLite
and for a PostgreSQL target, and the HTTP API the setup screen (import) and Settings (export)
call. The buttons themselves come in a frontend slice.

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

### HTTP API

Both routes take the passphrase in the request, never from `PICSOU_ARCHIVE_PASSPHRASE` (only the
commands read that variable). It follows the same rule (at least 12 characters), is never logged,
never echoed in an error and never written to disk. Errors carry a stable `code`.

**`POST /api/setup/import-archive`** (anonymous, `multipart/form-data`: file part `archive`, text
part `passphrase`). Allowed on the first screen only: setup not complete and no account yet
(otherwise `409 SETUP_ALREADY_STARTED`, or `410` once setup is complete). In Desktop Mode it
needs the gate of the app's own window like every `POST /api/setup/**`. Rate-limited like the
other setup routes (10 per minute per IP). The body is not capped by the 1 MiB anonymous cap but
by `PICSOU_ARCHIVE_MAX_UPLOAD_BYTES` (default 1 GiB + 1 MiB); one upload at a time, the file is
held in memory while it is opened.

The route runs every check that needs no access to the target, then **stages** the archive and
asks the server to restart. It never changes the live database or key:

| Status | `code` | Meaning |
|--------|--------|---------|
| 202 | — | `{"restarting": true, "tables": n, "rows": n}`; poll `/api/setup/status` |
| 400 | `PASSPHRASE_REQUIRED`, `PASSPHRASE_TOO_SHORT`, `WRONG_PASSPHRASE`, `NOT_AN_ARCHIVE` | nothing staged |
| 409 | `KEY_OVERRIDDEN` | `CRYPTO_ENCRYPTION_KEY` is set and differs from the archived key |
| 409 | `IMPORT_UNAVAILABLE`, `IMPORT_ALREADY_STAGED`, `IMPORT_IN_PROGRESS`, `SETUP_ALREADY_STARTED` | no data folder, already staged, busy, not a fresh instance |
| 413 | `ARCHIVE_TOO_LARGE` | over the limit, announced or not |
| 422 | `CORRUPT_ARCHIVE`, `ARCHIVE_TOO_NEW`, `VERIFICATION_FAILED` | damaged file, or a newer Picsou is needed |

**Staging.** The decrypted archive goes to `<data dir>/import-pending/archive.zip` (folder 0700,
file 0600), built in a scratch folder and renamed into place so a crash never leaves a half-staged
import. `<data dir>` is the folder of the SQLite file, or the parent of `PICSOU_SECRETS_DIR` on
PostgreSQL (the `/data` volume in Docker); with neither, the route answers
`409 IMPORT_UNAVAILABLE`. Why decrypted and not the sealed file: the passphrase must not be kept
anywhere, and the next start has nobody to ask for it. The staged file holds what `picsou.db`
and `crypto_key` already hold in the same folder, with the same permissions; it is removed once
applied, or moved to `backups/`.

**Restart.** `AppState.restart` (a `RestartSignal`) is set after staging. `run` stops serving
gracefully (requests in flight finish, the database closes) and returns `Exit::Restart` instead
of `Exit::Stopped`.

- Docker and bare metal: `main.rs` loops in-process: it loads the configuration again, binds the
  same address again and calls `serve`. It does not rely on a restart policy of the container.
- Desktop: `Ready::serve` returns `Exit::Restart`, the supervisor stops the sidecars and the
  backend, and the app relaunches itself (`AppHandle::restart`, as the updater does), so the new
  process gets a new gate token and the window reloads from the first screen.

**Applying.** `picsou::start` calls `db::archive::apply_pending` before it opens the database.
It checks the target again, so an account created since the staging makes the import fail
instead of overwriting it, then places the archive as `import-archive` does. One outcome per
staged import, held by the process that applied it:

- done: staging removed, the archived key is loaded (a `CRYPTO_ENCRYPTION_KEY` in the
  environment always wins and was already checked), outcome `done` with the table and row counts.
- failed: the data and keys stay as they were (the import already rolls back), the staging folder
  moves to `backups/<unix-seconds>-failed-import/` (deleted if it cannot move: it is never applied
  twice), outcome `failed` with the error `code`. The server still starts.
- PostgreSQL unreachable: neither; the start fails and the staging is kept for the next start.

**`GET /api/setup/status`** gains `archiveImport`, absent when there is nothing to say:
`{"state": "pending"}` (staged, restarting), `{"state": "done", "tables": n, "rows": n}` or
`{"state": "failed", "code": "..."}`. While setup is not complete every read returns it. Once
setup is complete (the usual case: an archive comes from a working instance) the first read
returns it and clears it. It is memory only: a later restart reports nothing.

**`POST /api/admin/archive`** (admin session, JSON `{"passphrase", "reAuth"}`). Same proof as
`POST /api/me/export`: `reAuth` (password, and TOTP when 2FA is on) or the `reauth_proof` cookie;
`401 REAUTH_FAILED` otherwise. Limited like the other exports (5 per hour per user). The sealed
file is built in a scratch folder under the data folder and streamed as
`picsou-<date>.picsou-archive` (`application/octet-stream`, `Cache-Control: no-store`); the folder
is deleted when the stream ends or the client drops. Works on a PostgreSQL and a SQLite source.
In Desktop Mode, the quick-start account has a random password nobody knows: the window's gate
stands in for the proof (`reauth::verify`, the same path as `/api/me/export`), so a request from
the app's own window needs no `reAuth`. A browser on the same machine without the gate does need a
password: the UI asks the user to set one in Settings first.

### UI

Both screens reuse the same pieces; nothing here adds a design language.

**Import (first screen only).** `ImportArchiveEntry` renders a secondary ghost button, "J'ai déjà
des données Picsou" / "I already use Picsou", under the primary action of the desktop welcome
(`DesktopWelcomeStep`) and of the Docker wizard intro (`SetupStepIntro`). It shows only while
`GET /api/setup/status` says `state: PENDING_ADMIN` and `needsSetup`, and never in demo mode.
`ImportArchiveDialog` (`pages/setup/components/`) is the single dialog behind it:

1. **Form.** File picker (`accept=".picsou-archive"`, name and size shown), passphrase field with
   the show toggle (`SecretInput`), at least 12 characters (counted like the server, in characters),
   and a short note: data, settings and bank connections are restored, you sign in again with your
   previous account, and the server (Docker) or the whole app window (desktop) restarts.
2. **Upload.** `POST /api/setup/import-archive` as multipart with `onUploadProgress`; the dialog
   cannot be closed meanwhile. A refusal keeps the form open with the file and an error mapped from
   its `code` (`setup.archive.errors.<CODE>`, one message per code of the table above, plus
   rate limit, 410, 403 and a dropped connection); the passphrase is never displayed back.
3. **Restart.** "Import en cours" while `waitForArchiveImport` polls `GET /api/setup/status` every
   1.5 s for up to 5 minutes. Network errors are expected (Docker restarts in-process, the desktop
   relaunches its window, which may simply reload the page) and only switch the line to "waiting for
   the server". `pending` keeps waiting; a reload during the restart reopens the dialog on it.
4. **Outcome.** `done`: tables and rows restored, button to `/login` (the imported accounts; the
   wizard's cached status is invalidated only then, as it would unmount the dialog earlier).
   `failed`: the message for its `code`, "none of your current data was changed", retry. Timeout:
   check again. A `done` already read by another request (complete setup, no report) is still
   shown as done, using the counts of the 202.

**Export (Settings, Security tab, administrators, not in demo).** `ArchiveExportCard` sits under the
existing security card and opens `ExportArchiveDialog`: archive passphrase and its confirmation
(at least 12 characters, must match), a note that the file holds the key protecting the bank
credentials and that the passphrase cannot be recovered, then the proof of `ExportDataDialog`:
password (or TOTP when 2FA is on), or `ReauthPanel` for a password-less account (the IdP round
trip returns with `/settings?resume=archive`, which reopens the dialog confirmed). Desktop without
the password lock asks for nothing: the window stands for the proof. If the server refuses it
anyway (401 on an account that is not the one autologin signs in), the password field appears and
the dialog points to the "Desktop security" lock card to set one first. The file is downloaded as a
blob under the name of `Content-Disposition` (`picsou-<date>.picsou-archive`); a refusal arrives as
a blob too, so `exportApi.downloadArchive` reads its JSON back to expose the `code`.

### Key files

- `backend-rs/src/db/archive.rs` — format, seal/open, export and import, staging and applying
- `backend-rs/src/setup_auth/archive.rs` — the import route and the status it adds
- `backend-rs/src/admin/archive.rs` — the export route
- `frontend/src/features/setup/{api,archive-import,hooks}.ts` — the upload, the status polling and its outcome
- `frontend/src/pages/setup/components/Import{ArchiveEntry,ArchiveDialog}.tsx` — the entry point and the dialog
- `frontend/src/pages/settings/security/{ArchiveExportCard,ExportArchiveDialog}.tsx` — the export
- `backend-rs/src/lib.rs` — `RestartSignal`, `Exit`, `start` applying a staged import
- `backend-rs/src/main.rs`, `desktop/src-tauri/src/{supervisor,main}.rs` — the restart
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
| Stage the decrypted zip, apply at the next start | The running server holds the database and the key; the passphrase is never stored | Keep the sealed file and the passphrase until the restart; swap the database live |
| Restart in-process on Docker | Works without a restart policy | `exit(0)` and rely on `restart: unless-stopped` |
| Re-check the target when applying | An account created between staging and start is never overwritten | `--replace` semantics |

## Gotchas / Pitfalls

- A wrong passphrase and a modified file give the same error: nothing is written.
- An archive from a newer Picsou (format or SQLite migration unknown to this binary) is refused:
  update Picsou first.
- Importing into PostgreSQL gives no backup copy of the old rows: `--replace` empties every
  application table first. Take a `pg_dump` before if you may want them back.
- A PostgreSQL import is all or nothing for the data (one transaction), but the app keeps its old
  caches and key until restarted: restart it after the import (the HTTP import does it itself).
- A staged import is applied by the next start, wherever it comes from: removing
  `<data dir>/import-pending/` cancels it.
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
- Integration (`tests/integration/archive_api.rs`): staging and restart request without touching
  the data; wrong, short or missing passphrase, not an archive, overridden key, missing file
  (nothing staged, no restart); refusal once an account exists or setup is complete; the upload
  limit versus the 1 MiB anonymous cap; rate limit; Desktop Mode gate; the next start applying a
  staged import into SQLite and PostgreSQL; a failed apply keeping the data, moving the staging
  aside and reporting `failed` once; a corrupt staging; `run` returning `Exit::Restart`; the
  export (admin only, re-authentication, file read back by `import_archive`, no scratch left, the
  desktop quick-start account).

## Links

- Related ADR: [Dual-engine database](../decisions/2026-11-22-sqlite-dual-engine.md)
- Related feature: [Desktop app](./desktop-app.md)
- Ticket: #865
