# Picsou Backend API Reference

> This document is manually maintained. When adding or changing an endpoint, update this file accordingly.

## Overview

| Property | Value |
|----------|-------|
| Base URL | `/api` |
| Content-Type | `application/json` (except multipart endpoints noted below) |
| Authentication | JWT via HttpOnly cookies (`access_token` + `refresh_token`) |
| Access token TTL | 15 minutes |
| Refresh token TTL | 7 days (rotated on every use) |

### Auth flow

1. `POST /api/auth/login` — sends credentials, receives `access_token` + `refresh_token` as HttpOnly, SameSite=Strict cookies
2. All subsequent requests include cookies automatically — no header needed
3. On 401, the frontend calls `POST /api/auth/refresh` to get new tokens; the old refresh token is invalidated (rotation)
4. `POST /api/auth/logout` clears both cookies

### Rate limiting

| Endpoint group | Limit |
|---------------|-------|
| Login (`/api/auth/login`) | 5 requests / IP / 15 min |
| Bank sync (`/api/sync/initiate`, `/complete`, `/{id}/reconnect`, `/countries`) | Throttled — each on its own bucket, keyed by `ip + endpoint` |
| TR auth (`/api/tr/auth/initiate`) | Throttled |

## Shared Enums

### AccountType

`LEP` · `LIVRET_A` · `LDDS` · `LIVRET_JEUNE` · `PEL` · `CEL` · `PEA` · `COMPTE_TITRES` · `CRYPTO` · `CHECKING` · `SAVINGS` · `REAL_ESTATE` · `SCPI` · `LOAN` · `EMPLOYEE_SAVINGS` · `ASSURANCE_VIE` · `OTHER`

### WealthTier

`SAFETY_NET` · `REAL_ESTATE` · `EQUITY` · `CRYPTO` · `ALTERNATIVE`

The investment-pyramid layer an account or holding belongs to. Distinct from the Accounts
page's asset filters: those group accounts the way a user browses them, this one groups them
by the role they play in a portfolio.

### Chain

`SOLANA` · `ETHEREUM` · `BITCOIN`

### ExchangeType

`BINANCE` · `KRAKEN` · `MERIA`

### FinaryMappingAction

`SKIP` · `MAP_EXISTING` · `CREATE_NEW`

## Error Format

All errors use [RFC 7807 ProblemDetail](https://datatracker.ietf.org/doc/html/rfc7807):

```json
{
  "type": "about:blank",
  "title": "Unauthorized",
  "status": 401,
  "detail": "Invalid credentials"
}
```

Validation errors (422) include an `errors` map:

```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 422,
  "detail": null,
  "errors": {
    "name": "must not be blank",
    "targetAmount": "must be greater than 0.01"
  }
}
```

| Status | When |
|--------|------|
| 400 | `IllegalArgumentException` — bad request logic |
| 401 | `BadCredentialsException` — invalid credentials or missing auth |
| 404 | `ResourceNotFoundException` — entity not found |
| 422 | Validation failure (`@Valid`) — includes `errors` map |
| 429 | Rate limit exceeded |
| 502 | `SyncException` — upstream provider error |
| 500 | Unexpected server error (message is always `"An unexpected error occurred"`) |

---

## Endpoints

---

### 1. Authentication — `/api/auth`

#### `POST /api/auth/login`

- **Auth:** Public
- **Rate limit:** 5 / IP / 15 min

**Request body:**
| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `username` | `string` | @NotBlank, max 50 | |
| `password` | `string` | @NotBlank, max 128 | |

**Response `200`:**
```json
{ "username": "string" }
```
Sets `access_token` and `refresh_token` HttpOnly cookies.

**Errors:** 401 (invalid credentials), 429 (rate limited)

---

#### `POST /api/auth/refresh`

- **Auth:** Public (reads `refresh_token` cookie; also honors a valid `persistent_token` "Remember Me" cookie via `PersistentTokenAuthFilter`)
- **Body:** none

**Response `200`:**
```json
{ "username": "string", "role": "string", "memberId": 0, "displayName": "string" }
```
Rotates `access_token`/`refresh_token` (old refresh token is invalidated) whenever a valid `refresh_token` is presented, **or** when a still-valid `persistent_token` re-authenticates the request in place of a missing/invalid one (this is what lets "Remember Me" survive a tab/browser restart, since the frontend probes this endpoint on mount instead of trusting a stale client-side flag). `access_token`/`refresh_token` are reissued as **persistent cookies** (matching `persistent_token`'s remaining lifetime) only when the request actually carries a `persistent_token` owned by the same user — otherwise they're reissued as session cookies, so a non-"Remember Me" login can't outlive the browser via this endpoint. A Remember-Me `refresh_token` is bound to its persistent-session `series_id` (a `sid` claim); if that session has been revoked (`/auth/sessions`) or has passed its 90-day cap, the refresh is refused even though the JWT itself is still valid, so revoking a device actually logs it out at its next refresh.

**Errors:** 401 (no refresh token and no valid persistent_token; or the presented session's series has been revoked/expired — `"Session revoked"`)

---

#### `POST /api/auth/logout`

- **Auth:** Public
- **Body:** none

**Response `204`** — clears both cookies.

---

#### `POST /api/auth/change-password`

- **Auth:** Required

**Request body:**
| Field | Type | Constraints |
|-------|------|-------------|
| `currentPassword` | `string` | @NotBlank |
| `newPassword` | `string` | @NotBlank, min 8, max 128 |

**Response `200`:**
```json
{ "message": "Password updated successfully" }
```

**Errors:** 401 (current password incorrect), 422 (validation)

---

### 2. Dashboard — `/api/dashboard`

#### `GET /api/dashboard`

- **Auth:** Required
- **Body:** none

**Response `200` — `DashboardResponse`:**
```json
{
  "totalNetWorth": 15000.00,
  "netWorthHistory": [
    { "date": "2025-01-01", "total": 14000.00 },
    { "date": "2025-02-01", "total": 14500.00 }
  ],
  "distribution": [
    {
      "accountId": 1,
      "name": "PEA",
      "color": "#6366f1",
      "balanceEur": 8000.00,
      "percentage": 53.3
    }
  ],
  "goalSummaries": [ /* GoalProgressResponse[] — see Goals section */ ]
}
```

---

### 3. Accounts — `/api/accounts`

#### `GET /api/accounts`

- **Auth:** Required

**Response `200` — `AccountResponse[]`:**

```json
[
  {
    "id": 1,
    "name": "PEA Boursorama",
    "type": "PEA",
    "provider": null,
    "currency": "EUR",
    "currentBalance": 8000.00,
    "currentBalanceEur": 8000.00,
    "lastSyncedAt": "2025-03-15T10:30:00Z",
    "isManual": false,
    "color": "#6366f1",
    "ticker": null,
    "logoUrl": null,
    "logoKey": null,
    "createdAt": "2024-06-01T08:00:00Z",
    "openedAt": "2014-03-12"
  }
]
```

`logoUrl` is the bank logo captured from the sync provider's institution catalog (Enable
Banking only). `logoKey` names a logo bundled with the frontend — set on on-chain wallet
accounts, whose `provider` is a bare ticker, and settable by a client only on an account
that already carries one; see [the feature notes](../../docs/features/bank-logos.md).

`openedAt` is when the member says the wrapper was opened — omitted when they never have. It is
**not** `createdAt`, which dates the Picsou row: a PEA opened in 2014 and typed in last month has
a decade between the two, and the five-year tax clock runs from the former.

---

#### `GET /api/accounts/{id}`

- **Auth:** Required

**Response `200` — `AccountResponse`** (same shape as above).

**Errors:** 404 (account not found)

---

#### `POST /api/accounts`

- **Auth:** Required

**Request body — `AccountRequest`:**
| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `name` | `string` | @NotBlank, max 100 | Account name |
| `type` | `AccountType` | @NotNull | Account type enum |
| `provider` | `string` | max 100 | External provider name (optional) |
| `currency` | `string` | @NotBlank, max 10 | Currency code, e.g. `"EUR"` |
| `currentBalance` | `number` | @DecimalMin("0") | Balance in native currency |
| `isManual` | `boolean` | | Whether manually managed |
| `color` | `string` | Hex pattern | Display color, e.g. `"#6366f1"` |
| `ticker` | `string` | max 20 | Ticker for price lookup (optional) |
| `logoKey` | `string` | `^[a-z0-9-]{1,32}$` | Bundled frontend logo to show, e.g. `"ledger"` (optional). Honoured only on a `CRYPTO` account that already stores a key, i.e. an on-chain wallet — ignored on `POST` and on any other account, so a key can be swapped but never attached. Omitting it on `PUT` keeps the stored value: it is never cleared by a client that doesn't know about it |
| `openedAt` | `string` | @PastOrPresent, ISO-8601 date | When the wrapper was opened (optional). Relevant to the types whose taxation turns on the plan's age — a PEA at five years, an assurance-vie at eight. **Omitting it on `PUT` keeps the stored value**, for the same reason as `logoKey`: the MCP `update_account` tool has no such parameter, and treating null as "clear" would erase the date on any unrelated update. It can therefore be changed but not blanked |

**Response `201` — `AccountResponse`.**

**Errors:** 422 (validation)

---

#### `PUT /api/accounts/{id}`

- **Auth:** Required
- **Body:** same `AccountRequest` as POST

**Response `200` — `AccountResponse`.**

**Errors:** 404, 422

---

#### `DELETE /api/accounts/{id}`

- **Auth:** Required
- **Body:** none

**Response `204`.**

**Errors:** 404

---

#### `GET /api/accounts/{id}/holdings`

- **Auth:** Required

**Response `200` — `HoldingResponse[]`:**
```json
[
  {
    "ticker": "AAPL",
    "name": "Apple Inc.",
    "quantity": 10,
    "averageBuyIn": 150.00,
    "currentPrice": 195.00,
    "quoteCurrency": "USD",
    "currentValueEur": 1800.00,
    "costBasisEur": 1500.00,
    "pnlEur": 300.00,
    "pnlPercent": 20.00,
    "priceUpdatedAt": "2026-07-20T10:00:00Z",
    "priceAsOf": "2026-07-20",
    "priceStale": false
  }
]
```

`currentPrice` is expressed in `quoteCurrency`. `averageBuyIn`,
`currentValueEur`, `costBasisEur` and `pnlEur` are EUR-denominated.

`priceAsOf` is the day the EUR price is for, and `priceStale` is `true` when the price provider
could not be reached and the last recorded price (up to 7 days old) was used instead. The value is
still returned in that case — clients should display it and mark it, not hide it. Both are
`null`/`false` when no price could be resolved at all.

`priceUpdatedAt` answers a different question: it is the instant the stored price on the holding
was last refreshed, whereas `priceAsOf` is the calendar day that price *is for*. A holding synced
minutes ago can carry a `priceAsOf` of yesterday. It is `null` when the holding has never been
priced — a manually entered position, or one whose ticker no provider resolves.

> A crypto exchange account also exposes its per-product breakdown at
> [`GET /api/accounts/{id}/positions`](#get-apiaccountsidpositions), documented with the crypto
> exchange endpoints in section 9.

---

#### `GET /api/accounts/{id}/history`

- **Auth:** Required

**Query params:**
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `from` | `ISO-8601 date` | none | Start date filter |
| `to` | `ISO-8601 date` | none | End date filter |

**Response `200` — `BalanceSnapshot[]`:**
```json
[
  {
    "id": 1,
    "date": "2025-01-15",
    "balance": 7500.00,
    "createdAt": "2025-01-15T10:00:00Z"
  }
]
```

---

#### `POST /api/accounts/{id}/snapshot`

- **Auth:** Required

**Request body — `SnapshotRequest`:**
| Field | Type | Constraints |
|-------|------|-------------|
| `balance` | `number` | @NotNull, @DecimalMin("0") |
| `date` | `string` | @NotNull, ISO-8601 date |

**Response `201` — `BalanceSnapshot`.**

**Errors:** 404, 422

---

#### `GET /api/accounts/{id}/transactions`

- **Auth:** Required

**Response `200` — `TransactionDto[]`:**
```json
[
  {
    "id": 1,
    "date": "2025-01-15",
    "description": "Apple Inc. - Buy",
    "amount": -1500.00,
    "type": "buy",
    "category": "stock",
    "nativeCurrency": "EUR"
  }
]
```

#### `POST /api/accounts/export`

Builds an `.xlsx` workbook with one sheet per selected account — identity, positions, and property
or loan detail. Every id is resolved through the member-scoped read path, so an account outside the
caller's perimeter fails the request rather than appearing in the file.

- **Auth:** Required
- **Rate limit:** 20 per hour per user (`accountExportBuckets`)
- **No re-authentication**, unlike `POST /api/me/export` — this is a subset the user picks, not a
  full personal-data dump.

**Request body — `AccountsExportRequest`:**
| Field | Type | Constraints |
|-------|------|-------------|
| `accountIds` | `number[]` | @NotEmpty, @Size(max = 200) |
| `labels` | `object` | optional — column headings keyed by `LabelKey` name |

`labels` carries the localized column and section headings, because the backend has no message
bundle. Keys match the `LabelKey` enum case- and separator-insensitively (`ACCOUNT_NAME`,
`accountName` and `account-name` are the same key); unknown keys are ignored, and any key omitted
falls back to that column's English default. Omitting `labels` entirely yields a complete English
workbook, which is what makes this endpoint usable from `curl` or the MCP server. See
[the ADR](../../docs/decisions/2026-08-18-client-supplied-labels-for-xlsx-export.md).

```json
{
  "accountIds": [1, 4, 7],
  "labels": { "quantity": "Quantité", "averageBuyIn": "Prix de revient moyen" }
}
```

**Response `200`** — streamed workbook:

```
Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
Content-Disposition: attachment; filename="picsou-comptes-20260818-143211.xlsx"
```

**Errors:** 404 (an id the member may not read), 422 (empty or oversized `accountIds`),
429 (hourly quota spent)

A failure that happens after the response headers are flushed cannot become a `ProblemDetail`; it
is logged and the client receives a truncated file. See
[the feature note](../../docs/features/account-xlsx-export.md).

---

#### `POST /api/accounts/{id}/valuation/refresh`

Re-estimates a `REAL_ESTATE` account from open data. **Owner only** — a co-owner may read a
property but not move its balance.

- **Auth:** Required

Always answers `200`. A non-`OK` `status` is not an error: it explains why no figure could be
produced, which the UI renders as guidance.

| `status` | Meaning |
|---|---|
| `OK` | An estimate was produced |
| `UNSUPPORTED_AREA` | Outside coverage — Alsace-Moselle (57/67/68) and Mayotte keep the *livre foncier* registry |
| `NOT_ESTIMABLE` | Building, land, parking or commercial: no reliable price per m² |
| `INCOMPLETE_DATA` | Living area missing |
| `GEOCODING_FAILED` | Address could not be resolved to an INSEE commune |
| `NO_COMPARABLE_DATA` | Source answered with no usable sample |
| `PROVIDER_UNAVAILABLE` | Source unreachable; the previous valuation is kept |

**Response `200`:**
```json
{
  "status": "OK",
  "mode": "ESTIMATED",
  "appliedToBalance": true,
  "estimatedValue": 412000.00,
  "lowValue": 362560.00,
  "highValue": 469680.00,
  "pricePerSqm": 4336.00,
  "sampleSize": 1048,
  "confidence": "HIGH",
  "sourceYear": 2025,
  "provider": "CEREMA_DV3F",
  "scale": "communes",
  "valuedAt": "2026-08-01",
  "reindexRatio": 1.021,
  "adjustments": [
    { "code": "GARDEN", "factor": 0.02, "sqm": null, "amount": 8080.00 },
    { "code": "GARAGE", "factor": null, "sqm": 12, "amount": 52032.00 }
  ]
}
```

#### `GET /api/accounts/{id}/ownership`

Current split. Readable by co-owners.

**Response `200`:**
```json
{
  "shares": [
    { "memberId": 1, "displayName": "Alice", "avatarColor": "#6366f1", "sharePercent": 50, "isOwner": true },
    { "memberId": 2, "displayName": "Bob", "avatarColor": "#22c55e", "sharePercent": 50, "isOwner": false }
  ],
  "totalAssigned": 100,
  "unassigned": 0
}
```

#### `PUT /api/accounts/{id}/ownership`

Replaces the whole split. **Owner only.** An empty `shares` array clears it, restoring the
default where the owner holds 100%.

Only `REAL_ESTATE` and `LOAN` accounts may be split. `unassigned` above zero is legitimate —
that part is held outside Picsou and counts towards nobody's net worth.

**Request:**
```json
{ "shares": [{ "memberId": 1, "sharePercent": 60 }, { "memberId": 2, "sharePercent": 40 }] }
```

**Errors:** `422` if the sum exceeds 100, if the owner is absent from the split, if a member
appears twice, or if the account is not a property or a loan.

---

#### `GET /api/accounts/{id}/holdings/{ticker}/classification`

What the classification editor opens on. Returns the member's override **and** what the providers
inferred, as separate fields — merged into one "effective" value the form could not tell you
whether you are confirming a guess or reading your own earlier decision.

**Response `200`:**
```json
{
  "ticker": "AAPL", "wealthTier": null, "sectorKey": null, "countryKey": null,
  "inferredSectorKey": "technology", "inferredCountryKey": "US", "profileLooked": true
}
```

`profileLooked` is false when no lookup has run for the ticker, so "no sector" reads as "not
asked yet" rather than "unknowable". Readable-gated, not owner-gated: looking is not a write.

---

#### `PUT /api/accounts/{id}/holdings/{ticker}/classification`

The member's own verdict on what a holding is, overriding whatever was inferred from the account
type and the price providers. Needed because a wrapper does not determine the asset — a gold ETC
and a bitcoin ETP both live in an ordinary brokerage account.

**Body** — every field optional; null means "stop overriding this one", and the three are
independent so correcting a sector does not drop a tier set earlier:
```json
{ "wealthTier": "ALTERNATIVE", "sectorKey": "basic_materials", "countryKey": "FR" }
```

**Response `200`:**
```json
{ "ticker": "GLD", "wealthTier": "ALTERNATIVE", "sectorKey": null, "countryKey": null }
```

Stored per `(member, ticker)` rather than per holding row, so one correction covers the same
security in every account and survives a sync that drops and recreates the holding. Sending all
three fields null deletes the override. Requires ownership of the account, not merely read
access: a co-owner must not rewrite how someone else's holdings are counted.

### 4. Real estate — `/api/real-estate`

#### `GET /api/real-estate/summary`

Property wealth, already weighted by the member's shares.

**Response `200`:**
```json
{
  "grossValue": 412000.00,
  "outstandingDebt": 168400.00,
  "netValue": 243600.00,
  "costBasis": 368800.00,
  "unrealizedGain": 43200.00,
  "unrealizedGainPercent": 11.71,
  "loanToValue": 40.87,
  "monthlyRentalIncome": 0.00,
  "properties": [{ "accountId": 8, "name": "Résidence principale", "sharePercent": 100, "loans": [] }]
}
```

#### `GET /api/real-estate/{accountId}/valuations`

Past estimates, newest first. Readable by co-owners.

---

### 5. Analysis — `/api/analysis`

How the portfolio is built, rather than what it is worth. Every figure is weighted by the
member's ownership shares, exactly like `/api/dashboard`.

#### `GET /api/analysis/pyramid`

The five tiers, their weight against the member's targets, and the resulting score.

Built from **assets only**, never net worth — property enters net of the mortgage financing it,
and loans are otherwise excluded.

`tiers` carries the **four investment tiers only**, and their percentages sum to 100 over
`allocatableEur`. The cushion is not among them: it is measured in euros against an absolute
target in the `safetyNet` object, and a second line expressing the same money as a share would
contradict it.

`safetyNet.valueEur` counts **savings passbooks only** — a current account holds money already
committed to this month, so counting it would report a buffer that is largely spent.
`dailyCashEur` reports current-account money so it is visible; it is scored nowhere and, like the
cushion, sits outside `allocatableEur`.

Each tier line carries `targetEur` beside `targetPercent`, because a gap of "−6.36 points" is not
something a member can act on.

**Response `200`:**
```json
{
  "totalAssetsEur": 341700.00,
  "allocatableEur": 319200.00,
  "safetyNet": {
    "valueEur": 18200.00, "dailyCashEur": 4300.00, "targetEur": 11100.00,
    "coverage": 1.6396, "excessEur": 7100.00, "known": true, "score": 87
  },
  "tiers": [
    {
      "tier": "EQUITY", "valueEur": 142400.00, "actualPercent": 44.61,
      "targetPercent": 50.00, "targetEur": 159600.00, "gapPercent": -5.39,
      "accounts": [{ "accountId": 2, "name": "PEA", "color": "#6366f1", "valueEur": 96400.00 }]
    }
  ],
  "score": {
    "global": 91, "allocation": 86, "misplacedPercent": 13.51,
    "cryptoPenalty": 0.10, "leverageBonus": 4.28,
    "cryptoTopTenShare": 72.50, "loanToValue": 51.40
  }
}
```

`allocatableEur` is `totalAssetsEur - safetyNet.valueEur - safetyNet.dailyCashEur`; current-account
cash counts in the total and then leaves the allocation, so the four tiers divide `allocatableEur`
and their `actualPercent` sum to 100. The figures above are one consistent portfolio — the same one
`frontend/src/demo/data/analysis.ts` serves.

`safetyNet.known` is `false` when the member has never stated their monthly expenses. The tier
is then **unrated, not scored zero**: `safetyNet.score` is `null` and `score.global` falls back
to the allocation score plus the modifiers. `cryptoTopTenShare` is `null` when no crypto holding
was seen line by line — an exchange tracked as a single balance draws no penalty, because its
composition is unknown rather than poor.

#### `POST /api/analysis/security-profiles/refresh`

Warms the security profiles the diversification breakdown reads from, now rather than on the
weekly schedule. Exists because nothing warms the table on first read: a fresh install would show
a wholly unclassified breakdown until the following Sunday.

**Response `202`:**
```json
{ "queuedTickers": 36, "alreadyRunning": false }
```

`202`, never `200` — the scraping outlives the request by design (one or two HTTP calls per
ticker, no pacing), so it runs on a background thread. One pass at a time across the whole
instance: a call made while one is running returns `alreadyRunning: true` and `queuedTickers: 0`
rather than starting a rival pass. Capped at 40 tickers, like the scheduled pass, and
rate-limited per IP on the sync bucket since it reaches two unofficial sources.

Profiles are global reference data, so the pass covers every distinct ticker in the instance
rather than only the caller's.

---

#### `GET /api/analysis/diversification`

How the equity sleeve spreads across sectors and regions. ETFs are looked through to their
composition; a directly held share contributes its whole value to one sector and one country.

Reads **persisted profiles only** — never the network, so a page render can never block on a
scrape. `SchedulerService` warms the table weekly.

**Response `200`:**
```json
{
  "totalValueEur": 142400.00,
  "classifiedValueEur": 131800.00,
  "unclassifiedValueEur": 10600.00,
  "coveragePercent": 92.56,
  "unclassified": [
    {
      "ticker": "MC.PA", "name": "LVMH", "accountId": 3, "valueEur": 10600.00,
      "sectorMissing": false, "countryMissing": true, "profileLooked": true
    }
  ],
  "securities": [
    { "ticker": "IWDA", "name": "iShares Core MSCI World", "accountId": 2, "valueEur": 84200.00 }
  ],
  "sectors": {
    "score": 78, "effectiveCount": 4.68, "targetCount": 6, "basis": "MIXED",
    "classifiedValueEur": 118600.00, "coveragePercent": 83.29,
    "slices": [{
      "label": "technology", "percent": 31.40, "valueEur": 37240.40, "contributorCount": 3,
      "contributors": [{ "ticker": "IWDA", "valueEur": 26813.09, "sharePercent": 72.00 }]
    }]
  },
  "countries": {
    "score": 71, "effectiveCount": 2.14, "targetCount": 3, "basis": "MIXED",
    "classifiedValueEur": 131800.00, "coveragePercent": 92.56,
    "slices": [{
      "label": "US", "percent": 62.80, "valueEur": 82770.40, "contributorCount": 4,
      "contributors": [{ "ticker": "IWDA", "valueEur": 54628.46, "sharePercent": 66.00 }]
    }]
  }
}
```

`label` is a stable key — the same vocabulary `/api/securities/{ticker}/insight` uses, translated
client-side under `holdings.insight.sectorNames.*` / `countryNames.*`, with the raw value as the
fallback. `score` is `min(100, 100 × N_eff / targetCount)` where `N_eff = 1/Σw²`, the effective
number of positions: it separates 20/20/20/20/20 from 96/1/1/1/1, which counting buckets cannot.

A fund's published percentages are applied **literally**: a provider that discloses 70 % of a
fund's sectors places 70 % of the holding, and the rest lands in `unclassifiedValueEur`. Each
`Breakdown` therefore carries its **own** `coveragePercent`, because the two axes genuinely
diverge and the top-level figure reports the more generous of them.

Each slice names the holdings behind it, largest first. Contributors are capped at twelve and
anything under 0.5 % of the slice folds into a single entry with a `null` ticker;
`contributorCount` reports how many there really are. Names and accounts are not repeated per
slice — they live once in the top-level `securities` dictionary.

`unclassified` lists the lines a breakdown could not fully place, biggest first, with what the
editor needs to fix them: `accountId` because the write is account-scoped, and `profileLooked` to
separate "never looked up" (a refresh may still resolve it) from "looked up and still unknown"
(only a manual override can). The two axes are reported independently — a share often has a
sector and no domicile.

Both scores are computed over the **classified** part only. `coveragePercent`,
`unclassifiedValueEur` and `unclassified` travel with them so a breakdown over part of a
portfolio cannot be read as one over all of it. A ticker in `pendingTickers` has no profile yet —
"not looked up", not "unknowable".

`basis` is `EXPOSURE` when every contribution came from a fund look-through, `MIXED` once a
directly held share contributed its ISIN domicile. The two are different quantities; see the
[ADR](../../docs/decisions/2026-08-13-equity-domicile-vs-etf-exposure.md).

#### `GET /api/analysis/projection?years={n}`

The investable portfolio projected forward under four return assumptions, fed by the member's
recurring investment plans. `years` is clamped to 1–40 (default 20).

The base is **investable only** — the `EQUITY`, `CRYPTO` and `SAFETY_NET` tiers. Property, loans
and alternative assets are excluded from the headline curve: a house does not compound at an
equity rate, and including it would inflate every scenario. `baseValueEur` is returned so the
client can state what it is projecting from rather than letting it be mistaken for net worth.

The starting split comes from the **wealth pyramid**, not from account types, so the two panels of
one screen cannot disagree about the same euro: an account is broken down line by line and manual
overrides are honoured. Current-account money is excluded, as it is there.

**Response `200`:**
```json
{
  "baseValueEur": 96400.00,
  "monthlyInflowEur": 300.00,
  "years": 20,
  "scenarios": [
    {
      "key": "REFERENCE", "annualPercent": 6.40, "riskyDelta": 0.0,
      "points": [{ "date": "2026-08-31", "valueEur": 96400.00, "contributedEur": 96400.00 }]
    }
  ],
  "allocation": [
    {
      "date": "2036-12-31",
      "tiers": [
        { "tier": "REAL_ESTATE", "valueEur": 189351.00, "percent": 57.00, "targetPercent": 75.00 },
        { "tier": "EQUITY", "valueEur": 140000.00, "percent": 37.00, "targetPercent": 18.00 },
        { "tier": "SAFETY_NET", "valueEur": 16101.00, "percent": 1.00, "targetPercent": null }
      ]
    }
  ]
}
```

Scenarios run prudent to optimistic (`PESSIMISTIC`, `CAUTIOUS`, `REFERENCE`, `OPTIMISTIC`) and
are **spreads on risky assets**, not absolute rates: `riskyDelta` points are added to equity and
crypto only, because a passbook does not have a good year. `annualPercent` is the **blended rate
the scenario actually works out to** for this member — the same optimistic curve is 10 % for
someone fully invested and 6 % for someone half in cash — so a client must report it rather than
restate an assumption.

Each tier earns its own rate (cash 2 %, equity and crypto 7.5 %, property and alternatives 0 %),
and a plan is credited to the tier of the account it funds, at its own `expectedReturn` when one
was given. Contributions are share-weighted like the base. The maths is monthly using the
**geometric** rate `(1 + r)^(1/12) − 1` with contributions credited at month end; the points are
yearly.

`allocation[]` answers the other question: where the mix lands against the member's own targets,
under the reference scenario only. `targetPercent` is null for `SAFETY_NET`, which is measured in
euros against an absolute target rather than as a share.

#### `GET /api/analysis/allocation-targets`

The member's targets, or the shipped defaults when they have never set any. No row is created
by reading.

**Response `200`:**
```json
{
  "monthlyEssentialExpenses": 1850.00, "safetyNetMonths": 6,
  "realEstatePct": 30.00, "equityPct": 50.00, "cryptoPct": 10.00, "alternativePct": 10.00
}
```

#### `PUT /api/analysis/allocation-targets`

Replaces the whole profile. `monthlyEssentialExpenses` may be `null` — that is how a member
clears a figure they no longer stand behind, putting the safety net back to unrated.

**Errors:** `422` when the four percentages do not sum to exactly 100. The `errors` map keys
that violation under **`summingToOneHundred`** (a derived property), not under a field name —
cross-field validation on a record has no single field to attach to.

#### `GET /api/analysis/essential-expenses/estimate`

What the member's own transactions suggest they spend monthly, offered as a starting point for
the field above. **Never stored on their behalf** — accepting it is a `PUT`.

Mean monthly debits on `CHECKING` accounts over the last six *complete* months, with internal
transfers removed by counterparty matching (a debit whose amount reappears as a credit on
another readable account within ±3 days), investment legs dropped, and a narrow label heuristic
as a last resort. Divided by the months actually observed, not by six.

**Response `200`:**
```json
{ "estimate": 1912.40, "monthsObserved": 6, "excludedTransferCount": 11 }
```

`estimate` is `null` when there is no usable history — never `0`, which would be
indistinguishable from "this member spends nothing" and would set a target of zero.

---

### 6. Geocoding — `/api/geocode`

#### `GET /api/geocode?q={query}&limit={n}`

Address suggestions, proxied to the IGN Géoplateforme so the rate limit is enforceable.
Queries shorter than 3 characters return `[]`. Rate-limited to 60 lookups/minute per member;
exceeding it returns `429`.

---

### 7. Goals — `/api/goals`

Goals have a **type**: `SAVINGS_TARGET` (an amount by a deadline — what every goal was before
2026-08-13, and still is by default) or `RECURRING_INVESTMENT` (an amount every month, no target,
no deadline; it feeds `/api/analysis/projection`).

`type` may be **omitted** from any request body and defaults to `SAVINGS_TARGET`, so payloads
written before the field existed keep working unchanged.

| Field | `SAVINGS_TARGET` | `RECURRING_INVESTMENT` |
|---|---|---|
| `targetAmount`, `deadline` | required | must be absent |
| `monthlyAmount` | — | required |
| `expectedReturn`, `startDate`, `endDate` | — | optional |
| `allocations` | must be empty | optional |
| `accountIds` | one or more | exactly one |

`allocations` splits `monthlyAmount` across positions the funded account **already holds**:
`[{ "ticker": "CW8", "monthlyAmount": 250.00 }, …]`. It may be omitted (read as an empty list),
it may cover only part of the monthly amount — the remainder is simply unallocated — but it may
never exceed it, repeat a ticker, or name a ticker the account does not hold (`400`).

In the response each line carries the holding's `name` as well. **`allocations` is always present
as an array**, empty for a savings target and for an undetailed plan — deliberately unlike every
other nullable field here, because clients map over it.

In the response, the target machinery (`targetAmount`, `deadline`, `percentComplete`,
`monthlyNeeded`, `surplus`) is null for a recurring plan and dropped from the JSON. `monthsLeft`
and `isOnTrack` are primitives so they still appear, as `0` and `true` — **meaningless for that
type; discriminate on `type`, not on absence.**

**Errors:** `422` for a type/field mismatch. Those rules are cross-field, so the `errors` map keys
them under derived property names — `savingsTargetComplete`, `recurringComplete`,
`recurringSingleAccount`, `dateRangeOrdered`, `allocationOnlyOnRecurring`,
`allocationWithinMonthlyAmount`, `allocationTickersUnique` — not under a field name.
`400` (not `422`) for an allocation ticker the funded account does not hold: the client picks from
that account's own holdings, so an unknown one is a malformed request rather than a typo.

The monthly calendar and the history backfill (`/months`, `/history/extend`,
`/months/{yearMonth}` and their manual-contribution variants) apply to `SAVINGS_TARGET` only and
answer `400` for a recurring plan: they count towards a deadline it does not have.

#### `GET /api/goals`

- **Auth:** Required

**Response `200` — `GoalProgressResponse[]`:**
```json
[
  {
    "id": 1,
    "name": "Vacation Fund",
    "targetAmount": 3000.00,
    "deadline": "2025-12-31",
    "accounts": [ /* AccountResponse[] */ ],
    "currentTotal": 1200.00,
    "percentComplete": 40.0,
    "monthsLeft": 9,
    "monthlyNeeded": 200.00,
    "avgMonthlyContribution": 150.00,
    "isOnTrack": true,
    "surplus": -50.00,
    "allocations": []
  }
]
```

---

#### `GET /api/goals/{id}`

- **Auth:** Required

**Response `200` — `GoalProgressResponse`** (same shape as above).

**Errors:** 404

---

#### `POST /api/goals`

- **Auth:** Required

**Request body — `GoalRequest`:**
| Field | Type | Constraints |
|-------|------|-------------|
| `name` | `string` | @NotBlank, max 200 |
| `type` | `string` | Optional; `SAVINGS_TARGET` (default) or `RECURRING_INVESTMENT` |
| `targetAmount` | `number` | @DecimalMin("0.01"); required for a savings target |
| `deadline` | `string` | @Future, ISO-8601 date; required for a savings target |
| `monthlyAmount` | `number` | @DecimalMin("0.01"); required for a recurring plan |
| `expectedReturn` | `number` | −100 … 100, percent per year |
| `startDate`, `endDate` | `string` | ISO-8601 dates; `endDate` must follow `startDate` |
| `allocations` | `object[]` | `{ ticker (max 30), monthlyAmount (≥ 0.01) }`; recurring only |
| `accountIds` | `number[]` | @NotEmpty, list of account IDs |

**Response `201` — `GoalProgressResponse`.**

**Errors:** 422

---

#### `PUT /api/goals/{id}`

- **Auth:** Required
- **Body:** same `GoalRequest` as POST

**Response `200` — `GoalProgressResponse`.**

**Errors:** 404, 422

---

#### `DELETE /api/goals/{id}`

- **Auth:** Required
- **Body:** none

**Response `204`.**

**Errors:** 404

---

#### `GET /api/goals/{id}/months`

- **Auth:** Required

**Response `200` — `GoalMonthEntryResponse[]`:**
```json
[
  {
    "yearMonth": "2025-01",
    "objective": 200.00,
    "actual": 150.00,
    "override": null,
    "effective": 150.00
  }
]
```

---

#### `PUT /api/goals/{id}/months/{yearMonth}`

- **Auth:** Required
- **Path:** `yearMonth` in format `yyyy-MM`

**Request body — `GoalMonthOverrideRequest`:**
| Field | Type | Constraints |
|-------|------|-------------|
| `amount` | `number` | @NotNull, @DecimalMin("0") |

**Response `200` — `GoalMonthEntryResponse`.**

---

#### `DELETE /api/goals/{id}/months/{yearMonth}`

- **Auth:** Required
- **Path:** `yearMonth` in format `yyyy-MM`

**Response `200` — `GoalMonthEntryResponse`** (with `override: null`).

---

### 8. Bank Sync (Enable Banking) — `/api/sync`

#### `GET /api/sync/institutions`

- **Auth:** Required

**Query params:**
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `query` | `string` | `""` | Search filter |
| `country` | `string` | `"FR"` | ISO country code |

**Response `200` — `InstitutionData[]`:**
```json
[
  {
    "id": "Swan::FR::business",
    "name": "Swan",
    "bic": "SWNBFR22",
    "logoUrl": "https://...",
    "country": "FR",
    "psuType": "business"
  }
]
```

`id` is an opaque token encoding `name::country::psuType` — pass it back to
`/sync/initiate` verbatim. `psuType` is `personal` or `business`; business-only
banks (Swan and other BaaS providers) present a professional login at the
consent step.

---

#### `GET /api/sync/countries`

- **Auth:** Required
- **Rate limit:** Throttled (own bucket per IP, separate from `/initiate`'s)

Countries the active bank-sync provider supports, for the "which country" search filter/UI selector above — sourced from the provider (Enable Banking: `GET /application`'s `countries` field) rather than a hardcoded list. Enable Banking's result is cached in-memory for up to 6 hours.

**Response `200` — `string[]`** (ISO 3166-1 alpha-2 codes, ~29 entries for Enable Banking):
```json
["AT", "BE", "DE", "EE", "FR"]
```

**Errors:** 429, 502

---

#### `POST /api/sync/initiate`

- **Auth:** Required
- **Rate limit:** Throttled

**Request body:**
| Field | Type | Description |
|-------|------|-------------|
| `institutionId` | `string` | Bank identifier from `/institutions`, passed back verbatim — it encodes the bank name, country, and PSU type |
| `institutionName` | `string` | Display name |

**Response `200` — `InitiateResponse`:**
```json
{
  "requisitionId": "uuid",
  "authLink": "https://ob.nordigen.com/psd2/..."
}
```

**Errors:** 422 (validation — both fields required), 429, 502

---

#### `GET /api/sync/complete`

- **Auth:** Required

**Query params:**
| Param | Type | Description |
|-------|------|-------------|
| `code` | `string` | OAuth authorization code |

**Response `200` — `AccountResponse[]`.**

**Errors:** 401 (invalid code), 502

---

#### `GET /api/sync/status`

- **Auth:** Required

**Response `200` — `Requisition[]`:**
```json
[
  {
    "id": 1,
    "requisitionId": "uuid",
    "institutionId": "BNP Paribas::FR::personal",
    "institutionName": "BNP Paribas",
    "status": "LINKED",
    "authLink": null
  }
]
```

`status` values: `CREATED` · `LINKED` · `EXPIRED` · `FAILED`

---

#### `POST /api/sync/{id}/retry`

- **Auth:** Required

**Response `200` — `AccountResponse[]`.**

**Errors:** 404, 502

---

#### `DELETE /api/sync/{id}`

- **Auth:** Required

**Response `204`.**

**Errors:** 404

---

### 9. Trade Republic — `/api/tr`

#### `POST /api/tr/auth/initiate`

- **Auth:** Required
- **Rate limit:** Throttled

**Request body:**
| Field | Type | Description |
|-------|------|-------------|
| `phoneNumber` | `string` | Phone number |
| `pin` | `string` | Account PIN |

**Response `200` — `AuthInitResponse`:**
```json
{ "processId": "string" }
```

**Errors:** 429, 502

---

#### `POST /api/tr/auth/complete`

- **Auth:** Required

**Request body:**
| Field | Type | Description |
|-------|------|-------------|
| `processId` | `string` | From initiate step |
| `tan` | `string` | 2FA code from SMS |

**Response `200` — `AccountResponse[]`.**

**Errors:** 401, 502

---

#### `POST /api/tr/sync`

- **Auth:** Required
- **Body:** none

**Response `200` — `AccountResponse[]`.**

**Errors:** 401 (no active session), 502

---

#### `GET /api/tr/status`

- **Auth:** Required

**Response `200` — `SessionStatusResponse`:**
```json
{
  "isActive": true,
  "expiresAt": "2025-03-15T12:00:00Z"
}
```

---

#### `POST /api/tr/import`

- **Auth:** Required
- **Content-Type:** `multipart/form-data`
- **Field:** `file` (CSV)

**Response `200` — `AccountResponse[]`.**

---

#### `DELETE /api/tr/session`

- **Auth:** Required
- **Body:** none

**Response `204`.**

---

### 10. Bourse Direct — `/api/bourse-direct`

The connector is read-only. Authentication persists an encrypted browser
session, then portfolio import continues asynchronously.

#### `POST /api/bourse-direct/auth/initiate`

- **Auth:** Required
- **Rate limit:** Per IP

**Request body:**
```json
{ "login": "client-id", "password": "secret" }
```

**Response `200` — `BourseDirectAuthInitResponse`:**
```json
{ "processId": "uuid", "mfaRequired": true, "mfaType": "OTP" }
```

When `mfaRequired` is false, the encrypted session is already stored and its
first portfolio import is queued.

---

#### `POST /api/bourse-direct/auth/complete`

- **Auth:** Required
- **Rate limit:** Per IP

**Request body:**
```json
{ "processId": "uuid", "code": "123456" }
```

**Response `200` — `BourseDirectSessionStatus`**, normally with
`syncStatus: "QUEUED"`.

---

#### `POST /api/bourse-direct/sync`

- **Auth:** Required
- **Body:** none

**Response `202` — `BourseDirectSessionStatus`.** An already queued or running
job is not duplicated; its current status is returned.

---

#### `GET /api/bourse-direct/status`

- **Auth:** Required

**Response `200` — `BourseDirectSessionStatus`:**
```json
{
  "isActive": true,
  "expiresAt": null,
  "syncStatus": "SUCCESS",
  "lastSyncStartedAt": "2026-07-20T09:59:40Z",
  "lastSyncCompletedAt": "2026-07-20T10:00:00Z",
  "lastSyncError": null
}
```

`syncStatus` is one of `IDLE`, `QUEUED`, `RUNNING`, `SUCCESS`, or `FAILED`.

---

#### `DELETE /api/bourse-direct/session`

- **Auth:** Required

**Response `204`.** Imported accounts and history are retained.

Domain failures use `422` RFC 7807 responses with a stable `code` property:
`INVALID_CREDENTIALS`, `INVALID_OTP`, `AUTH_ATTEMPT_EXPIRED`,
`SESSION_EXPIRED`, `PORTFOLIO_INCOMPLETE`, `UPSTREAM_FORMAT_CHANGED`,
`UPSTREAM_UNAVAILABLE`, `INVALID_DATA`, or `INTERNAL_ERROR`. Authentication
rate limiting returns `429`.

---

### 11. Crypto Wallets — `/api/crypto/wallet`

#### `POST /api/crypto/wallet`

- **Auth:** Required

**Request body:**
| Field | Type | Description |
|-------|------|-------------|
| `chain` | `Chain` | `SOLANA` · `ETHEREUM` · `BITCOIN` |
| `address` | `string` | Wallet address |
| `label` | `string` | Display label |

**Response `200` — `AccountResponse`.**

---

#### `POST /api/crypto/wallet/{id}/sync`

- **Auth:** Required
- **Body:** none

**Response `200` — `AccountResponse`** (updated with latest balance).

---

#### `GET /api/crypto/wallet`

- **Auth:** Required

**Response `200` — `WalletStatusResponse[]`:**
```json
[
  {
    "id": 1,
    "chain": "ETHEREUM",
    "address": "0x...",
    "label": "My Wallet",
    "lastSyncedAt": "2025-03-15T10:00:00Z"
  }
]
```

---

#### `DELETE /api/crypto/wallet/{id}`

- **Auth:** Required

**Response `204`.**

---

### 12. Crypto Exchanges — `/api/crypto/exchange`

#### `POST /api/crypto/exchange`

- **Auth:** Required

**Request body:**
| Field | Type | Description |
|-------|------|-------------|
| `type` | `ExchangeType` | `BINANCE` · `KRAKEN` · `MERIA` |
| `apiKey` | `string` | Exchange API key (required, max 200 chars) |
| `apiSecret` | `string?` | Exchange API secret (max 300 chars). **Required** for `BINANCE` and `KRAKEN`; must be **omitted** for `MERIA`, which authenticates with a single read-only API key |

**Response `200` — `AccountResponse`.**

**Errors:**

| Status | When |
|--------|------|
| `400` | Blank API key; missing secret for an exchange that needs one; secret supplied for a single-key exchange |
| `422` | Bean-validation failure (`errors` map), the credentials were refused by the exchange, or the immediate sync failed |

---

#### `POST /api/crypto/exchange/{id}/sync`

- **Auth:** Required
- **Body:** none

**Response `200` — `AccountResponse`** (updated with latest holdings).

---

#### `GET /api/accounts/{id}/positions`

- **Auth:** Required

The per-product breakdown behind an account's holdings. **Empty** for every account that has none
(anything but a crypto exchange), in which case the client shows the flat holdings table instead.

**Response `200` — `ExchangePositionResponse[]`:**
```json
[
  { "product": "SPOT", "ticker": "BTC", "quantity": 0.01204, "principal": null, "interest": null,
    "averageBuyIn": 68000.0, "currentPriceEur": 92100.0, "currentValueEur": 1108.88,
    "costBasisEur": 818.72, "pnlEur": 290.16, "pnlPercent": 35.4,
    "priceAsOf": "2026-08-01", "priceStale": false },
  { "product": "STAKING", "ticker": "ATOM", "quantity": 33.154, "principal": 19.73, "interest": 13.424,
    "averageBuyIn": 6.4, "currentPriceEur": 5.65, "currentValueEur": 187.32,
    "costBasisEur": 212.19, "pnlEur": -24.87, "pnlPercent": -11.7,
    "priceAsOf": "2026-07-31", "priceStale": true }
]
```

`interest` is the yield **already included** in `quantity` (`principal + interest = quantity`), not
an amount to add. `principal`/`interest` are null for exchanges that don't report yield, and
`currentPriceEur`/`currentValueEur` are null for an asset with no CoinGecko mapping.

`priceAsOf` / `priceStale` carry the price's freshness, as on `HoldingResponse` above: the second
line is valued from the price recorded on 2026-07-31 because the provider did not answer.

Cost basis is tracked **per asset**, not per product: `averageBuyIn` comes from the asset's
`AccountHolding` and every line of the same asset shares it, with `costBasisEur = averageBuyIn ×
quantity`. The per-line figures therefore still add up to the holding's own cost and P&L.

---

#### `GET /api/crypto/exchange/status`

- **Auth:** Required

**Response `200` — `ExchangeStatusResponse[]`:**
```json
[
  {
    "id": 1,
    "exchangeType": "BINANCE",
    "status": "CONNECTED",
    "lastSyncedAt": "2025-03-15T10:00:00Z"
  },
  {
    "id": 2,
    "exchangeType": "MERIA",
    "status": "CONNECTED",
    "lastSyncedAt": "2025-03-15T10:00:00Z"
  }
]
```

---

#### `DELETE /api/crypto/exchange/{id}`

- **Auth:** Required

**Response `204`.**

---

### 13. Prices — `/api/prices`

#### `GET /api/prices`

- **Auth:** Required

**Query params:**
| Param | Type | Description |
|-------|------|-------------|
| `tickers` | `string` | Comma-separated ticker symbols, e.g. `"BTC,ETH,AAPL"` |

**Response `200`:**
```json
{
  "BTC": 45000.00,
  "ETH": 3000.00,
  "AAPL": 180.00
}
```

Prices are in EUR. Results are cached for 15 minutes.

---

### 14. Finary — `/api/finary`

Two import modes: **file-based** (XLSX upload) and **API-based** (direct sync). Both use a two-phase flow: preview then execute with account mappings.

#### `POST /api/finary/preview` (file-based)

- **Auth:** Required
- **Content-Type:** `multipart/form-data`
- **Field:** `file` (XLSX)

**Response `200` — `FinaryPreviewResponse`:**
```json
{
  "accounts": [
    {
      "finaryName": "Compte Courant",
      "finaryInstitution": "BoursoBank",
      "finaryCategory": "checking",
      "suggestedType": "CHECKING",
      "currentBalance": 2500.00,
      "nativeCurrency": "EUR",
      "transactionCount": 42
    }
  ],
  "existingPicsouAccounts": [ /* AccountResponse[] */ ],
  "totalTransactionCount": 128,
  "fileToken": "server-side-token"
}
```

---

#### `POST /api/finary/import` (file-based)

- **Auth:** Required

**Request body — `FinaryImportRequest`:**
```json
{
  "fileToken": "token-from-preview",
  "mappings": [
    {
      "finaryName": "Compte Courant",
      "finaryCategory": "checking",
      "action": "MAP_EXISTING",
      "targetAccountId": 5,
      "newAccount": null
    },
    {
      "finaryName": "PEA",
      "finaryCategory": "stock",
      "action": "CREATE_NEW",
      "targetAccountId": null,
      "newAccount": {
        "name": "PEA Finary",
        "type": "PEA",
        "provider": "Finary",
        "currency": "EUR",
        "color": "#10b981"
      }
    }
  ]
}
```

`action` values: `SKIP` · `MAP_EXISTING` · `CREATE_NEW`

**Response `200` — `FinaryImportResultResponse`:**
```json
{
  "accountsCreated": 1,
  "accountsMapped": 2,
  "accountsSkipped": 0,
  "snapshotsCreated": 3,
  "transactionsImported": 128,
  "importedAccounts": [
    {
      "id": 10,
      "name": "PEA Finary",
      "type": "PEA",
      "currentBalance": 8000.00,
      "color": "#10b981"
    }
  ]
}
```

---

#### `GET /api/finary/configured` (API-based)

- **Auth:** Required

**Response `200`:**
```json
true
```

Returns whether the Finary API credentials (`FINARY_EMAIL`, `FINARY_PASSWORD`) are configured.

---

#### `POST /api/finary/api-sync/preview` (API-based)

- **Auth:** Required

**Query params:**
| Param | Type | Description |
|-------|------|-------------|
| `totp` | `string` | TOTP 2FA code (if enabled) |

**Response `200` — `FinaryPreviewResponse`** (same shape as file-based preview, but with `syncToken` instead of `fileToken`).

---

#### `POST /api/finary/api-sync/execute` (API-based)

- **Auth:** Required

**Request body — `FinaryApiSyncExecuteRequest`:**
```json
{
  "syncToken": "token-from-preview",
  "mappings": [ /* same FinaryAccountMapping[] as file-based */ ]
}
```

**Response `200` — `FinaryImportResultResponse`** (same shape as file-based import).

---

### 15. Amundi Épargne Salariale — `/api/amundi`

Read-only. Amundi gates its login behind a captcha and a mandatory second
factor, so authentication is always interactive; it persists an encrypted
sidecar session, then plan import continues asynchronously. One account is
created per *dispositif* (PEE/PEG, PERCO, PER…), typed `EMPLOYEE_SAVINGS`.

#### `POST /api/amundi/auth/initiate`

- **Auth:** Required
- **Rate limit:** 5 attempts per IP per 15 minutes

**Request body:**
```json
{ "login": "identifiant", "password": "secret" }
```

**Response `200` — `AmundiAuthInitResponse`:**
```json
{ "processId": "uuid", "mfaRequired": true, "mfaType": "APP_PUSH" }
```

`mfaType` is `APP_PUSH` when the user must approve in the "Mon Épargne" app,
or `SMS` when a code is texted.

---

#### `POST /api/amundi/auth/complete`

- **Auth:** Required
- **Rate limit:** 5 attempts per IP per 15 minutes

**Request body** — `code` is omitted for an app push, since there is nothing
for the user to type:
```json
{ "processId": "uuid", "code": "123456" }
```

**Response `200` — `AmundiSessionStatus`**, normally with
`syncStatus: "QUEUED"`. For an app push the request stays open until the user
approves on their phone, or fails with `APP_VALIDATION_TIMEOUT`.

---

#### `POST /api/amundi/sync`

- **Auth:** Required
- **Rate limit:** 10 requests per IP per minute (shared `syncBuckets`)
- **Body:** none

**Response `202` — `AmundiSessionStatus`.** An already queued or running job is
not duplicated; its current status is returned.

---

#### `GET /api/amundi/status`

- **Auth:** Required

**Response `200` — `AmundiSessionStatus`:**
```json
{
  "isActive": true,
  "syncStatus": "SUCCESS",
  "lastSyncStartedAt": "2026-08-09T09:59:40Z",
  "lastSyncCompletedAt": "2026-08-09T10:00:00Z",
  "lastSyncError": null
}
```

`syncStatus` is one of `IDLE`, `QUEUED`, `RUNNING`, `SUCCESS`, or `FAILED`.

---

#### `DELETE /api/amundi/session`

- **Auth:** Required

**Response `204`.** Imported accounts and history are retained.

Domain failures use `422` RFC 7807 responses with a stable `code` property:
`INVALID_CREDENTIALS`, `CAPTCHA_BLOCKED`, `INVALID_OTP`,
`APP_VALIDATION_TIMEOUT`, `AUTH_ATTEMPT_EXPIRED`, `SESSION_EXPIRED`,
`PORTFOLIO_INCOMPLETE`, `UPSTREAM_FORMAT_CHANGED`, `UPSTREAM_UNAVAILABLE`,
`INVALID_DATA`, or `INTERNAL_ERROR`. Authentication rate limiting returns `429`.

---

### 16. DEGIRO — `/api/degiro`

The connector is read-only and **session-only**: DEGIRO's session cookie expires
after ~30 minutes of inactivity and Picsou never stores the account's TOTP
secret, so there is no scheduled background resync — every sync is user-initiated
and may require reconnecting. See
[`docs/decisions/2026-08-05-degiro-session-only-no-stored-totp.md`](../../docs/decisions/2026-08-05-degiro-session-only-no-stored-totp.md).

#### `POST /api/degiro/auth/initiate`

- **Auth:** Required
- **Rate limit:** Per IP — 5 attempts / 15 min

**Request body:**
```json
{ "username": "client-id", "password": "secret" }
```

**Response `200` — `DegiroAuthInitResponse`:**
```json
{ "processId": "uuid", "totpRequired": true }
```

When `totpRequired` is false, the encrypted session is already stored and a
first portfolio import has run.

---

#### `POST /api/degiro/auth/complete`

- **Auth:** Required
- **Rate limit:** Per IP — 5 attempts / 15 min (anti-bruteforce on the 6-digit code)

**Request body:**
```json
{ "processId": "uuid", "code": "123456" }
```

**Response `200` — `DegiroSessionStatus`.**

---

#### `POST /api/degiro/sync`

- **Auth:** Required
- **Body:** none

**Response `200` — `AccountResponse`.** Synchronous: the portfolio is fetched
with the stored session and the account is returned. Fails with `422` when the
session has expired, and the stored status flips to `REAUTH_REQUIRED`.

---

#### `GET /api/degiro/status`

- **Auth:** Required

**Response `200` — `DegiroSessionStatus`:**
```json
{
  "isActive": true,
  "status": "ACTIVE",
  "lastSyncedAt": "2026-08-05T10:00:00Z"
}
```

`status` is one of `ACTIVE`, `REAUTH_REQUIRED`, or `FAILED`. `REAUTH_REQUIRED`
is an expected, frequent state for this integration — not an error.

---

#### `DELETE /api/degiro/session`

- **Auth:** Required

**Response `204`.** Imported accounts and history are retained.

Domain failures use `422` RFC 7807 responses. Unlike Bourse Direct and Amundi,
DEGIRO does not yet set a stable `code` property — clients should treat the
absence of a code as a generic sync failure rather than parsing `detail`.
Authentication rate limiting returns `429`.

---

### 17. Member profile — `/api/me/profile`

The authenticated member's personal and fiscal context: age, marginal tax rate, household,
income, savings capacity, retirement horizon, risk profile. Read by the Goals page's savings
rate, and intended as context for exported data.

**Every field is optional and nullable.** A member who has never stated anything has no row at
all, and reading returns an all-null profile without creating one. `PUT` is a **full
replacement**: an omitted field clears what was stored, which is how a figure is withdrawn.

Two fields are derived and read-only:

- `age`, from `birthDate` — the date is what is stored, since an age is wrong the morning after a
  birthday.
- `monthlyNetIncome` = `monthlyNetBeforeTax × (1 − withholdingTaxRate / 100)`, rounded to cents.
  **Null unless both inputs are stated**: a blank withholding rate means "not said", not zero.

`annualGrossIncome` is fiscal context and feeds nothing — gross cannot reach net without a social
contribution rate, which this API deliberately does not ask for or assume.

#### `GET /api/me/profile`

- **Auth:** Required

**Response `200` — `MemberProfileResponse`:**
```json
{
  "birthDate": "1990-06-14",
  "age": 36,
  "marginalTaxRate": 30.00,
  "householdStatus": "COUPLE",
  "taxHouseholdParts": 2.50,
  "dependents": 1,
  "annualGrossIncome": 48000.00,
  "monthlyNetBeforeTax": 2750.00,
  "withholdingTaxRate": 7.30,
  "monthlyNetIncome": 2549.25,
  "monthlySavingsCapacity": 900.00,
  "targetRetirementAge": 62,
  "riskProfile": "DYNAMIC"
}
```

Null fields are omitted from the JSON, as everywhere else in this API.

---

#### `PUT /api/me/profile`

- **Auth:** Required

**Request body — `MemberProfileRequest`:**
| Field | Type | Constraints |
|-------|------|-------------|
| `birthDate` | `string` | @Past, ISO-8601 date |
| `marginalTaxRate` | `number` | 0 … 100, **percent** (30 means 30 %, not 0.30) |
| `householdStatus` | `string` | `SINGLE` or `COUPLE` |
| `taxHouseholdParts` | `number` | 1 … 20 |
| `dependents` | `number` | 0 … 20 |
| `annualGrossIncome` | `number` | ≥ 0; fiscal context, feeds nothing |
| `monthlyNetBeforeTax` | `number` | ≥ 0; the payslip's "net à payer avant impôt" |
| `withholdingTaxRate` | `number` | 0 … 100, percent (taux de prélèvement à la source) |
| `monthlySavingsCapacity` | `number` | ≥ 0 |
| `targetRetirementAge` | `number` | 40 … 90 |
| `riskProfile` | `string` | `PRUDENT`, `BALANCED`, `DYNAMIC` or `AGGRESSIVE` |

**Response `200` — `MemberProfileResponse`** (same shape as above).

**Errors:** 422
