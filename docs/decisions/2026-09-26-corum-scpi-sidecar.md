# ADR: CORUM client space fills existing SCPI accounts through a browser sidecar

> Date: 2026-09-26
> Status: ✅ Active

## Context

A SCPI share in Picsou is entered by hand: the user types the share count and the two
prices. That is fine until a scheduled plan or a reinvested dividend moves the quantity
every quarter, which is exactly the case on a CORUM real-estate contract — the holder
has a savings plan plus reinvested dividends running.

CORUM exposes no public API. Its client space is a SPA whose calls return precisely
what is needed, but reaching them means driving a login form. The portal was spiked
against a real account before any code was written, and the shape that came back
decides this ADR.

Three findings from that spike shaped it:

1. **No captcha, no second factor.** The login is a client id and a password. That is
   materially easier than Amundi, whose captcha and 2FA forced a two-step handshake with
   a pending browser held in memory.
2. **The withdrawal price is not in the contract payload.** The contract call names the
   funds and the envelope; a separate per-fund call is the only place a withdrawal
   price appears.
3. **The figure CORUM displays is not the withdrawal value.** `saving.value` is
   `quantity × unitPrice`, and `unitPrice` is the subscription-side price — it equals
   `averageUnitPrice`. On the spiked account the two differ by the entry fee, about 12 %
   of the position.

## What the first end-to-end run corrected

The spike read the client space from the browser, so it never had to replay the session.
Running the sidecar against the live account on 2026-09-26 showed that the spike had
carried three assumptions that the implementation inherited. All three are now covered by
`test_live_contract.py`.

- **The session is a cookie jar, not a cookie.** Replaying `ai_session` — the httpOnly
  cookie the SPA sets, and the obvious candidate — is answered `all_tokens_expired` on a
  session that is demonstrably alive. The tokens the API checks are `au_t` and `re_t`;
  `ai_session` is only the SPA's own flag. The whole CORUM-domain jar is captured, and
  login waits for `au_t` rather than for "a cookie arrived" — the pre-login page already
  sets Cloudflare cookies, so the looser condition returns a jar that fails on first read.
- **Cloudflare fingerprints the client, not the credential.** The same live cookies
  requested from the sidecar's own process are answered `403 Error 1010` — "the site
  owner has banned your browser's signature" — before any application code runs, while
  the identical request issued by the page answers 200. Every read therefore happens as a
  same-origin `fetch` inside the page. A side effect worth keeping: a missing or stale
  token now surfaces as the API's own 401 rather than as a bot-wall 403 we would have to
  guess about.
- **The per-fund route has no `realEstate` segment.** The contract reads as
  `/contract/realEstate/{code}/…` but the fund read as `/contract/{code}/…`. With the
  segment added, the 404 message reads like a fund that does not exist, which points the
  operator at the wrong cause.

None of these were reachable by the parser tests, which feed the code a dict rather than a
URL. That is the argument for `test_live_contract.py` existing at all.

## Decision

1. A dedicated internal-only FastAPI + Playwright sidecar, `services/corum-auth`, on the
   Chromium-only image the sidecar ADR already mandates. It is the only component that
   knows CORUM's HTML or its routes.
2. Credentials are ephemeral. Only the sidecar's opaque session blob — Playwright storage
   state plus the harvested cookie jar — is persisted, encrypted through `CryptoEncryption`.
   The password is never stored and never logged.
3. The cookie jar is harvested by watching the requests the SPA itself makes, not by
   reading a CORUM-internal storage key. Watching live traffic is also the capture point
   that does not depend on CORUM's internal storage keys, which is what makes this
   survive a front-end refactor.
4. The balance is always `quantity × withdrawalPrice`, written through
   `ScpiPositionService.applySyncedPosition` — the same method the manual form uses. A
   sync that valued the share any other way would reintroduce the entry-fee overstatement
   the manual model exists to prevent, so the rule lives in one place and both callers
   share it.
5. A sync does not create accounts. Picsou models one account per vehicle while a CORUM
   contract holds several funds, so a fund is matched to an account through
   `scpi_position.corum_fund_code`. A holding with no linked account is skipped: account
   creation belongs to the manual flow, and a sync that opened accounts on its own would
   bypass it.
6. A portfolio whose funds do not sum to the contract total is refused wholesale, in the
   sidecar and again in the service. The last known-good balances are kept.
7. CORUM Life, PER and capitalisation are insurance, not shares. One login can see all
   of them, and only the real-estate contract is read. A login holding more than one
   real-estate contract is refused as `MULTIPLE_CONTRACTS` rather than guessing.
8. Upstream I/O happens outside any transaction; the write is then one short
   transaction per position.

## Alternatives considered

### Let the sync create the accounts

- **Pros**: no manual linking step; the portfolio appears on its own.
- **Cons**: a fund code is not an account, and creating one silently bypasses the
  Immobilier flow a share is supposed to go through. It also makes a mis-read fund
  create a phantom account. Linking is one field the user already fills in.

### Value the share at CORUM's displayed figure

- **Pros**: one number, and it is what the holder sees in the portal.
- **Cons**: it is the subscription-side value. Adopting it would overstate net worth by
  the entry fee on every position, silently and in the direction that makes the app look
  better. This is the exact failure the SCPI ADR was written to prevent.

### Reuse the operations feed to derive the quantity

- **Pros**: a dated ledger instead of a snapshot.
- **Cons**: the feed is two calls (`/operations/` has no id and no quantity, then
  `detail/real-estate`), it has no redemption example in this account's history, and the
  ADR on SCPI shares already scoped automatic sync out. Snapshot first.

### A plain HTTP client with a pasted cookie

- **Pros**: no browser, smallest surface.
- **Cons**: the cookie is short-lived and unreadable from the page because it is
  httpOnly, so the user would have to copy it out of devtools before every sync.

## Reasoning

Choosing the sidecar is cheaper here than the Amundi precedent, but the isolation is the
same: CORUM's routes and DOM stay in one Python file, and `CorumPort` gives the domain a
typed contract that does not change when CORUM redesigns.

Valuing at the withdrawal price is the load-bearing decision. The whole SCPI model is
built so entry fees stay out of net worth, and a connector is exactly where that rule
would be quietly dropped — the portal offers a convenient number, it is the wrong number,
and nothing would fail.

Failing closed is inherited rather than invented. A SCPI position is long-horizon money
whose history matters more than intraday accuracy; a fund silently missing from a sync
would corrupt the net-worth series permanently, whereas a refused sync costs a retry.

## Trade-offs accepted

- An unofficial integration needing maintenance whenever CORUM ships a front-end change.
  Selector lists are redundant and failures are typed so that is diagnosable rather than
  mysterious.
- One more container.
- Public CI cannot prove the live login, so the sidecar's contract is tested against
  fixtures shaped like the real payloads.
- Multi-day cookie survival is unverified on a real account. A daily sync is the test,
  and an expired session deactivates itself and asks for a fresh login.
- No redemption example exists in the spiked history, so the quantity-decrease branch is
  unproven against a real row.

## Consequences

- New: `services/corum-auth/`, `CorumPort` / `CorumErrorCode` / `CorumAdapter`,
  `CorumSyncService`, `CorumController`, `CorumSession` / `CorumSyncStatus`,
  `CorumSessionRepository`, `CorumSyncConfig`, migration `V91`.
- `scpi_position` gains `corum_fund_code`, unique per member, and
  `ScpiPositionRequest` gains the matching field. `ScpiPositionService` gains
  `applySyncedPosition`.
- `docker-compose.yml`, both compose files, `.env.example` and the CI workflows gain the
  new sidecar.
