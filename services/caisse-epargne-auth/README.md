# caisse-epargne-auth (session replay transport + accounts)

FastAPI sidecar that proves a captured Caisse d'Epargne SSO session can still
mint a short-lived Bearer token, without a browser, and reads the accounts and
their transactions with it. The login itself runs in a real browser (see
"Login by browser" below).

## Chain replayed (observed on the real bank, 2026-10-06/07)

1. `POST {authorize}?<params>` with a fresh PKCE `code_challenge` (S256) and `nonce` -> `{action, parameters{SAMLRequest}}`
2. `POST action` (form `SAMLRequest`, SSO cookies) -> `response.status == "AUTHENTICATION_SUCCESS"` and `response.saml2_post{action, samlResponse}`
3. `POST saml2_post.action` (form `SAMLResponse`) -> `parameters.code`
4. `POST {token}` (form `grant_type=authorization_code, client_id, code, code_verifier, redirect_uri`) -> `{access_token, token_type: Bearer, expires_in}` (about 267 s)

Step 2's status is the validity signal. Any other status, a 401/403, or an empty
or unusable body means the session is dead (`SESSION_EXPIRED`).
`/gsu/sessions/me/status` is not used: it answers 202 on live sessions too.

## API

All routes except `GET /health` need the header `X-Picsou-Sidecar-Key`
(`APP_SIDECAR_API_KEY`, compared in constant time). The service refuses to
start when the variable is missing or blank.

- `GET /health` -> `{"status": "ok"}`
- `POST /token-check` body `{"sessionState": "<json string>"}` -> `{"ok": true, "expiresIn": <seconds>}`

- `POST /accounts` body `{"sessionState": "<json string>"}` -> `{"accounts": [...], "unsupported": [...]}` (contract below)

`sessionState` is a JSON string:
`{"cookies": [{"name","value","domain","path"}], "authorizeParams": {...}}`.
`authorizeParams` is the authorize query captured at login; it is opaque apart
from `client_id` and `redirect_uri`, which the token request repeats. Each
cookie keeps its own domain and path.

| HTTP | `detail` | Meaning |
| --- | --- | --- |
| 400 | `INVALID_SESSION_STATE` | bad body or session state |
| 401 | `SESSION_EXPIRED` | the bank no longer accepts the SSO session |
| 502 | `UPSTREAM_ERROR` | any other bank or transport failure, or a URL outside the allow-list |
| 500 | `INTERNAL_ERROR` | unexpected bug, no detail leaked |

### `POST /accounts`

Token via the same replay chain and in-memory cache as `/token-check`, then
GET-only calls on `https://www.rs-ext-bad-ce.caisse-epargne.fr` with
`Authorization: Bearer <token>` and `Accept: application/json`:

- `GET /bapi/contract/v2/augmentedSynthesisViews?pfmCharacteristicsIndicator=true&productFamilyPFM=1,2,3,4,6,7,17,18,20,19`
- per imported account, `GET /pfm/user/v1.1/transactions` with the query observed in the spike (`accountIds=<id>`, ...), then `pageToken=<meta.nextPageToken>` until the token is absent. Hard cap: 20 pages per account, beyond that `UPSTREAM_FORMAT_CHANGED` (never a silent truncation).

Response 200 (money as decimal strings, never floats; a card's `balance` is its
outstanding, the sum of operations with a future `dueDate`, so <= 0):

```json
{"accounts": [{"externalId": "1001", "kind": "CURRENT_ACCOUNT|LIVRET_A|CARD", "name": "str|null",
  "balance": "1234.56", "currency": "EUR", "iban": "str|null", "ibanAmbiguous": false,
  "authorizedOverdraft": "str|null", "ceiling": "str|null", "remainingDepositCapacity": "str|null",
  "fillingRatio": "str|null", "cardNature": "CREDIT|DEFERRED_DEBIT|IMMEDIATE_DEBIT|null",
  "parentExternalId": "str|null",
  "transactions": [{"externalId": "9001", "date": "YYYY-MM-DD", "dueDate": "YYYY-MM-DD",
                    "amount": "-12.5", "currency": "EUR", "label": "str"}],
  "snapshotComplete": true}],
 "unsupported": [{"externalId": "1003", "familyCode": "7"}]}
```

All-or-nothing: any parse error gives `UPSTREAM_FORMAT_CHANGED`, never a partial list.

| HTTP | `detail` | Meaning |
| --- | --- | --- |
| 400 | `INVALID_SESSION_STATE` | bad body or session state |
| 401 | `SESSION_EXPIRED` | SSO session refused, or 401 on a data call |
| 502 | `UPSTREAM_UNAVAILABLE` | bank or transport failure (also a failed token chain) |
| 502 | `UPSTREAM_FORMAT_CHANGED` | payload does not match the observed format, or page cap exceeded |
| 500 | `INTERNAL_ERROR` | unexpected bug, no detail leaked |

Unverified, to confirm on the next live run: card transactions are requested
with `accountIds=<cardPfmId>` (isolated in `fetcher._fetch_card_pages`); if the
bank files them under another id, the import fails with
`UPSTREAM_FORMAT_CHANGED` instead of mis-filing rows.

## Safety

- SSRF guard: authorize, token, SAML and the `action` URLs returned by the bank must be `https` on `*.caisse-epargne.fr` (default port, no userinfo, no apex). A URL that fails is rejected before any request is sent. Redirects are never followed.
- Finite timeouts (10 s connect, 25 s per request), response bodies capped at 1 MiB, the httpx client is always closed.
- Cookies, SAML payloads, codes, tokens and authorize parameters are never logged or returned. The access token lives only in an in-memory cache until `expires_in - 30 s` (at most 64 entries) and is never returned by the API.
- Read-only target: the OAuth/SAML replay POSTs and, for `/accounts`, GET data calls only. No balance, label, IBAN or token is ever logged.

## Login by browser (`POST /initiate`, `POST /keypad`, `POST /complete`)

The login page computes the `password` it posts from the keypad clicks with its
own script, so the sidecar does not reproduce it. It does not guess the digits
either: the key images are sent to the UI, **the user clicks his own digits in
the pop-up**, and only the clicked positions come back. The password never
exists outside the user's browser. Same model as `services/amundi-auth`, plus
the user-clicked pad.

**Why not a digest table.** The key images are 10 PNG of ~920 bytes, shuffled at
every connection. Measured live on 2026-10-07: they are stable inside one login
(60 s, and across a reload) but different at the next one, so a fixed
image→digit table can never work.

`POST /initiate` body `{"customerId": "<digits, 1-20>"}`

| HTTP | Body | Meaning |
| --- | --- | --- |
| 200 | `{"processId", "keypad": {"images": [10 data URIs], "columns": 5}, "expiresInSeconds": 90}` | identifier accepted, the pad is up: the user must click his digits |
| 401 | `INVALID_CREDENTIALS` | the bank refused the identifier; also a non-numeric one, refused before any browser work |
| 409 | `KEYPAD_CHANGED` | the pad is not 10 keys, a key has no PNG image, two keys show the same image, or an image is over 16 KB. Zero clicks were made |
| 429 | `TOO_MANY_PENDING` | browser slots full (`MAX_CONCURRENT_BROWSERS = 2`) |
| 502 | `UPSTREAM_UNAVAILABLE` / `UPSTREAM_FORMAT_CHANGED` | login form not found, redirect not understood |

`POST /keypad` body `{"processId": "<from initiate>", "positions": [3, 0, 5, ...]}`
clicks those positions (6 to 12 of them, each 0-9, in the user's order), then
Valider once, then waits for the Sécur'Pass page. 200
`{"processId", "status": "SECURPASS_PENDING"}`. 422 `INVALID_POSITIONS` (refused
before any click, and the pending login is dropped); 409 `KEYPAD_CHANGED` when
the pad changed before a click, or on a second call for the same process;
408 `KEYPAD_EXPIRED` (over `KEYPAD_TTL_SECONDS = 90` after `/initiate`);
410 `AUTH_ATTEMPT_EXPIRED`; 401 `INVALID_CREDENTIALS`; 502 upstream. A process
accepts `/keypad` exactly once. The positions are never logged.

`POST /complete` body `{"processId": "<from initiate>"}` blocks up to 150 s
(`COMPLETE_WAIT_SECONDS`) for the human to approve on the phone. It never clicks
anything. 200 `{"sessionState": "<JSON string>"}` (the format `/token-check` and
`/accounts` take); 410 `AUTH_ATTEMPT_EXPIRED` (unknown, already used, or older
than `PENDING_TTL_SECONDS = 300`); 408 `APP_VALIDATION_TIMEOUT`; 401
`INVALID_CREDENTIALS`; 502 `UPSTREAM_UNAVAILABLE` / `UPSTREAM_FORMAT_CHANGED`.
A process is single-use and its browser is always closed. A request body that
fails validation answers `400 INVALID_REQUEST`.

Rules:

- Nothing secret is stored, logged, echoed, put in an error, a screenshot, a
  trace or a video: neither the password (which only the page ever sees) nor the
  identifier, the positions, the key images or the cookies. Errors and logs
  carry codes, counts and exception types only.
- The pad is typed once: a refusal never triggers a second attempt, here or
  anywhere in the stack (a wrong password costs a bank attempt).
- Navigation is allow-listed: any request outside `https://*.caisse-epargne.fr`
  is aborted at the browser (`context.route`).
- The returned state keeps only cookies on `caisse-epargne.fr`, and the
  authorize query of the first `/api/oauth/v2/authorize` request made from the
  client space (without `code_challenge`, `code_challenge_method`, `nonce`),
  validated with `replay.parse_session_state` before it is returned.
- Capacity: at most 2 browsers at once (pending ones included); a sweeper closes
  expired pending logins; shutdown closes everything. A login waiting for a pad
  click ages out after `KEYPAD_TTL_SECONDS = 90`, one waiting for Sécur'Pass
  after `PENDING_TTL_SECONDS = 300`.
- Deadlines: `/initiate` is cut at `INITIATE_DEADLINE_SECONDS = 80` (backend gives
  90 s), `/keypad` at `KEYPAD_DEADLINE_SECONDS = 60` (backend gives 70 s),
  `/complete` at `COMPLETE_DEADLINE_SECONDS = 160` (backend gives 170 s), human
  wait included. On expiry the browser is closed, the slot released and the
  answer is 502 `UPSTREAM_UNAVAILABLE`. The human wait stays 150 s and the
  reload / authorize wait after it are clamped to the 10 s left.
- Error banner: only read on the identifier page (identifier refused) and on the
  password page right after Valider. Never during the Sécur'Pass wait: there only
  the client-space URL or the timeout decide, so an informational alert can never
  become a false `INVALID_CREDENTIALS` (the user would retype and risk a lock).
- Browser context: service workers blocked; WebSocket connections to hosts outside
  `https://*.caisse-epargne.fr` (as `wss://`) are closed (`context.route_web_socket`).
- The 10 key images are re-read and compared to the pinned ones before every
  click; any change (reshuffle, missing image, extra key, bigger image) stops
  with `KEYPAD_CHANGED` and no further click.
- A maximum of 16 KB per key image, PNG only: anything else is `KEYPAD_CHANGED`.
  The UI must render the images with `<img src>` and must not treat them as HTML.
- Not observed live yet: the exact error-banner selector
  (`login.ERROR_SELECTOR`) and the Sécur'Pass screen text selector. Detection
  also works on the `(modal:icg/cloudcard)` URL, which was observed.

## Not implemented here

Logout.

## Tests

```
python3 -m venv "$TMPDIR/ce-venv"          # outside the repo
"$TMPDIR/ce-venv/bin/pip" install -r requirements.txt pytest
cd services/caisse-epargne-auth && "$TMPDIR/ce-venv/bin/python" -m pytest -q -p no:cacheprovider .
```

The data calls use `httpx.MockTransport`; the login uses a fake Playwright
(`fake_browser.py`): no browser, no network, no real credentials, no bank data.
`test_dockerfile.py` fails if the Dockerfile `COPY` misses a module that
`main.py` imports.
