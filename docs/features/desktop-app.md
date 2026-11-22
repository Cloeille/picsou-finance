# Feature: Desktop app (Linux shell)

> Last updated: 2026-11-15

## Context

Issue #235: Picsou for people who never open a terminal. This first slice of #587 is the Tauri
shell on Linux: it starts PostgreSQL and the backend locally and shows the app in a window.
The tray, launch at login and "close keeps running" come in the next slice. The choices are in
the [ADR](../decisions/2026-11-15-desktop-app-tauri.md).

## How it works

1. The window opens on `desktop/ui/index.html` ("Starting Picsou").
2. A thread runs `supervisor::start`:
   - creates the app-data folder (`~/.local/share/com.picsou.desktop`) and `secrets/`, both 0700;
   - generates `secrets/postgres_password` (0600) once;
   - first start only: `initdb` into `postgres.init/`, `CREATE DATABASE picsou` in single-user
     mode, then renames it to `postgres/`, so an interrupted first start is redone, never reused;
   - starts `postgres` on `127.0.0.1` and a free port, no Unix socket;
   - starts `java -jar app.jar` with `SERVER_ADDRESS=127.0.0.1`, the port of the last start when
     still free (`desktop-port`, so the window keeps its origin and local storage), plain-HTTP
     cookies, `PICSOU_SECRETS_DIR`, and the startup sync off (#585 catches up instead);
   - waits for PostgreSQL's "ready to accept connections" (an open port is not enough during
     crash recovery), then for `/actuator/health/readiness`, then the window navigates to the backend.
3. Quitting stops the backend (SIGTERM), then PostgreSQL with a fast shutdown (SIGINT). On Linux,
   both children also die with the app if it crashes or is killed (`PR_SET_PDEATHSIG`).

### Key files

- `desktop/src-tauri/src/supervisor.rs` — processes, ports, secrets, first start
- `desktop/src-tauri/src/main.rs` — the window and the startup thread
- `desktop/scripts/fetch_runtime.py` — builds `desktop/runtime` from the Docker release pins
- `desktop/ui/index.html` — the waiting and error page

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Window loads the Spring URL | The SPA is not forked: same build as Docker | Bundling `dist` in Tauri, which would need a second API origin |
| zonky PostgreSQL binaries | Relocatable, per-OS, checksummed on Maven Central | Distribution packages (not relocatable) |
| Database created in single-user mode | The bundle has no client tools | Shipping `psql`/`createdb` |
| Versions from `sidecars.py --desktop` | The desktop follows the Docker release with no copy | Pins in the desktop build |

## Gotchas / Pitfalls

- **`PR_SET_PDEATHSIG` is per thread.** It fires when the thread that spawned the child ends.
  The startup thread parks forever after `start`; a thread that returns kills PostgreSQL and the
  backend half a second later (seen in the first real run).
- `initdb` refuses to run as root, so the app cannot either.
- macOS and Windows: no parent-death guard and a hard stop yet (#591).
- A startup error can come before `ui/index.html` finished loading: it is kept and shown again
  from `on_page_load`.
- Logs grow without rotation; two launches at once fail on the cluster lock (slice 2 adds
  single-instance).

## Tests

- `cargo test` in `desktop/src-tauri` (CI job `desktop`): backend environment (loopback, data
  paths), secret generated once and 0600, port reuse, quitting during startup.
- Manual run: `make desktop`, or `PICSOU_DESKTOP_RUNTIME=../runtime cargo run` from `desktop/src-tauri`.
