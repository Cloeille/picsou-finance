# Feature: Desktop app (Linux shell)

> Last updated: 2026-11-15

## Context

Issue #235: Picsou for people who never open a terminal. This first slice of #587 is the Tauri
shell on Linux: it starts PostgreSQL and the backend locally, shows the app in a window, and
keeps running in the tray. The choices are in
the [ADR](../decisions/2026-11-15-desktop-app-tauri.md).

## How it works

1. One instance only: a second launch brings the running window forward
   (`tauri-plugin-single-instance`). The window opens on `desktop/ui/index.html`
   ("Starting Picsou"), except when the login item started the app with `--hidden`.
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
3. The tray menu has Open Picsou, Launch at login and Quit Picsou. Closing the window hides
   it; only Quit stops the processes. Launch at login (`tauri-plugin-autostart`, an XDG
   autostart entry on Linux) is switched on at the first start only (`autostart-default`
   marker), so turning it off sticks. The check mark is read back from the OS entry.
4. Quitting stops the backend (SIGTERM), then PostgreSQL with a fast shutdown (SIGINT). On Linux,
   both children also die with the app if it crashes or is killed (`PR_SET_PDEATHSIG`).

### Updates and failed migrations (#588)

- `app-version` in the data folder names the last version that reached the dashboard. It moves
  only after the backend's readiness probe answers.
- A start with another version copies `postgres/` and `secrets/` to
  `backups/<seconds>-<from>-to-<to>/` before PostgreSQL starts, so the copy is consistent. The
  copy is written to `*.partial` and renamed when complete. A retried update keeps the copy of
  its first try. The last two copies are kept.
- An older version on newer data does not start PostgreSQL: the window names the version to
  install and the data is untouched. Flyway's own refusal (#554) gives the same message.
- A Flyway failure after an update shows its reason and a "Restore my data from before the
  update" button. Restoring stops the processes, moves the failed `postgres/` and `secrets/`
  aside (`*.failed-<seconds>-<n>`, never deleted: delete them by hand once the restore works) and
  puts the backup in place. The copy is staged as `*.restoring` first, so a failed restore
  changes nothing. Copies keep the folders' modes (PostgreSQL requires 0700) and skip
  `postmaster.pid`. Data without an `app-version` marker counts as an update from `unknown`. The user then
  reinstalls the previous version.
- The button calls the `restore_backup` command. IPC is open only to the bundled `ui/` page
  (`capabilities/error-page.json` lists no remote URL), so the backend's pages cannot call it.

### Windows (#591, unsigned)

- CI job `desktop-windows` builds an NSIS installer (`Picsou_<version>_x64-setup.exe`) from the
  same jar and SPA, with the Windows Temurin JRE and zonky PostgreSQL
  (`fetch_runtime.py --target windows`), and uploads it as the `picsou-windows-installer`
  artifact. The job installs it silently, starts the installed app, waits for the readiness
  probe, checks that only loopback listens, kills the app hard and checks no process is left.
- Install per user (`%LOCALAPPDATA%\Picsou`, no admin prompt). Data in
  `%LOCALAPPDATA%\com.picsou.desktop` (local: a roaming profile never syncs the cluster). WebView2 ships with Windows 10 and 11.
- Unsigned: SmartScreen shows "Windows protected your PC". Click **More info**, then **Run
  anyway**. Signing and auto-update stay in #591.
- PostgreSQL runs under `pg_ctl` (Windows has no signals): `pg_ctl start -w` and
  `pg_ctl stop -m fast`. `pg_ctl` also drops admin rights, which PostgreSQL requires.
- A job object with "kill on close" holds Picsou and its children: a crash or a kill from the
  Task Manager takes Java and PostgreSQL with it.
- PostgreSQL carries the VC++ runtime DLLs copied from the JRE, and the shell links it
  statically: a fresh Windows without the VC++ redistributable runs it.
- The cluster uses the `C` locale and initdb's own `postgres` database: creating another one
  needs single-user mode, which PostgreSQL refuses to an elevated user, or client tools the
  bundle does not ship. Known gap: `lower()` and `ILIKE` fold ASCII only, so "É" and "é"
  differ on Windows; an ICU locale is the follow-up.
- Secrets are not 0600: NTFS has no POSIX modes. They sit in the user's own `%LOCALAPPDATA%`; the
  backend logs a warning when it cannot chmod.

### Bank sidecars (#589, browserless slice)

- `fetch_runtime.py` adds `runtime/python/` (python-build-standalone, the base image's minor,
  SHA-256 checked, Tk/IDLE/pip/headers trimmed: ~75 MB) and `runtime/sidecars/`: the shared
  libraries of `services/base` without test helpers, the pinned packages of
  `requirements.txt` in `site/`, and one folder per browserless sidecar holding exactly the
  files its Dockerfile `COPY`s. `runtime/sidecars.json` lists them. Wheels must match the
  bundled interpreter, so the runtime is built on its own OS.
- `sidecars.rs` opens one loopback port per listed sidecar and hands the backend
  `<SERVICE>_URL` plus `APP_SIDECAR_API_KEY` (generated once in `secrets/sidecar_api_key`).
  Nothing runs at idle: the first connection starts `python -m uvicorn main:app` on a private
  loopback port and the bytes are piped to it. After 15 min without an open connection it is
  stopped (longer than the sidecars' 10 min pending-login TTL, so an SMS step survives).
  Quitting stops every sidecar.
- Chromium sidecars (amundi, banque-populaire, bourse-direct, caisse-epargne, corum, fortuneo,
  lbp, tr) ship with Playwright but without a browser. Once the backend is ready, the shell runs
  `playwright install chromium-headless-shell` in the background into `<data>/browsers`
  (~150 MB download, ~280 MB unpacked, logged in `logs/browser-download.log`), so the first sync
  of a Chromium bank finds it ready; all of them share it. Later starts see it in place (a
  no-op of a few seconds). The bundled
  Playwright pins the browser build, so a new release downloads its own once. A sync that comes
  before the download ends: the sidecar answers `503` ("Chromium is downloading…",
  `Retry-After: 30`) without starting, so no bank login is started for a caller that gave up.
  A failed download (offline, proxy, antivirus) answers `503` with the reason and retries on the
  next call; `PLAYWRIGHT_DOWNLOAD_HOST` points it at a mirror.
- Linux: Chromium needs the usual desktop libraries (`libnss3`, `libnspr4`, `libatk1.0-0`,
  `libatk-bridge2.0-0`, `libcups2`, `libgbm1`, `libxkbcommon0`, `libasound2`, …; the list of
  `services/base/Dockerfile`). Windows needs nothing extra.
- Playwright's driver adds ~135 MB to the runtime.
- `<data>/sidecar-ports.json` maps each `<SERVICE>_URL` to its loopback port, for diagnostics.
- Camoufox sidecars (amex, bnp, revolut) are not shipped on desktop. `fetch_runtime.py` lists
  them in `runtime/sidecars-unavailable.json`; the shell passes `PICSOU_DESKTOP=true` and
  `PICSOU_DESKTOP_UNAVAILABLE` to the backend, which exposes them as `info.app.desktop` and
  `info.app.desktop-unavailable` on `/actuator/info`. The SPA greys those banks out in "Add
  account" with a "Not on desktop" badge, and their sync tab explains to use the Docker version.
  Docker sets neither variable, so nothing changes there.

### Key files

- `desktop/src-tauri/src/supervisor.rs` — processes, ports, secrets, first start
- `desktop/src-tauri/src/upgrade.rs` — version marker, backups, restore, Flyway failure reading
- `desktop/src-tauri/src/main.rs` — the window and the startup thread
- `desktop/src-tauri/src/sidecars.rs` — on-demand sidecars behind loopback ports
- `desktop/scripts/fetch_runtime.py` — builds `desktop/runtime` from the Docker release pins (`--target linux|windows`)
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
- Logs grow without rotation.
- Logout and shutdown send SIGTERM to the app, which skips `RunEvent::Exit`; the children then
  stop through `PR_SET_PDEATHSIG` (SIGINT for PostgreSQL, its fast shutdown).
- If the tray cannot be made (`libayatana-appindicator3` missing), Picsou runs without it:
  the window always opens and closing it quits.
- AppImage: Launch at login is greyed out and never defaulted, because the login item would
  point at the AppImage's temporary mount. Use the `.deb` for it.
- A startup error shows the window, even after a hidden start.
- The tray needs an AppIndicator host. GNOME shows none without the AppIndicator extension:
  the app still runs, and launching it again opens the window.

## Tests

- `cargo test` in `desktop/src-tauri` (CI job `desktop`): hidden start only with `--hidden`,
  launch at login defaulted once, backend environment (loopback, data
  paths), secret generated once and 0600, port reuse, quitting during startup.
- Manual run: `make desktop`, or `PICSOU_DESKTOP_RUNTIME=../runtime cargo run` from `desktop/src-tauri`.
