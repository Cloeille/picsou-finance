# Feature: Opt-in anonymous telemetry & privacy

> Last updated: 2026-10-07
> Status: ✅ Implemented (issue #200)

## Context

Picsou is self-hosted and handles household finances. Error reports and coarse usage counts help
the maintainers find crashes and see which screens matter, but only if they carry **no financial or
personal data** and only if the instance explicitly opted in. The collector is a self-hosted
[GlitchTip](https://glitchtip.com/) speaking the Sentry protocol.

## Summary

- **Off by default, everywhere.** Nothing is sent and no SDK is initialised until the instance admin
  says yes. "No answer yet" counts as no.
- **No DSN, no telemetry.** If `APP_TELEMETRY_DSN` is empty (the default) the feature is fully
  inert: no SDK in the browser or the JVM, no consent prompt, and the tunnel endpoint drops
  everything. Self-hosters who never set it are not affected in any way.
- **Allowlist, not denylist.** Only the fields listed below leave the instance; every kept string is
  scrubbed again. Scrubbing runs twice: in the browser (`beforeSend`) and again on the Picsou server
  before forwarding.
- **No identifier at all.** No user, no device id, no session id, no anonymous id.
- **The browser never talks to GlitchTip.** Browser events go through the Picsou backend
  (`POST /api/telemetry/tunnel`), which re-scrubs and forwards them. The user's IP address therefore
  never reaches the collector, and the CSP keeps `connect-src 'self'`.

## Consent

Consent is an **instance** setting (one household, one choice), stored in `app_setting` under
`telemetry.consent` with values `ENABLED` / `DISABLED`. A missing row is `UNSET` and behaves as
disabled. No Flyway migration is involved.

- **Prompt.** When a DSN is configured and consent is `UNSET`, an admin sees a one-time dialog on
  their next visit (first launch, or the first visit after the update that ships this feature). It
  lists what is shared and what never is, with two explicit buttons: *Enable* / *No thanks*.
  Members never see it. Closing it without answering keeps it `UNSET` (off) and asks again on the
  next page load. In demo mode it never shows.
- **Settings.** *Administration → Anonymous telemetry* has a switch, disabled with an explanation
  when no DSN is configured. Turning it off takes effect immediately: the backend closes its SDK
  in-process and the browser closes its SDK on the spot.
- **Enabling without a DSN** is refused (`409`): consent given to a collector that does not exist
  yet must not silently activate once one is configured.

Effective state = DSN configured **and** consent `ENABLED`.

## What is collected

Errors:

- Exception type, module and sanitized stack frames. Exception messages are dropped entirely:
  regex scrubbing cannot reliably remove names, merchant names or account labels from free text.
- Only the fixed usage messages `page_view` and `feature_used` are retained; arbitrary event
  messages and formatted log messages are not collected.
- Stack frames: file path (query stripped, ids templated), function, module, line, column, in-app.
  No local variables, no source context lines.
- Mechanism (`type`, `handled`).
- App release (`APP_VERSION`), environment (`APP_TELEMETRY_ENVIRONMENT`, default `production`),
  platform, level, logger, timestamp, event id.
- Coarse context: OS name, browser name, runtime name and version.

Usage (browser only, no payload):

- **Page views**: one `page_view` message per route change, tagged with the route **template**
  (`/accounts/:id`, never `/accounts/123`). Unknown routes are reported as `not-found`.
- **Feature usage**: one `feature_used` message tagged with an event name from a closed list
  (`sync_triggered`, `export_downloaded`, `goal_created`, `budget_viewed`).

Tags are allowlisted: `route`, `event`, `feature`, and keys starting with `ab.` (reserved for the
1.2 A/B tests). Values are scrubbed and capped at 64 characters.

## What is never sent

Amounts, balances, transactions, labels, account or bank names, IBANs, card numbers, emails, user
names, holdings, goals, budgets, tokens, cookies, headers, request or response bodies, query strings,
IP addresses, session replays, breadcrumbs (DOM text, console, fetch), `extra`, `user`, `request`,
`server_name`, `modules`, `debug_meta`.

Concretely, the SDKs are configured so most of this is never produced in the first place:

- Browser (`@sentry/react`, loaded with a dynamic `import()` only after consent): no default
  integrations (no breadcrumbs, no tracing, no replay, no session tracking, no HTTP context); only
  the global error handlers and linked errors. `dataCollection` disables user info, cookies,
  headers, bodies, query params, frame variables and context lines. `attachStacktrace: false`
  (otherwise every usage message carries a synthetic stack and is stored as an error),
  `maxBreadcrumbs: 0`,
  `beforeBreadcrumb` drops everything, client reports off.
- Backend (`io.sentry:sentry`, plain SDK, **not** the Spring Boot starter): `sendDefaultPii=false`,
  no breadcrumbs, no uncaught-exception handler, no session tracking, no modules, no server name,
  no external configuration (`SENTRY_*` env vars and `sentry.properties` are ignored). Only
  unhandled exceptions reaching `GlobalExceptionHandler` are captured.

Whatever the SDK still produces is then rebuilt from the allowlist; an unknown field added by a
future SDK version is dropped by default.

## Scrubbing rules

Applied identically in `frontend/src/lib/telemetry-scrub.ts` and
`backend/.../telemetry/TelemetryScrubber.java` to every kept string:

| Pattern | Replacement |
|---|---|
| IBAN (`FR76 3000 6000 …`) | `[iban]` |
| Card number (13–19 digits, spaces/dashes allowed) | `[card]` |
| Email | `[email]` |
| `Bearer …`, JWT (`eyJ….….…`), `key=`/`token=`/`password=`/`secret=` values | `[token]` |
| IPv4 address | `[ip]` |
| Amount with decimals or currency (`1 234,56 €`, `€12.50`, `12.50 EUR`, `1500 euros`) | `[amount]` |
| Any other number of 4+ digits | `[n]` |
| URL | origin, query and fragment removed; numeric / UUID / long hex or base64 segments → `:id` |

## Server-side tunnel

`POST /api/telemetry/tunnel` (any authenticated user, same-origin CSRF rules as the rest of the
API, max 200 KB → `413`):

- Answers `204` and forwards nothing unless telemetry is effectively enabled.
- Keeps only `event` items (max 5 per envelope); sessions, replays, attachments, transactions and
  client reports are dropped. Each event is re-scrubbed.
- Rebuilds the envelope header with the **configured** DSN and `sent_at` only. The DSN sent by the
  client is ignored, so the tunnel can never be used to reach another host. Redirects are not
  followed.
- Forwards to `<scheme>://<host>/api/<project>/envelope/` with
  `X-Sentry-Auth: Sentry sentry_version=7, sentry_key=<key>, sentry_client=picsou-tunnel`.
- Limits each authenticated principal to 30 requests per minute before reading the body.
- Uses asynchronous HTTP sends, with at most 16 in flight and no waiting queue. Disabling
  consent or shutting down cancels pending sends. Rate-limit or capacity overflow answers
  `429` with `Retry-After: 60`; the request timeout remains five seconds.
- Forward failures are logged at `ERROR` and swallowed (`204`), so the endpoint is not an oracle.
- Parser and SDK scrubber failures log fixed identifiers and exception types only, never raw
  parser excerpts or event contents.
- Envelope parsing uses a bounded byte-buffer cursor. Declared lengths must be exact integers
  within the remaining bytes; fractional, overflowing or truncated lengths reject the envelope
  without forwarding earlier events. Collector credentials are bound from server configuration
  before any client-envelope-dependent branch.

## API

| Method | Path | Who | Notes |
|---|---|---|---|
| `GET` | `/api/telemetry/config` | authenticated | `{enabled, dsn, environment, release, available, consent}`; `dsn` is `null` unless enabled |
| `POST` | `/api/telemetry/tunnel` | authenticated | Sentry envelope, see above |
| `GET` | `/api/admin/settings` | admin | gains `telemetry: {available, consent}` |
| `PUT` | `/api/admin/settings/telemetry` | admin | `{enabled: boolean}` → `204`; `409` without DSN |

## Configuration

| Env var | Default | Effect |
|---|---|---|
| `APP_TELEMETRY_DSN` | empty | Collector DSN. Empty = feature fully inactive |
| `APP_TELEMETRY_ENVIRONMENT` | `production` | Environment tag |
| `APP_VERSION` | `dev` | Release tag (already used for the version display) |

## Code map

- Backend: `com.picsou.telemetry` (`TelemetryService`, `TelemetryScrubber`,
  `TelemetryEnvelopeSanitizer`, `TelemetryDsn`, `SentryTelemetrySdk`), `TelemetryController`,
  `AdminController#updateTelemetry`, capture in `GlobalExceptionHandler#handleGeneric`.
- Frontend: `lib/telemetry.ts` (SDK lifecycle, `trackPageView`, `trackFeature`),
  `lib/telemetry-scrub.ts`, `components/shared/TelemetryRuntime.tsx` (init/shutdown + page views),
  `components/shared/TelemetryConsentDialog.tsx`, `pages/admin/sections/TelemetrySection.tsx`,
  capture in `ErrorBoundary`.
- Demo mode: `GET /telemetry/config` always answers disabled; nothing is ever sent.

## Tests

- No event without consent / without DSN: `TelemetryServiceTest`, `telemetry.test.ts`
  (SDK module never even imported), `TelemetryRuntime.test.tsx`, `TelemetryEndpointsTest`.
- Scrubbing of a payload with a fake IBAN, amount, email, card, JWT and bearer token:
  `TelemetryScrubberTest`, `telemetry-scrub.test.ts`, `SentryTelemetrySdkTest`.
- Route ids replaced by templates: `TelemetryScrubberTest#routeTemplate_*`,
  `telemetry-scrub.test.ts`, `TelemetryRuntime.test.tsx`.
- Tunnel host pinning and 200 KB limit: `TelemetryServiceTest#tunnel_*`, `TelemetryEndpointsTest`.

## GlitchTip server-side hardening (recommended)

The client-side and tunnel scrubbing are the primary guarantees; configure the collector as a third
layer, in the GlitchTip project/organization settings:

- Enable **Scrub IP addresses** and **Prevent storing IP** (the tunnel already hides the user's IP,
  but the Picsou server's own address would otherwise be stored on every event).
- Enable the **data scrubber** (default scrubbing of fields such as `password`, `secret`, tokens,
  card numbers).
- Keep the project's event retention short; the reports have no identifier to purge by user.

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Plain `io.sentry:sentry`, programmatic init | No autoconfiguration, no default PII, initialised/closed in-process when consent changes | `sentry-spring-boot-starter` (auto-registers integrations, request/user capture) |
| `enableUncaughtExceptionHandler=false` | It installs a JVM-global default handler; capture is explicit in `GlobalExceptionHandler` instead, so the surface is small and only consented, scrubbed events are possible | Global handler (would also catch third-party thread crashes we cannot reason about) |
| One allowlist over JSON, shared by `beforeSend` and the tunnel | The SDK event is serialized, scrubbed as a map, and deserialized back; a field added by a future SDK version is dropped by default | Hand-rolled field nulling on `SentryEvent` (denylist, drifts) |
| Consent in `app_setting` | Instance-wide choice, no Flyway migration | A per-user column |
| `java.net.http.HttpClient`, redirects `NEVER`, 5 s timeouts | The envelope may only ever reach the configured host | Following redirects / `RestClient` defaults |
| Enabling without a DSN → `409` | Consent must not silently activate once a collector is configured later | Storing the consent anyway |

## Gotchas / Pitfalls

- `TelemetryConfigResponse.dsn` carries `@JsonInclude(ALWAYS)`: the app serialises with
  `default-property-inclusion: non_null`, which would otherwise omit the explicit `"dsn": null`.
- `GlobalExceptionHandler` resolves optional telemetry through constructor-injected
  `ObjectProvider<TelemetryService>`; standalone MockMvc tests can still use its no-argument constructor.
- Consent and DSN are re-read on every call; there is no cache to invalidate.
- The consent prompt reads the lightweight telemetry configuration rather than admin settings.
  Browser config follows the shared stale-time policy and is invalidated after consent changes.
- Runtime cleanup closes the browser SDK on logout/unmount, including pending imports. New
  initialization waits at most one second for the old client to close. Shutdown synchronously
  disables and detaches the client, drops queued events through session gates and aborts in-flight
  fetches before closing, so a stalled collector cannot block re-enabling. Bytes already delivered
  before revocation cannot be recalled.
- The budget overview emits `budget_viewed` once per mount, with no financial payload, through
  the same consent-gated tracking helper.
- The tunnel reads the body by hand (browser SDK sends `text/plain`), caps it at 200 KB, and never
  trusts the `dsn` in the incoming envelope header.

## Follow-up (1.2)

A/B variants will be attached as `ab.<experiment>=<variant>` tags on the same usage events, under
the same consent. Whether GlitchTip tags are enough for A/B analysis, or a privacy-first analytics
tool is added beside it, is still to be decided.
