# ADR: One Chromium binary, one process per session, owned by browser-service

> Date: 2026-10-18
> Status: 🟡 Proposed (draft for 1.2.0, issue #235)

## Context

Issue #235 adds a desktop app that bundles every sidecar. Today each Playwright
sidecar image downloads its own Chromium. Measured on 1.1.0
(`services/scripts/shared_env_check.py`, python:3.12-slim-bookworm, x86_64):

- 6 sidecars install Chromium (amundi, bourse-direct, caisse-epargne, corum,
  fortuneo, tr): 6 × 894 MiB ≈ 5.4 GiB, against 894 MiB for one copy.
- 9 per-sidecar Python environments add up to 1,178 MiB. One environment built
  from `services/requirements-shared.txt` is 159 MiB.

The issue also asks that the desktop app stay light at idle, start sidecars and
Chromium on demand, and leave no orphan Chromium after a crash.

Amex and Revolut use Camoufox (a Firefox build) because Akamai blocks Chromium
there. They cannot share a Chromium and are out of scope.

## Decision

A new `browser-service` owns the only Chromium binary. For each login or sync,
a sidecar asks it for a session:

1. `POST /sessions` (sidecar key) launches a **fresh Chromium process** with a
   throwaway profile directory (mode 0700) and returns a single-use token.
2. The sidecar connects Playwright with `connect_over_cdp` to
   `WS /sessions/{id}/cdp`, sending the token and the sidecar key.
3. The service relays CDP frames to Chromium over `--remote-debugging-pipe`.
   Chromium never opens a TCP debugging port.
4. On WebSocket close, `DELETE`, TTL (300 s), unclaimed timeout (30 s), Chromium
   exit or service shutdown, the process group is killed and the profile wiped.

Sidecars call `picsou_browser.launch_chromium()`. Without `BROWSER_SERVICE_URL`
it launches Chromium locally, exactly as today, so the Docker install does not
change unless the `shared-browser` Compose profile is turned on.

corum-auth is the pilot. Other Chromium sidecars move in follow-up PRs.

## Alternatives considered

### A. One Chromium binary on disk, each sidecar launches its own process

- **Pros**: smallest change; same isolation as today.
- **Cons**: saves disk only, not RAM; every sidecar still needs Playwright's
  driver and the launch logic; the desktop app has to track N process trees.

### B. One long-lived Chromium, one browser context per sidecar

- **Pros**: lowest RAM and fastest start.
- **Cons**: any CDP client of a browser can drive every context in it.
  A compromised sidecar would read another bank's session. Restricting that
  means a CDP proxy that understands targets and sessions, which is large and
  fragile. Rejected.

### Reusing Electron's own Chromium

Rejected: it exposes a remote debugging endpoint on the app that holds the
Picsou session, and ties the Chromium version to Electron's instead of
Playwright's pin.

### C. One binary, one process per session, brokered by a service (chosen)

- **Pros**: same process isolation as today; one binary; nothing running at
  idle; a global cap (`BROWSER_MAX_SESSIONS`) bounds peak RAM; one place to kill
  everything on Quit.
- **Cons**: one more service; a relay in the CDP path; a sidecar's egress now
  leaves from browser-service (see trade-offs).

## Reasoning

C keeps the security property sidecars have today (no shared browser process
between connectors) and still gives the desktop app one binary and one
lifecycle owner. B's saving is RAM at peak, which the cap already bounds.

## Trade-offs accepted

- **Same Unix user for all sessions, and `--no-sandbox`.** Profiles are 0700
  but owned by the same user, so a CDP client could read another session's
  profile, and a renderer exploit would not be contained by Chromium's sandbox.
  The relay refuses the CDP routes that load or hand over a local file:
  `Page.navigate`, `Target.createTarget` and `Fetch.continueRequest` to a scheme
  other than http(s)/about; `DOM.setFileInputFiles`, `Page.handleFileChooser`,
  `Input.dispatchDragEvent` with files, `Network.loadNetworkResource`,
  `Target.sendMessageToTarget` (nested, unfiltered commands) and
  `Extensions.loadUnpacked`. Download-behaviour commands are rewritten to `deny`.
  This is a denylist; a per-session Unix user and the Chromium sandbox are the
  stronger fix, left for the desktop packaging work.
- **Egress moves.** Page traffic leaves from browser-service, which sits on its
  own networks (`browser-net`, internal, shared with corum-auth only, and
  `browser-egress`), never on `default` with the database. Network policy per
  sidecar (e.g. Fortuneo's dedicated network) no longer applies to the page
  traffic once that sidecar uses the service. Fortuneo stays on local launch until a per-session egress allowlist
  exists. Corum keeps its in-page CDP `Fetch` guard, which runs over the remote
  connection unchanged.
- **Full Chromium, not the headless shell.** The service launches
  `chromium.executable_path` with Playwright 1.52's own switches read from the
  installed driver, plus `--headless`. Playwright's `launch(headless=True)` uses
  the separate headless shell. The fingerprint Cloudflare sees may differ; a live
  CORUM login through the service is still to be done.
- **The corum image keeps its own Chromium** for the local fallback in this
  draft. Removing it is the step that realises the disk saving, once every
  install path uses the service.

## Consequences

- New `services/browser-service/` (image, CI job, GHCR publish entry) and
  `services/shared/picsou_browser/` client package.
- corum-auth builds from the `services/` context and installs
  `services/requirements-shared.txt`.
- `services/requirements-shared.txt` is a candidate single environment for the
  nine non-Camoufox sidecars: all 967 of their existing unit tests pass on it
  unchanged, including tr-auth on Playwright 1.52 instead of 1.44. Only corum
  uses it in this PR.
- The desktop app would start browser-service on demand as a child process,
  bound to `127.0.0.1` on a random port.
