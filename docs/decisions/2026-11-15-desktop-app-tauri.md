# ADR: A Tauri desktop app built from the Docker release

> Date: 2026-11-15
> Status: 🟡 Proposed
> Issue: #235 (sub-issues #584 to #591)

## Context

Picsou installs only through Docker Compose (or the Helm chart). Issue #235 asks for a second path
for people who never open a terminal: a download-and-double-click app on macOS, Windows and
Linux that starts at login and keeps running in the background, so the daily sync and snapshots
happen without the window being opened.

The issue sets two constraints that drive every choice below:

- **Same release train.** One version, one tag, the same backend, frontend and sidecars.
- **Light when idle.** The app runs all day, so its idle cost is paid all day.

And one constraint of our own: the desktop app must not be a second product to maintain. Every
change made for Docker (a new sidecar, a pin bump, a migration) must reach the desktop build with
no desktop-specific edit.

Measured on `release/1.3.0`, the parts that make this possible already exist:

- The app image is one non-root Java process; Spring serves the SPA
  ([one non-root process](./2026-11-03-one-non-root-process.md),
  [Spring serves the SPA](./2026-11-03-spring-serves-the-spa.md)). No nginx, no supervisord.
- `SecretBootstrap` generates the secrets into `PICSOU_SECRETS_DIR`.
- The 17 sidecars build on three shared bases with one pin set
  ([shared base images](./2026-10-21-shared-sidecar-base-image.md)): 6 browserless (`python`),
  8 Chromium, 3 Camoufox (amex, bnp, revolut).
- `.github/scripts/sidecars.py` already derives the CI and publishing matrices from the sidecar
  Dockerfiles.

## Decision

1. **Native processes, no VM.** The app runs the backend jar on a bundled JRE 21, PostgreSQL
   binaries of the Compose major, and the sidecars on one Python runtime of the base image's
   minor, all bound to `127.0.0.1`.
2. **Tauri is the shell.** The window shows the local Spring URL, so the frontend is not forked.
   The Rust side is limited to process supervision, the tray menu and launch at login.
3. **The desktop build reads its pins from the Docker files.** `sidecars.py --desktop` prints the
   Java major (`backend/pom.xml`, checked against the app image's JRE), the PostgreSQL major (Compose `db` image), the Python minor (base Dockerfile), the Playwright
   version (`requirements-chromium.txt`) and the sidecar list with a `desktop` flag. The desktop
   build will call it at build time (#587 wires it in); there is no copied list to keep in sync.
   A pin that moves to a shape the script does not recognise makes the script exit non-zero, and
   `DesktopManifestTest` fails in CI, instead of a stale version shipping.
4. **v1 connectors: the `python` and `chromium` sidecars.** Camoufox sidecars are left out
   (`desktop: false`): AMEX needs a headful browser, which Docker hides behind Xvfb and a desktop
   would show as a visible window. A new sidecar on one of the first two bases joins the desktop
   app with no change.
5. **No browser-service.** One shared `PLAYWRIGHT_BROWSERS_PATH` already gives one Chromium on
   disk. The `browser-service` of #242 (merged only on `1.1.0-leg`) is not needed.
6. **Secrets stay files.** 0600 files in the OS app-data folder, through the existing
   `PICSOU_SECRETS_DIR`. The OS keychain can come later without changing the backend.

The other open questions of #235 are tracked in their own sub-issues: missed-schedule catch-up in
the backend (#585), container-free sidecars (#586), backup before update (#588), on-demand
sidecars and Chromium download (#589), the Enable Banking callback (#590), signing and updates
(#591).

## Alternatives considered

### Electron

- **Pros**: TypeScript only, one bundled Chromium so one rendering engine to test, mature tray,
  autostart, signing and update tooling (electron-builder).
- **Cons**: weight. Every install ships its own Chromium and Node: roughly 80 to 150 MB of
  installer and 100 to 200 MB of RAM for the shell alone, before Picsou starts. For an app that
  runs all day in the background, that is the cost that matters most. The usual argument for it
  here, reusing its Chromium for the Playwright sidecars, does not hold: Playwright drives the
  build it pins, and Camoufox is Firefox.

### Tauri (chosen)

- **Pros**: uses the OS webview (WebView2, WKWebView, WebKitGTK), so the shell weighs a few MB on
  disk and tens of MB of RAM. Tray, autostart, deep links and updater are official plugins.
- **Cons**: a small Rust codebase (the maintainers had already discussed adding Rust), and three
  webviews to test instead of one. WebKitGTK on Linux is the main rendering risk.

The weights above are public orders of magnitude for empty shells, not measured on Picsou. The
first shell pull request (#587) measures installer size and idle RAM on the real app and records
them here.

### Embedded containers (Podman or Colima inside the app)

- **Pros**: runs the exact Compose stack, so nothing can drift.
- **Cons**: contradicts "no Docker" in spirit, needs WSL2 and admin rights on Windows, and a Linux
  VM idles around 2 GB of RAM. Fails "light when idle".

### A Java shell (jpackage and an AWT tray icon)

- **Pros**: no new language, the JVM is bundled anyway.
- **Cons**: the AWT tray relies on the legacy system tray, which current GNOME does not show
  without an extension, and launch at login, auto-update and signing would all
  be hand-written.

## Reasoning

The shell is the only part that is new code. Everything it runs is already built for Docker. So
the deciding criteria are its idle weight, because it is paid all day, and how little it has to
know about Picsou, because every piece of Picsou knowledge in the shell is a place where Docker
and desktop can drift. Tauri wins on weight; reading the pins from the Docker files keeps the
shell's Picsou knowledge to the list of bases it supports.

## Trade-offs accepted

- Rust joins Java, TypeScript and Python in the repository.
- The frontend must render in WebKitGTK and WKWebView, not only in Chromium-based browsers.
- AMEX, BNP Paribas and Revolut are not available in the desktop app v1.
- Sidecar code must run on macOS and Windows, which CI does not test today (#586).

## Consequences

- `sidecars.py --desktop` and its tests (`DesktopManifestTest` in
  `.github/tests/test_sidecar_workflows.py`) are the contract between the Docker release and the
  desktop build. A new runtime pin the desktop needs is added there, read from its Docker source.
- The Docker install, its images and publishing are unchanged.
