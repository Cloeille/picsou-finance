# Feature: Admin instance-status alert

> Last updated: 2026-10-31

## Context

An install can drift from what the release expects without anyone noticing until a sync
fails. The typical case came with the shared sidecar base images
([ADR 2026-10-21](../decisions/2026-10-21-shared-sidecar-base-image.md)): eleven sidecars are
built `FROM sidecar-python` or `sidecar-chromium`. When that base is missing (custom Compose
file without the two build-only services, Compose older than v2.33, base not pulled), the
sidecar build fails, Compose aborts, and the old sidecar keeps running or none runs at all.
Self-hosters also have no in-app signal that a newer release exists. An admin now gets a
pop-up for both.

## How it works

`GET /api/admin/instance-status` (ADMIN only, through the `/api/admin/**` matcher) returns:

```json
{
  "currentVersion": "1.1.0",
  "update": { "latestVersion": "1.2.0", "releaseUrl": "https://github.com/Cloeille/picsou-finance/releases/tag/1.2.0" },
  "issues": [
    { "code": "SIDECAR_UNREACHABLE", "sidecar": "corum-auth", "expectedBase": "sidecar-chromium" },
    { "code": "SIDECAR_OUTDATED", "sidecar": "bourso-auth", "expectedBase": "sidecar-python" }
  ]
}
```

**Sidecar check.** The registry covers all thirteen authentication sidecars, including
`cic-auth` (CIC online banking), separately from `cmes-auth` (employee savings).
Only sidecars the household uses are probed: one with at least one row in its session table,
including an expired session. Each in-use sidecar gets `GET /health` (2 s per probe, in
parallel, 3 s overall). CIC uses `app.cic-auth.url` (`CIC_AUTH_URL`) and expects `sidecar-python`.
The session counts run one after another in the background under the same 3 s deadline (one
DB connection at a time): a sidecar whose count is not known by the deadline reports nothing,
since it is unknown whether the household uses it.

- No answer, or a non-2xx answer: `SIDECAR_UNREACHABLE`.
- A 2xx answer whose `base` field is missing or not the expected base: `SIDECAR_OUTDATED`. The
  base images set `PICSOU_SIDECAR_BASE` (`services/base/Dockerfile`) and every base-built
  sidecar echoes it in `/health`. An image from before the base images answers
  `{"status":"ok"}` only, so it is caught.
- amex-auth and revolut-auth have their own image (Camoufox). They are checked for
  reachability only (`expectedBase: null`).

**Update check.** `ReleaseCheckService` reads `tag_name` and `html_url` from
`GET https://api.github.com/repos/Cloeille/picsou-finance/releases/latest` (5 s timeout) and
keeps the answer, success or failure, in memory for 6 hours. The fetch runs on its own
virtual thread and concurrent callers share it. It shares the endpoint's 3 s deadline with the
sidecar probes: on a cold cache the first response may carry `update: null` while the fetch
finishes and fills the cache for the next call. `html_url` must start with
`https://github.com/Cloeille/picsou-finance/releases/`, so the link the admin clicks can only
point at this project's releases. `update` is `null` when the
check is off, failed, finds nothing newer, or when `APP_VERSION` is not plain `X.Y.Z`
(`dev`, `nightly-…`, branch builds). Versions compare numerically (`1.0.10` > `1.0.9`).

**Pop-up.** `InstanceStatusDialog` (mounted in `AppLayout`) opens for an admin, outside demo
mode, when there is an issue or an update. It waits for the one-time sidebar-style prompt and
for the telemetry consent dialog, so two modals never stack. It waits only while that dialog
is on screen (`useTelemetryConsentPrompt`): closing it unanswered leaves consent `UNSET` but
lets the alert through for the rest of the page load. It lists each issue with the fix
(`docker compose pull` then `docker compose up -d`, or `docker compose up -d --build` with
Compose v2.33+) and the update with a link to the release notes. Closing it stores a
fingerprint (sorted `code:sidecar` plus the latest version) in the persisted app store; it
shows again only when that fingerprint changes. A clean status clears the stored fingerprint,
so a problem that is fixed and later comes back alerts again. The request opts out of the
global error redirect: a 5xx on this optional check never sends the app to `/error/500`.

### Key files

- `backend/src/main/java/com/picsou/service/InstanceStatusService.java` — sidecar registry,
  probes, classification
- `backend/src/main/java/com/picsou/service/ReleaseCheckService.java` — GitHub release check
  and cache
- `backend/src/main/java/com/picsou/controller/AdminController.java` — `GET /instance-status`
- `services/base/Dockerfile` — `PICSOU_SIDECAR_BASE`; each base-built sidecar's `main.py`
  echoes it in `/health`
- `frontend/src/components/shared/InstanceStatusDialog.tsx`, `instance-status.ts`
  (fingerprint)
- `frontend/src/features/admin/{api,hooks}.ts`, `frontend/src/stores/app-store.ts`

### Configuration

| Variable | Default | Effect |
|----------|---------|--------|
| `APP_UPDATE_CHECK_ENABLED` | `true` | `false` makes no outbound call at all |

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Probe only sidecars with a session row | An install that never uses a bank may run without its sidecar; warning it forever is noise. | Probe all thirteen (false alarms on lean installs). |
| Base reported by `/health` from an image `ENV` | The only signal that tells "new image" from "old container still running", without Docker socket access. | Reading the Docker API from the app (needs the socket, a privilege escalation). |
| Backend calls GitHub, not the browser | The CSP keeps `connect-src 'self'`, and one cached call serves every admin. | Browser `fetch` to api.github.com (CSP change, one call per page load). |
| Failure cached for 6 h too | An offline install would otherwise call GitHub on every admin page load. | Cache successes only. |
| Dismiss by fingerprint, client-side | No migration; a new problem or a new release still surfaces. | Server-side "seen" flag (a table or setting for a UI preference). |

## Gotchas / Pitfalls

- A sidecar that has never been used (no session row) is never probed, even if it is down.
  The setup wizard's BoursoBank health check covers first-time setup.
- `nightly-…` and branch builds never get an update notice: their `APP_VERSION` is not semver.
- The sidecar tests that assert `/health == {"status":"ok"}` remove `PICSOU_SIDECAR_BASE`
  for the request, since the rebuilt test images now carry it.
- Adding a base-built sidecar: add it to the registry in `InstanceStatusService` with its
  base and session repository, and echo `PICSOU_SIDECAR_BASE` in its `/health`.

## Tests

- `InstanceStatusServiceTest` — all thirteen registry targets, including CIC's base, URL and
  session-count supplier; classification against stub HTTP servers, not-in-use sidecars never probed;
  session counts run one at a time; a slow count stays within the deadline and neither reports
  its own sidecar nor marks an already counted one unreachable
- `ReleaseCheckServiceTest` — semver ordering, disabled/non-semver make no call, 6 h cache,
  GitHub errors
- `AdminControllerTest` — endpoint returns the service result
- `InstanceStatusDialog.test.tsx` — admin/demo/stacking gates, fingerprint dismiss
- `TelemetryConsentInstanceStatus.test.tsx` — both dialogs mounted together: X, Escape, Yes
  and No each let the alert through, and closing never records consent
- Each base-built sidecar's `test_sidecar_auth.py`, including CIC — `/health` echoes the base
  when set and remains accessible without a key
