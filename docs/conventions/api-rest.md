# Convention: REST API

## Endpoints

Each module registers its routes in the route registry (`backend-rs/src/routes.rs`), under `/api/`.

- **Base URL:** `/api` (the backend serves the SPA and the API on one port)
- **No URL versioning** — all endpoints sit directly under `/api/` without a version segment
- **Naming:** plural resource nouns, e.g. `/api/accounts`, `/api/goals`, `/api/sync`
- **Standard HTTP verbs:** GET (read), POST (create or action), PUT (replace), PATCH (partial update), DELETE (remove)

### Auth

JWT authentication via **HttpOnly cookies with `SameSite=Lax`** — no Authorization header.

| Cookie              | TTL                       | Purpose                                                                       |
| ------------------- | ------------------------- | ----------------------------------------------------------------------------- |
| `access_token`      | 15 minutes                | Authenticates requests; carries `uid`, `role`, `tv` (token-version) claims    |
| `refresh_token`     | 7 days                    | Rotated on every use via `POST /api/auth/refresh`                             |
| `mfa_challenge`     | 5 minutes                 | Issued after correct password when 2FA is required; consumed by `/api/mfa/verify` |
| `persistent_token`  | 30 days (configurable)    | Opaque rotating "Remember Me" token; silent re-login by the auth layer |
| `reauth_proof`      | 5 minutes                 | Proof of a fresh re-authentication (`type=reauth`, `uid`, `tv`), set by `POST /api/auth/reauth` or an SSO `reauth` round trip; required by credential changes, accepted by the data export |

- The auth layer (`backend-rs/src/auth/`) reads `access_token` and gives handlers the `Auth` extractor. It also verifies the `tv` claim against the user's token version, so a password change immediately invalidates outstanding tokens.
- The same layer re-issues a fresh access token from a valid `persistent_token` and rotates the persistent token on every use (theft detection: a re-used old token revokes the family).
- **Member-scoped authorization.** Every handler takes the member from `Auth` (the effective `member_id`, with the admin override) and every query filters by `member_id`. Family-shared resources are gated by `SharingSettings` + `SharedResource` (see [`docs/features/multi-account-family.md`](../features/multi-account-family.md)). Never expose data from another member without going through this layer.
- Cookies are `SameSite=Lax` (not `Strict`): Safari iOS dropped Strict cookies on certain navigations. `Lax` alone does not stop CSRF: a page on a sibling subdomain is same-site and gets the cookies on its form POSTs, and many endpoints accept a body-less or multipart POST that needs no CORS preflight.
- **Cross-site request check.** The API chain rejects with 403 (`application/problem+json`) any `POST`/`PUT`/`PATCH`/`DELETE` that carries an auth cookie, no `Authorization` header, and comes from another origin (`Sec-Fetch-Site` other than `same-origin`/`none`, else a foreign `Origin`/`Referer`), unless that origin is on the CORS allow-list. No CSRF token is involved. See [`docs/features/security-cors-cookies.md`](../features/security-cors-cookies.md#csrf--cross-site-request-check).
- **Never change state on `GET`.** The check only covers state-changing methods, and `Lax` cookies ride along on top-level cross-site GET navigations. An action triggered from a redirect (e.g. the bank OAuth callback) is a SPA page that `POST`s to the API.
- The `Secure` flag follows `SECURE_COOKIES` (default `true`; set to `false` only for local HTTP dev).

### Rate limiting

Rate limits are policies of `backend-rs/src/http/rate_limit.rs`, consumed by handlers through `state.limits`.

| Endpoint group                                | Limit                                       |
| --------------------------------------------- | ------------------------------------------- |
| `POST /api/auth/login`                        | 5 requests / IP / 15 min                    |
| `POST /api/mfa/verify`, `/api/mfa/challenge`  | Throttled (anti-bruteforce on 6-digit code) |
| `POST /api/sync/initiate`                     | Throttled                                   |
| `POST /api/tr/auth/initiate`                  | Throttled                                   |
| `POST /api/bourse-direct/auth/initiate`, `/complete` | Throttled                            |
| `POST /api/degiro/auth/initiate`, `/complete` | Throttled (anti-bruteforce on 6-digit code) |
| `GET /api/me/export`                          | Throttled (GDPR export)                     |

When a limit is exceeded, the handler answers 429 directly.

### Success responses

| Status | Usage |
|--------|-------|
| `200 OK` | GET, PUT, POST (non-creation) |
| `201 Created` | POST that creates a resource |
| `202 Accepted` | POST that queues asynchronous work |
| `204 No Content` | DELETE, logout |

## Error format

All errors use [RFC 7807 ProblemDetail](https://datatracker.ietf.org/doc/html/rfc7807).

```json
{
  "type": "about:blank",
  "title": "Unauthorized",
  "status": 401,
  "detail": "Invalid credentials"
}
```

Validation errors (422) include an `errors` map with field-level messages:

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

Internal errors are never exposed: the client gets a generic 500 (see [error handling](./error-handling.md)).

Domain-specific failures may add a stable machine-readable `code` property.
Clients translate this code and must not infer a cause from localized or
human-readable `detail` text.

## Validation

- A request body implements `Validate` (`backend-rs/src/http/json.rs`): not blank, not null, size, minimum, future date, and so on
- The `Json<T>` extractor runs it before the handler
- Validation failures produce 422 (`AppError::Validation`)
- A business rule is checked in the domain code and answers 400 (`AppError::BadRequest`)

## Pagination

Not currently used. Each member has limited data volumes (one household-scale dataset), so all list endpoints return full arrays.

## JSON configuration

- Dates and timestamps are ISO-8601 strings.
- A `None` field is omitted (`#[serde_with::skip_serializing_none]` on response types).
- Field names are camelCase (`#[serde(rename_all = "camelCase")]`).
- Unknown properties in a request body are ignored.

### A nullable field arrives as `undefined`, never as `null`

The backend does not send `"targetPercent": null` — it omits the key. On the TypeScript side that
is `undefined`, so **`x === null` is false for every null the backend ever produces**. Rules:

- **Compare with `== null`** (or `??`) on any field the backend declares nullable. This is the one
  place the codebase prefers loose equality, and it is not a style preference.
- **Write fixtures the way the wire looks.** A test fixture that spells out `targetPercent: null`
  describes the DTO, not the response — it will pass while the browser throws. Omit the key.

Both halves were learned the hard way: `AllocationTrajectory` called `.toFixed()` on an omitted
`targetPercent` and took the whole Analyse → Répartition tab down with a `TypeError`, while every
test stayed green. Quieter cousins in the same payload printed `NaN` in a progress bar and the
literal string `"undefined"` in a form field.

## Reference

The complete endpoint reference is in [`backend-rs/docs/API.md`](../../backend-rs/docs/API.md). When adding or changing an endpoint, update that file.

## Don'ts

- **Never build an error response by hand** — return an `AppError`.
- **Keep handlers thin** — they read the request, call the domain code and shape the response.
- **Never return an upstream error or an internal message** — always a problem body through `AppError`.
- **Never bypass member scoping** — never query without filtering by the member `Auth` gives (or the explicit override on family-shared resources).
