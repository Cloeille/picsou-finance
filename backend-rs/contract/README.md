# Contract harness

Replays scripted HTTP scenarios against the backend, each on its own fresh database, and reports every difference from the goldens in status, compared headers, cookies and normalized JSON body. The goldens were recorded from the Java backend of 1.2.x, removed in 1.3.0: they are the HTTP contract the backend keeps.

Bun only, no dependencies: Bun's built-in `SQL` client creates and drops the databases, `fetch` and `Bun.serve` do the HTTP.

## Running

From the repository root:

```sh
bun run backend-rs/contract/run.ts list                      # every scenario
bun run backend-rs/contract/run.ts check --all               # the backend vs the goldens, on PostgreSQL
bun run backend-rs/contract/run.ts check --all --sqlite      # the same on embedded SQLite
bun run backend-rs/contract/run.ts record accounts.example   # write golden/accounts.example.json
bun test backend-rs/contract                                 # the harness's own tests
```

On SQLite, two import-preview scenarios (`transfer.actual.import`, `transfer.homebank.ios`) differ from the goldens, which were recorded on PostgreSQL, in id numbering only.

A selector is a scenario name (`auth.mfa`) or a prefix (`auth`). The exit code is 0 only when nothing differs and nothing errored. `record` writes no golden for a scenario whose responses miss a step's `expect` status.

| Option | Default |
|---|---|
| `--rust-bin` / `CONTRACT_RUST_BIN` | `backend-rs/target/release/picsou` |
| `--pg host:port` / `CONTRACT_PG` | `127.0.0.1:55432`, user `picsou` (`--pg-user`, `CONTRACT_PG_USER`), trust auth |
| `--sqlite` | off: PostgreSQL. On, each instance gets its own SQLite file and no PostgreSQL is needed |
| `--jobs n` | 2 scenarios at a time |
| `--keep-logs` | keep each instance's work directory and `backend.log` |
| `--verbose` | instance URLs, log paths and every stub hit |

A backend that fails to start keeps its log and the error names its path.

## What an instance gets

`lib/launcher.ts` creates a database named `contract_<scenario>_<random>`, starts the backend on a free port with a minimal environment, waits for `/actuator/health/readiness`, and at the end stops it (SIGTERM, then SIGKILL after 15 s) and drops that database. Ctrl-C stops every running instance too.

The environment holds the variables `backend-rs/src/config.rs` reads, nothing is inherited from the shell except `PATH`:

- `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, an empty `SPRING_DATASOURCE_PASSWORD`, `SERVER_PORT`.
- `JWT_SECRET` and `CRYPTO_ENCRYPTION_KEY` generated per instance.
- `SECURE_COOKIES=false`, `APP_STARTUP_SYNC_ENABLED=false`, `APP_UPDATE_CHECK_ENABLED=false`, `INSTRUMENT_LOGOS_ENABLED=false`, `VALUATION_ENABLED=false`, `ADMIN_RECOVERY_ENABLED=false`, `HSTS_ENABLED=false`, `APP_TIMEZONE=Europe/Paris`, `PUBLIC_BASE_URL=http://localhost:5173`.
- Every configurable external base URL points at the harness's stub server: `CEREMA_API_URL`, `INSEE_API_URL`, `GEOCODING_API_URL`, `MERIA_BASE_URL`, `ENABLEBANKING_BASE_URL` and the 17 `*_AUTH_URL` sidecars. The stub answers 503 to everything.
- Hosts no variable redirects (price feeds, logos) go through the stub as an HTTP proxy (`HTTP_PROXY`, `HTTPS_PROXY`), which refuses them. A scenario never reaches the internet.

The harness's cookie jar follows RFC 6265: cookies keyed by name and path, default path the request's directory, `Max-Age` before `Expires`.

Requests carry the headers axios sends (`Accept: application/json, text/plain, */*`, `Content-Type: application/json` with a body), a fixed `User-Agent` and `Accept-Encoding: identity`: compression is not part of the contract.

## Writing a scenario

One module per domain under `scenarios/`, exporting `scenarios: Scenario[]`. Names are `<domain>.<case>` and unique; the golden is `golden/<name>.json`.

```ts
import { bootstrap, manualAccount } from "../lib/flows.ts";
import { day, get, post, sql, type Scenario } from "../lib/scenario.ts";

export const scenarios: Scenario[] = [{
  name: "accounts.example",
  description: "One line saying what the scenario proves",
  steps: [
    ...bootstrap(),                                      // setup wizard + admin login, not compared
    manualAccount("acct", { name: "Courant", currentBalance: 10 }),  // captures the new id as "acct"
    post("/api/accounts/{acct}/transactions", (v) => ({ date: day(-1), description: "x", amount: -1.5, txType: "WITHDRAWAL" }), {
      expect: 201,
      capture: { tx: (r) => r.json.id },
    }),
    get("/api/accounts/{acct}/transactions", { as: "member", expect: 404 }),
    sql("state no endpoint creates", (v) => `UPDATE account SET hidden = true WHERE id = ${v.id("acct")}`),
  ],
}];
```

- `as` names the cookie jar; the default is `admin`. Any new name is a new browser (`anon`, `member`, `laptop`). `flows.ts` has `bootstrap()`, `login()` and `inviteMember()` (admin invites, the member activates and signs in).
- `{var}` in a path is filled from captures. Goldens record the template, never the filled path.
- `json` and `multipart` take a value or a function of the captured `Vars`; `raw` sends a body verbatim (malformed JSON). `v.totp("secret")` computes a TOTP code from a captured base32 secret and waits for the next 30 s window rather than reuse one, since the backend rejects a replayed step.
- `expect` is the status the scenario means to produce. `compare: false` keeps setup noise out of the golden. `keep` trims a body whose size depends on the clock (the intraday series).
- `sql` steps write fixtures straight into the instance's database, for state only a bank sync creates (a sub-account's `parent_account_id`).
- Dates: use `day(n)`, `monthEdge(k, "first" | "last")`, or a literal date more than 400 days old. Their labels are stable; any other recent literal date normalizes to an offset that moves as the calendar does.
- Stay under the backend's rate limits: 5 logins per 15 minutes per instance, so per scenario.

After adding or changing a scenario: `record` it and read the golden's diff before committing it. A golden only changes when the HTTP contract is meant to change.

## Normalization

`lib/normalize.ts`, applied to every response in step order:

- **Ids.** A number under `id` or a `…Id` key, the items of a `…Ids` array, and numeric object keys (per-account maps) become `<id:N>`, numbered by first appearance in the scenario; object keys are walked sorted, so field order never shifts the numbering. A numeric string id becomes `<id:N>:string`, so `"5"` against `5` is still a difference. A known id inside a path (`instance`, `Location`) is replaced too. Mapping is by value, so an account and a transaction that share the raw id 1 share the placeholder.
- **Secrets.** Strings under `secret`, `recoveryCodes`, `password`, `…Token` and `…Hash` keys become `<secret:N>`.
- **Tokens.** JWTs become `<jwt>`; UUIDs `<uuid:N>`; the last path segment of a `…Link` or `…Url` value `<token:N>`; other runs of 20+ characters that mix upper case, lower case and digits, or long hex or base32 runs, `<token:N>`; `data:` URIs `<data-uri:image/png>`; PEM blocks `<pem:public-key>`. The same token keeps the same number. Words, enum names, dashed file names and IBANs stay literal.
- **Timestamps** become typed placeholders that keep their shape: `<instant>` (UTC, `Z`), `<offset-datetime>`, `<local-datetime>`, with `:no-seconds`, `:space` (space instead of `T`) or `:hhmm` (offset without colon) when they apply; `<http-date>`; `<compact-datetime>` for `20261121-142530` in file names. A format change (`Z` vs `+00:00`, missing seconds) is a difference; fractional-second precision is not.
- **Dates**, first match wins: a date the scenario wrote with `day(n)` or `monthEdge(k, edge)` keeps that label (`<today-1>`, `<month+18:last>`); today is `<today>`; a first or last day of a month within 36 months is `<month-1:first>`, `<month:last>`; other dates within 400 days `<today+19>`; older ones stay literal. `YYYY-MM` becomes `<month-1>`. Today is computed in Europe/Paris, the instance's zone. Two cases can still move with the calendar: a run across midnight, and a server date that is a month edge on the day it is also today or a date the scenario sent (a check on the first or last day of a month).
- **Cookies** keep their name, attributes (lower-cased names, `SameSite` value lower-cased, `Expires` as `<http-date>`) and whether the value is set or empty (a deletion). Values are never stored. Distinct cookies are sorted by name; same-name lines keep their order.
- **Headers.** Only `COMPARED_HEADERS` in `normalize.ts`: content type and disposition, caching, location, authentication challenge, retry-after, the security headers and CORS. `Date`, `Vary`, lengths and encodings are ignored.
- **Bodies.** JSON is parsed losslessly (`lib/json.ts`): numbers keep their source text, so `1.50` differs from `1.5`, reported as `scale differs` and counted apart. Object key order is ignored, array order is kept, a `null` field is not an absent one, and a duplicated key makes the body unparseable, so it is compared as text. Text bodies are normalized as strings; binary bodies (XLSX) compare by status and headers only.

## Not covered, and why

| Flow | Reason |
|---|---|
| Bank and broker sync (Enable Banking, the 17 sidecars, IBKR, SimpleFIN, Wise, Louve, Finary sync, Trade Republic) | needs a real bank session or an upstream API; the stub only proves they fail closed |
| Crypto exchanges and wallets | upstream APIs and blockchain RPC |
| Holdings priced by ticker, price history, dividends, security insight, metals | price providers are hard-coded hosts (blocked by the proxy) |
| Real-estate valuation, geocoding, SCPI catalog | open-data APIs; valuation is off |
| SSO / OIDC sign-in, OAuth authorization server, MCP over SSE | needs an identity provider or a streaming client |
| AI categorization | needs a model provider |
| GDPR ZIP export, Actual / HomeBank / Finary file imports | not yet scripted; local only, good next scenarios |
| Admin recovery, update check, telemetry tunnel | need a restart, the internet or a DSN |
