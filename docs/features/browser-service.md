# Shared browser service

> Last updated: 2026-10-26
> Status: draft (1.2.0, issue #235), desktop app only. Pilot: corum-auth.

## What it does

`browser-service` launches one fresh Chromium process per session for the
sidecars that need a browser. Sidecars drive it with Playwright as if they had
launched it themselves. See the
[ADR](../decisions/2026-10-18-shared-browser-service.md) for why.

## Flow

1. Sidecar: `lease = await launch_chromium(pw, args=[...])`
   (`services/shared/picsou_browser`).
2. With `BROWSER_SERVICE_URL` unset: `pw.chromium.launch(...)`, as before.
3. With it set: `POST /sessions` → `{sessionId, wsPath, token, expiresAt}`, then
   `connect_over_cdp(ws_url, headers={X-Picsou-Browser-Token, X-Picsou-Sidecar-Key})`.
4. `await lease.close()` closes the browser and `DELETE`s the session. It is
   idempotent and never raises.

## API

| Call | Auth | Result |
|---|---|---|
| `GET /health` | none | `{status, sessions, max}` |
| `POST /sessions` `{"args": [...]}` | sidecar key | 201 session, 400 `UNSUPPORTED_ARG` / `INVALID_DATA`, 503 `BROWSER_CAPACITY`, 502 `BROWSER_LAUNCH_FAILED` |
| `WS /sessions/{id}/cdp` | sidecar key + single-use token | CDP relay; 1008 on any failed check |
| `DELETE /sessions/{id}` | sidecar key | 204, idempotent |

Allowed extra args: `--disable-blink-features=AutomationControlled` only.

## Isolation rules

- One Chromium process per session, own process group, `PR_SET_PDEATHSIG=SIGKILL`.
- `--remote-debugging-pipe`: no TCP debugging port.
- Fresh `--user-data-dir` (0700), deleted on close.
- Token single-use; one WebSocket per session.
- Chromium gets a minimal environment, never the sidecar key.
- The relay refuses, fail-closed: frames that are not one JSON object or carry a
  NUL; `Page.navigate`, `Target.createTarget`, `Fetch.continueRequest` to non-web
  schemes (`file:`, `chrome:`, `data:`, …); `DOM.setFileInputFiles`,
  `Page.handleFileChooser`, `Input.dispatchDragEvent` with files,
  `Network.loadNetworkResource`, `Target.sendMessageToTarget`,
  `Extensions.loadUnpacked`. Download-behaviour commands are rewritten to `deny`.
- Chromium stderr is discarded. A profile dir that cannot be removed is retried
  by the reaper until it is gone.
- Kill on WS close, `DELETE`, TTL, unclaimed timeout, Chromium exit, shutdown.
  The kill, wipe and registry removal run in their own task: cancelling the
  caller (the reaper at shutdown) does not stop them halfway, and shutdown waits
  for every cleanup in flight.
- Logs: session id prefix and lifecycle reason only.

## Configuration

| Env | Default | Meaning |
|---|---|---|
| `APP_SIDECAR_API_KEY` | required | shared sidecar key |
| `BROWSER_MAX_SESSIONS` | 4 | concurrent Chromium processes |
| `BROWSER_SESSION_TTL_SECONDS` | 300 | hard cap on a session |
| `BROWSER_CLAIM_TIMEOUT_SECONDS` | 30 | kill if no WebSocket connects |

Docker: not used. No Compose file declares the service and its image is not
published. Docker already shares Chromium through image layers and owns each
sidecar's processes, so a Compose install keeps one isolated container per bank.
To try it locally, run the image yourself on a network without the database and
set `BROWSER_SERVICE_URL` on corum-auth.

## Known limits

- Same Unix user for every session, Chromium runs with `--no-sandbox`; the CDP
  filter is a denylist (see ADR trade-offs).
- Page egress leaves from browser-service, not from the sidecar's network.
- Full Chromium binary instead of Playwright's headless shell. A live CORUM
  login passed through it on 2026-10-19; each other site must be checked.
- Camoufox sidecars (amex, revolut) are out of scope.

## Files

- `services/browser-service/main.py`, `Dockerfile`, `test_browser_service.py`
- `services/shared/picsou_browser/__init__.py`, `services/shared/test_picsou_browser.py`
- `services/corum-auth/main.py` (`_new_browser`, `_close_resources`), `test_browser_pilot.py`
- Environment: `services/base` (target `chromium`), shared with the Docker sidecars
