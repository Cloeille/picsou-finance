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

`LEP` · `PEA` · `COMPTE_TITRES` · `CRYPTO` · `CHECKING` · `SAVINGS` · `REAL_ESTATE` · `LOAN` · `EMPLOYEE_SAVINGS` · `OTHER`

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
    "createdAt": "2024-06-01T08:00:00Z"
  }
]
```

`logoUrl` is the bank logo captured from the sync provider's institution catalog (Enable
Banking only). `logoKey` names a logo bundled with the frontend — set on on-chain wallet
accounts, whose `provider` is a bare ticker, and settable by a client only on an account
that already carries one; see [the feature notes](../../docs/features/bank-logos.md).

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

### 5. Geocoding — `/api/geocode`

#### `GET /api/geocode?q={query}&limit={n}`

Address suggestions, proxied to the IGN Géoplateforme so the rate limit is enforceable.
Queries shorter than 3 characters return `[]`. Rate-limited to 60 lookups/minute per member;
exceeding it returns `429`.

---

### 6. Goals — `/api/goals`

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
    "surplus": -50.00
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
| `targetAmount` | `number` | @NotNull, @DecimalMin("0.01") |
| `deadline` | `string` | @NotNull, @Future, ISO-8601 date |
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

### 7. Bank Sync (Enable Banking) — `/api/sync`

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

### 8. Trade Republic — `/api/tr`

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

### 9. Bourse Direct — `/api/bourse-direct`

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

### 10. Fortuneo — `/api/fortuneo`

The connector is unofficial and read-only. Browser state is encrypted at rest,
and a complete portfolio import runs asynchronously only after authentication.

#### `POST /api/fortuneo/auth/initiate`

- **Auth:** Required
- **Rate limit:** Per IP

**Request body:**
```json
{ "login": "client-id", "password": "secret" }
```

**Response `200` — `FortuneoAuthInitResponse`:**
```json
{ "processId": "uuid", "mfaRequired": true, "mfaType": "OTP" }
```

When `mfaRequired` is false, the encrypted session is already stored and its
first portfolio import is queued.

---

#### `POST /api/fortuneo/auth/complete`

- **Auth:** Required
- **Rate limit:** Per IP

**Request body:**
```json
{ "processId": "uuid", "code": "123456" }
```

**Response `200` — `FortuneoSessionStatus`**, normally with
`syncStatus: "QUEUED"`.

---

#### `POST /api/fortuneo/sync`

- **Auth:** Required
- **Body:** none

**Response `202` — `FortuneoSessionStatus`.** An already queued or running
job is not duplicated; its current status is returned. A synchronous executor
submission failure transitions the persisted job to `FAILED`.

---

#### `GET /api/fortuneo/status`

- **Auth:** Required

**Response `200` — `FortuneoSessionStatus`:**
```json
{
  "isActive": true,
  "expiresAt": null,
  "syncStatus": "SUCCESS",
  "lastSyncStartedAt": "2026-08-24T09:59:40Z",
  "lastSyncCompletedAt": "2026-08-24T10:00:00Z",
  "lastSyncError": null
}
```

`syncStatus` is one of `IDLE`, `QUEUED`, `RUNNING`, `SUCCESS`, or `FAILED`.
Only `FAILED` carries a non-null `lastSyncError`.

---

#### `DELETE /api/fortuneo/session`

- **Auth:** Required

**Response `204`.** Imported accounts and history are retained.

Domain failures use `422` RFC 7807 responses with a stable `code` property:
`INVALID_CREDENTIALS`, `INVALID_OTP`, `AUTH_ATTEMPT_EXPIRED`,
`SESSION_EXPIRED`, `INVESTOR_PROFILE_REQUIRED`, `PORTFOLIO_INCOMPLETE`,
`UPSTREAM_FORMAT_CHANGED`,
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
