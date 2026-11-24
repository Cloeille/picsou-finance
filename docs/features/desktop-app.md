# Feature: Desktop app

> Last updated: 2026-11-24

## Opening the unsigned desktop app (macOS Gatekeeper & Windows SmartScreen)

Picsou is a free, open-source, self-hosted project and does not yet pay for an Apple Developer ID or a Windows EV code-signing certificate. Because of this, macOS and Windows will show a standard "unidentified developer" warning the **first time** you open a release downloaded from GitHub. This is expected for unsigned open-source apps — you only have to approve Picsou once.

### macOS (Apple Silicon)

1. Open the downloaded `.dmg` and drag **Picsou.app** into your **Applications** folder.
2. Double-click **Picsou** once — macOS Gatekeeper will block it with an "unidentified developer" message. Click **Done** (or **OK**).
3. Open **System Settings → Privacy & Security**, scroll down to the **Security** section where Picsou is listed, and click **Open Anyway** (*Ouvrir quand même*), then confirm with your Mac password or Touch ID.
   - *On macOS 14 Sonoma*: you can also right-click **Picsou.app** in Applications, choose **Open** (*Ouvrir*), and click **Open** again in the dialog.
4. **Terminal fallback** (if macOS says the app is "damaged" or does not display the *Open Anyway* button): open Terminal and clear the download quarantine attribute once:
   ```bash
   xattr -cr /Applications/Picsou.app
   ```
   Then launch **Picsou** normally from Applications.

> [!NOTE]
> Future in-app updates are verified against Picsou's cryptographic updater key and installed directly by the app, so Gatekeeper will not prompt you again after the initial install.

### Windows (64-bit)

1. Double-click the downloaded installer (`Picsou_<version>_x64-setup.exe`).
2. When Microsoft Defender SmartScreen displays **"Windows protected your PC"** (*Windows a protégé votre ordinateur*), click **More info** (*Informations complémentaires*).
3. Click **Run anyway** (*Exécuter quand même*).
4. Picsou installs into your user folder (`%LOCALAPPDATA%\Picsou`) without asking for administrator rights.

### Linux

- **AppImage**: make the file executable (`chmod +x Picsou_*.AppImage`) and run it.
- **Debian / Ubuntu (`.deb`)**: install with `sudo apt install ./Picsou_*.deb`.

## Context

Issue #235: Picsou for people who never open a terminal. The Tauri desktop shell embeds the Rust
backend (`backend-rs`) in-process on an embedded SQLite database (`picsou.db`), shows the app in a
native window, and keeps running in the system tray. Architectural choices are recorded in
[Desktop app (Tauri)](../decisions/2026-11-15-desktop-app-tauri.md),
[Rewrite the backend in Rust](../decisions/2026-11-21-rust-backend.md), and
[Dual-Engine Database Architecture](../decisions/2026-11-22-sqlite-dual-engine.md). There is no JRE,
no external database process, and no bundled PostgreSQL server.

## How it works

1. One instance only: a second launch brings the running window forward
   (`tauri-plugin-single-instance`). The window opens at once on `desktop/ui/index.html`, a
   calm surface in DESIGN.md's canvas colours (light or dark, as the system), painted before the
   page so it never flashes black, except when the login item started the app with `--hidden`.
   A thin progress line appears only if the start takes longer than 0.8 s.
2. A thread runs `supervisor::start`:
   - creates the app-data folder (`~/.local/share/com.picsou.desktop` on Linux,
     `~/Library/Application Support/com.picsou.desktop` on macOS,
     `%LOCALAPPDATA%\com.picsou.desktop` on Windows) and `secrets/`, both `0700` on POSIX;
   - opens the embedded SQLite database at `<data>/picsou.db` (`DATABASE_URL=sqlite://...`),
     configured with `0600` file permissions on POSIX, `PRAGMA journal_mode = WAL`,
     `PRAGMA foreign_keys = ON`, `PRAGMA busy_timeout = 5000`, and exact `rust_decimal::Decimal`
     UDFs over canonical `TEXT` columns (zero binary floats);
   - checks whether an older embedded-PostgreSQL folder (`postgres/`) exists alongside a fresh
     SQLite install: `postgres/` is preserved untouched and the backend flags
     `legacyPostgresDetected` on `/api/setup/status`, so the welcome screen says the earlier
     data was kept and that a new database starts. The app does not convert it. Only a build
     from source ever stored data there: no desktop release shipped with PostgreSQL. To carry
     that data over, start the old cluster with PostgreSQL 16 and run `picsou export-sqlite`
     from the server binary against it;
   - binds `127.0.0.1` on the port of the last start when still free (`desktop-port`, so the
     window keeps its origin and local storage), builds the backend's `Config` from the process
     environment plus plain-HTTP cookies, `APP_DESKTOP_MODE=true`, `PICSOU_SECRETS_DIR`,
     `APP_VERSION` (the shell's), and startup sync off (#585 catches up instead), and runs
     `picsou::start` (open SQLite, run `db::sqlite::migrate`, seed reference data) on a tokio
     runtime of its own;
   - once `start` returns, the backend is ready: no polling. The window navigates to the local
     backend and the thread serves (`picsou::run`) until Quit.
   - The shell and the backend log to `logs/backend.log` (rolling, 10 MB, seven kept), with
     "window shown", "Started Picsou", and "the app page loaded" timings.
3. The tray menu has Open Picsou, Launch at login and Quit Picsou. Its labels, the loading page
   and the startup failure messages follow the system language (English, French, Spanish,
   German; `i18n.rs` and `ui/index.html`), English otherwise. Closing the window hides
   it; only Quit stops the processes. Launch at login (`tauri-plugin-autostart`, an XDG
   autostart entry on Linux) is off until the user ticks it in the tray menu: Picsou adds
   nothing to the system's login items on its own. The check mark is read back from the OS entry.
4. Quitting asks the backend to finish requests in flight, flush SQLite WAL state and close its
   database handles (up to 20 s), and shuts down the WebView bridge and any active on-demand
   sidecars. On Linux, child sidecars also die with the app if it crashes or is killed
   (`PR_SET_PDEATHSIG`).
5. macOS: a click on the Dock icon while the window is closed (`RunEvent::Reopen`) shows it again.

### Updates and failed migrations (#588)

- `app-version` in the data folder names the last version that reached the dashboard. It moves
  only once the backend started and its SQLite migrations applied.
- A start with a newer version copies `picsou.db` (plus `picsou.db-wal` and `picsou.db-shm` if
  present) and `secrets/` to `backups/<seconds>-<from>-to-<to>/` before the backend opens SQLite,
  so the copy is consistent. Every copied file is `0600` (dirs `0700`) on POSIX and flushed to
  disk. The backup is staged as `*.partial` and renamed when complete. A retried update keeps the
  copy of its first try, and the last two copies are kept.
- An older version on newer data does not open SQLite: the window names the version to install
  and the data is untouched. The SQLite migrator's own refusal (an applied migration version this
  build does not ship) produces the same message.
- A migration failure after an update shows its reason (read from the backend's typed
  `SqliteMigrationError` / `MigrationError`, no log parsing) and a "Restore my data from before
  the update" button. Restoring stops the backend, moves the failed `picsou.db*` files and
  `secrets/` aside (`*.failed-<seconds>-<n>`, never deleted: remove them by hand once the restore
  works) and puts the backup in place. The copy is staged as `*.restoring` first, so a failed
  restore changes nothing. Data without an `app-version` marker counts as an update from
  `unknown`. The user then reinstalls the previous version.
- The button calls the `restore_backup` command. IPC is open only to the bundled `ui/` page
  (`capabilities/error-page.json` lists no remote URL), so backend pages cannot call it.

### Windows (#591, unsigned)

- CI job `desktop-windows` builds an NSIS installer (`Picsou_<version>_x64-setup.exe`) from the
  shell (which links the Rust backend and embedded SQLite) and the SPA, with bundled CPython for
  on-demand Chromium sidecars (`fetch_runtime.py --target windows`), and uploads it as the
  `picsou-windows-installer` artifact. The job installs it silently, starts the installed app,
  waits for readiness, checks that only loopback listens, kills the app hard, and checks no child
  process is left.
- Install per user (`%LOCALAPPDATA%\Picsou`, no admin prompt). Data in
  `%LOCALAPPDATA%\com.picsou.desktop` (local: a roaming profile never syncs the SQLite database).
  WebView2 ships with Windows 10 and 11.
- Unsigned: SmartScreen shows "Windows protected your PC". Click **More info**, then **Run
  anyway**.
- A job object with "kill on close" holds Picsou and any spawned sidecar children: a crash or a
  kill from Task Manager terminates the sidecars with it.
- The shell links the VC++ runtime statically: a fresh Windows machine without the VC++
  redistributable runs it.
- Secrets and `picsou.db` are not `0600`: NTFS has no POSIX modes. They live in the user's own
  `%LOCALAPPDATA%`; the backend logs a warning when it cannot chmod.

### Bank connectors & sidecars (#589)

- **Six pure-HTTP connectors run natively in-process in Rust** (`bourso-auth`, `cic-auth`,
  `cmes-auth`, `degiro-auth`, `natixis-auth`, `sofidy-auth`): `supervisor.rs` enables
  `APP_NATIVE_SIDECARS=true` (and explicit `<BANK>_AUTH_URL` overrides still take precedence), so
  Desktop runs them with zero Python processes and `fetch_runtime.py` does not bundle them or
  `euro_information`.
- **Three browser-protected connectors run through native OS WebViews** (`webview_bank/` for
  Revolut, BNP Paribas / Hello bank!, and American Express): the shell opens a loopback HTTP
  bridge on an ephemeral `127.0.0.1` port authenticated by `X-Picsou-Sidecar-Key` (constant-time
  comparison) and launches an interactive Tauri WebView window constrained by per-bank
  `on_navigation` host allowlists. No Camoufox or Firefox bundle is needed on Desktop. A completed
  BNP or Amex login hands its `session_state` once, to the first `GET /webview/status/{process_id}`
  that reads it; an unclaimed one is dropped after `SNAPSHOT_CACHE_TTL`. Forgetting a bank while
  one of its login windows is open closes that window (`POST /webview/cancel/{process_id}` on the
  bridge), including when the disconnect lands while the window is still opening; a status poll
  that arrives after the disconnect answers `AUTH_ATTEMPT_EXPIRED` and starts no sync. A WebView
  `processId` is refused by `POST /api/bnp/auth/complete` and stays usable by its own window.
- **Remaining Chromium sidecars** (amundi, banque-populaire, bourse-direct, caisse-epargne, corum,
  fortuneo, lbp, tr): `fetch_runtime.py` bundles `runtime/python/` (python-build-standalone,
  SHA-256 checked, Tk/IDLE/pip/headers trimmed, `bin/python3` flattened) and `runtime/sidecars/`
  (`picsou_sidecar`, `bpce`, pinned packages in `site/`, and each sidecar's files listed in
  `runtime/sidecars.json`). Wheels match the bundled interpreter, so the runtime is built on its
  own OS.
- `sidecars.rs` opens one loopback port per listed Chromium sidecar and hands the backend
  `<SERVICE>_URL` plus `APP_SIDECAR_API_KEY` (generated once in `secrets/sidecar_api_key`).
  Nothing runs at idle: the first connection starts the sidecar's `main:app` under uvicorn and
  pipes bytes to it. The Python child binds `127.0.0.1:0` itself (`SO_EXCLUSIVEADDRUSE` on
  Windows), hands that bound socket to uvicorn and announces the port through a temporary file in
  `<data>/run/` (`0700` on Unix), so no other local process can take the port between its choice
  and its use. After 15 min without an open connection it is stopped
  (longer than the sidecars' 10 min pending-login TTL, so an SMS step survives). Quitting stops
  every sidecar.
- Chromium sidecars ship with Playwright but without a browser. Once the backend is ready, the
  shell runs `playwright install chromium-headless-shell` in the background into `<data>/browsers`
  (~150 MB download, ~280 MB unpacked, logged in `logs/browser-download.log`), so the first sync
  of a Chromium bank finds it ready; all of them share it. Later starts see it in place (a no-op
  of a few seconds). A sync before the download finishes answers `503` ("Chromium is
  downloading…", `Retry-After: 30`) without starting the sidecar. A failed download (offline,
  proxy, antivirus) answers `503` with the reason and retries on the next call;
  `PLAYWRIGHT_DOWNLOAD_HOST` points it at a mirror.
- Linux: Chromium needs the usual desktop libraries (`libnss3`, `libnspr4`, `libatk1.0-0`,
  `libatk-bridge2.0-0`, `libcups2`, `libgbm1`, `libxkbcommon0`, `libasound2`, …; the list of
  `services/base/Dockerfile`). Windows needs nothing extra.
- `<data>/sidecar-ports.json` maps each `<SERVICE>_URL` to its loopback port, for diagnostics.
- Any connector listed in `runtime/sidecars-unavailable.json` is passed via
  `PICSOU_DESKTOP_UNAVAILABLE` and exposed on `/actuator/info` as `info.app.desktop-unavailable`.
  The SPA disables those banks in "Add account" and shows a calm Desktop notice on their sync tab.

### macOS (#591, Apple Silicon, ad-hoc signed)

- CI job `desktop-macos` builds `Picsou.app` and a `.dmg` on `macos-latest` (Apple Silicon) with
  embedded SQLite and python-build-standalone (`fetch_runtime.py --target macos`), and uploads the
  `.dmg` as the `picsou-macos-dmg` artifact. The job starts the app with `open`, then runs the
  smoke checks: ready, a sidecar on demand, Chromium prefetched, loopback only, nothing left
  after `kill -9`, and a backup on a simulated update.
- Intel Macs are not built: Apple stopped selling them, and a second build doubles the release.
- Data in `~/Library/Application Support/com.picsou.desktop`.
- **No Apple Developer account.** The app is ad-hoc signed (`signingIdentity: "-"`), free and
  done by the build: Apple Silicon refuses to run code with no signature at all. It is not
  notarized, so Gatekeeper blocks the first open of the downloaded app:
  - macOS 15 and later: open it once, then System Settings, Privacy & Security, **Open Anyway**.
  - macOS 14: right-click the app, **Open**, then **Open** again.
  - Or once in Terminal: `xattr -dr com.apple.quarantine /Applications/Picsou.app`.
- Updates (#591) replace the `.app` from `Picsou.app.tar.gz`. The updater downloads it itself, so
  no quarantine flag is set and Gatekeeper does not ask again; the download is checked against
  the updater key like on the other systems.
- macOS has no parent-death signal. Each spawned sidecar child starts through `/bin/sh`, which
  forks a watcher and `exec`s the program in its own place: the watcher polls the app every 2 s
  and terminates the sidecar once the app is gone, then exits.
- With an Apple Developer account later: set `APPLE_CERTIFICATE`, `APPLE_SIGNING_IDENTITY`,
  `APPLE_ID`, `APPLE_PASSWORD` and `APPLE_TEAM_ID` as secrets and pass them to the build step;
  Tauri then signs with the Developer ID and notarizes, and the Gatekeeper step goes away.

### Releases and updates (#591)

Publishing a GitHub Release runs `.github/workflows/desktop-release.yml`. It builds the Windows
installer, the Linux AppImage and the macOS app (`.app.tar.gz` for updates, `.dmg` for a first
install) at the release tag, each on its own OS, signs them with the updater key, and attaches
them, their `.sig` and one `latest.json` listing all three to the release. `latest.json` is
written only once all are built, so no platform is offered a half release. An installed app reads
`https://github.com/<repo>/releases/latest/download/latest.json` once the backend is ready, then
every 6 h, and offers "Install Picsou x.y.z" in the tray. The entry is disabled while the install
runs, so a second click (or `--install-update`) is ignored; a failed install shows its reason in a
banner of the main window and puts the entry back for a retry.

- The version comes from the tag (`1.3.0` or `v1.3.0`): `desktop_release.py stamp` writes it to
  `Cargo.toml`, which the bundle, the updater and the backups (#588) all read.
  `tauri.conf.json` carries no version on purpose.
- Before uploading, the `publish` job copies each installer to a version-less name
  (`Picsou-macOS-arm64.dmg`, `Picsou-Windows-x64.exe`, `Picsou-Linux-x64.AppImage`, with no `.sig`
  file) while leaving the versioned files and `latest.json` untouched. The direct download links in
  `README.md` point to `/releases/latest/download/<name>` for these three files and are verified
  against the workflow by `.github/tests/test_desktop_release.py`.
- Only the release build gets the updater config (`desktop_release.py config`, passed to
  `tauri build --config`). PR and dev builds have no public key, so they never check.
- The plugin checks the download against the public key before anything stops.
- Windows: Picsou stops its stack, writes the verified installer to `%TEMP%`, starts it detached
  with `/P /UPDATE /R` (progress bar, no question, restarts Picsou) and exits. The plugin's own
  Windows install hung on CI between its exit hook and the installer start, so it is not used.
- Linux: only the AppImage updates itself (the plugin rewrites the file, then Picsou restarts).
  A `.deb` install never offers an update: the package manager owns it.
- Each step goes to `logs/update.log` in the data folder; a release build has no console.
- The new version's first start copies the data before migrating it (#588), as for any update.
- A prerelease is attached like any release, but `releases/latest` skips prereleases, so
  installed apps do not get it.
- To attach to a release published before the key was set, run the workflow by hand
  (Actions, "Desktop release", "Run workflow") with its tag. Only a tag that contains this
  workflow works: an older one has neither the release scripts nor the updater.

One-time setup by a maintainer. The job is skipped until the variable exists.

1. Generate the key pair on a trusted machine:
   `npx @tauri-apps/cli@2.12.4 signer generate -w picsou-updater.key`. It asks for a password and
   writes `picsou-updater.key` (private) and `picsou-updater.key.pub` (public). Keep both
   outside the repository, and back the private key up: losing it means installed apps can no
   longer be updated, and users must reinstall by hand.
2. In the repository, Settings, Secrets and variables, Actions:
   - **Secrets** tab: `TAURI_SIGNING_PRIVATE_KEY` = the content of `picsou-updater.key`,
     `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` = its password.
   - **Variables** tab: `TAURI_UPDATER_PUBKEY` = the content of `picsou-updater.key.pub`.
   With the CLI: `gh secret set TAURI_SIGNING_PRIVATE_KEY < picsou-updater.key`,
   `gh secret set TAURI_SIGNING_PRIVATE_KEY_PASSWORD`, and
   `gh variable set TAURI_UPDATER_PUBKEY < picsou-updater.key.pub`.
3. Publish the next release as usual. The installer appears on the release page about 30 min
   later.

This key only proves an update comes from the project. It is not a code-signing certificate:
SmartScreen still warns on the first install (see Windows above).

### Key files

- `desktop/src-tauri/src/supervisor.rs` — embedded SQLite setup, in-process backend, ports, secrets, legacy `postgres/` detection, first start
- `desktop/src-tauri/src/upgrade.rs` — version marker, pre-upgrade `picsou.db` + `secrets/` backups (`0600`/`0700`), downgrade guard, restore, migration failure reading
- `desktop/src-tauri/src/webview_bank/` — loopback HTTP bridge and native OS WebView bank connectors (Revolut, BNP Paribas / Hello bank!, Amex) with an `on_navigation` scheme filter and `on_page_load` main-frame host allowlists
- `desktop/src-tauri/src/main.rs` — the window and the startup thread
- `desktop/src-tauri/src/external.rs` — https links the page opens in a new window, handed to the system browser
- `desktop/src-tauri/src/i18n.rs` — tray, restore and startup failure texts in the four languages
- `desktop/src-tauri/src/sidecars.rs` — on-demand Chromium sidecars behind loopback ports
- `desktop/scripts/fetch_runtime.py` — builds `desktop/runtime` from the Docker release pins (`--target linux|windows|macos`)
- `desktop/ui/index.html` — the waiting and error page

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Window loads the local backend URL | The SPA is not forked: same build as Docker | Bundling `dist` in Tauri, which would need a second API origin |
| Embedded SQLite (`rusqlite`, WAL mode, `Decimal` UDFs) | Zero external DB process, ~50 MB smaller bundle, instant startup, `0600` local file permissions | Bundled PostgreSQL (`initdb`/`pg_ctl`/`pg_dump`), which added ~64–293 MB and 14 background processes |
| Backend linked as a library, on its own tokio runtime | Readiness known in-process; no JRE (150 MB) or jar (132 MB); the window shows the app immediately after launch | A backend child process polled over HTTP |
| Native Rust connectors + OS WebView bridge | Zero Python processes for 6 pure-HTTP banks, and native WebViews for Revolut/BNP/Amex without bundling Camoufox/Firefox | Shipping Camoufox (~300 MB) and Python sidecars for every bank |
| Versions from `sidecars.py --desktop` | The desktop follows the Docker release with no copy | Pins in the desktop build |

## Gotchas / Pitfalls

- **`PR_SET_PDEATHSIG` is per thread.** It fires when the thread that spawned a child ends.
  The startup thread serves the backend, then parks forever; a thread that returns kills any
  spawned sidecar child half a second later.
- Windows has no parent-death signal: the job object ends the children. macOS uses a watcher
  per child (see macOS above).
- A startup error can come before `ui/index.html` finished loading: it is kept and shown again
  from `on_page_load`.
- **The main window is built in code** (`"create": false` in `tauri.conf.json`, `main_window`
  in `main.rs`). A window Tauri creates from the config has no new-window handler, and
  `target="_blank"` links and `window.open` then do nothing on any system. The handler hands
  https links to the system browser (`external.rs`) and never opens a second window.
- `backend.log` rotates; sidecar logs grow without rotation.
- Logout and shutdown send SIGTERM to the app, which skips `RunEvent::Exit`: the backend ends
  with the process, and any spawned sidecars stop through `PR_SET_PDEATHSIG` (Linux), the job
  object (Windows), or the watcher (macOS).
- If the tray cannot be made (`libayatana-appindicator3` missing), Picsou runs without it:
  the window always opens and closing it quits.
- AppImage: Launch at login is greyed out, because the login item would point at the
  AppImage's temporary mount. Use the `.deb` for it.
- A startup error shows the window, even after a hidden start.
- The tray needs an AppIndicator host. GNOME shows none without the AppIndicator extension:
  the app still runs, and launching it again opens the window.

## Tests

- `cargo test` in `desktop/src-tauri` (CI job `desktop`): hidden start only with `--hidden`,
  backend environment (loopback, `DATABASE_URL=sqlite://...`,
  data paths, legacy `postgres/` detection), secret generated once and `0600`, port reuse,
  quitting during startup, pre-upgrade SQLite + `secrets/` backups (`0600`/`0700`, two kept),
  downgrade guard, migration failure classification, the WebView navigation scheme filter and
  main-frame host allowlists, the external link filter, and the shell texts in four languages.
- `backend-rs`: `sqlite_foundation` and `desktop_mode` integration tests cover SQLite schema
  parity, exact `Decimal` precision, V1->V2 migrations, and Desktop auth/setup flows.
- Manual run: `make desktop`, or `PICSOU_DESKTOP_RUNTIME=../runtime cargo run` from `desktop/src-tauri`.
