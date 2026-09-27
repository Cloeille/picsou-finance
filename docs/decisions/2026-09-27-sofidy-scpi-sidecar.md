# ADR: Sofidy Espace Associé fills existing SCPI accounts through a browserless sidecar

> Date: 2026-09-27
> Status: ✅ Active
> Depends on: [#159 — the CORUM SCPI sidecar](2026-09-26-corum-scpi-sidecar.md)

## Context

A SCPI share in Picsou is entered by hand: the user types the share count and the two
prices. That is fine until a scheduled plan or a reinvested dividend moves the quantity
every quarter, which is exactly the case on a savings plan at a real-estate manager.

Sofidy exposes no public API. Its client space is server-rendered PHP behind a six-digit
associate code. The portal was spiked against a real account before any code was written,
and the shape that came back decides this ADR.

Three findings from that spike shaped it:

1. **The withdrawal price is in the portfolio page.** `3,clients.html` prints a
   "Valeur unitaire" column whose footnote states it is the withdrawal price for a
   capital-variable SCPI. This is the figure the balance may be built from — and it
   is what made the connector worth writing at all. Had it not been there, a sync
   could only ever fill quantities.
2. **No browser is needed.** Unlike CORUM there is no Cloudflare bot wall: the page
   is plain Apache-served PHP with a `PHPSESSID` cookie, and the whole portfolio
   table is in the initial response. This is the BoursoBank shape, not the CORUM one.
3. **A second factor is always required.** After the password, Sofidy emails a
   six-digit code and refuses the session until it is typed. The login is therefore
   a two-step handshake, like Amundi, not a single round trip like CORUM.

## What the spike corrected before any line was written

Two assumptions the reconnaissance made were wrong, and both would have produced a
connector that reads an empty portfolio while looking healthy:

- **The portfolio page is not `2,patrimoine.html`.** The site answers that path with
  "the page you want no longer exists". The real page is `3,clients.html` ("Mon
  Portefeuille"), reachable only from the connected menu. The list of `N,name.html`
  paths that answer 200 without a session is not a list of live pages: each returns the
  login form.
- **The fund row is not identified by its CSS class.** The served HTML spells it
  `line_marron` and the live DOM reports `line_maroon` back. Keying on either spelling
  reads a full portfolio as empty. The row is therefore selected on the product code it
  carries, which is the thing it is matched on downstream anyway.

The empty unit-value cell is a third shape that matters: a fund whose withdrawal price
Sofidy has not published yet still prints the `€` currency mark, so an empty-string
check reads it as a parse failure instead of a missing price. A cell with no digit in it
is the missing value, and `0.00` is a price.

## Decision

1. A dedicated internal-only FastAPI sidecar, `services/sofidy-auth`, on the
   browserless image BoursoBank already established. No Playwright, no Chromium: the
   page needs no JavaScript challenge, so plain HTTPS is enough and the image stays a
   tenth the size of the Playwright sidecars.
2. Credentials are ephemeral. Only the sidecar's opaque session blob — the cookie jar
   — is persisted, encrypted through `CryptoEncryption`. Neither the password nor the
   verification code is stored, and neither is ever logged.
3. The login is two steps. `POST /initiate` returns a pending process id and holds the
   httpx client; `POST /complete` submits the code and hands back the session. This is
   the Amundi shape, because a code that only exists in an inbox cannot be a
   single round trip.
4. The balance is always `quantity × withdrawalPrice`, written through
   `ScpiPositionService.applySyncedPosition` — the same method CORUM and the manual
   form use, so the withdrawal-price rule has one implementation.
5. A sync does not create accounts. A fund is matched to an account through
   `scpi_position.sofidy_fund_code`, Sofidy's own `Code_Produit` (`DY` for
   SOFIDYNAMIC). A holding with no linked account is skipped: account creation
   belongs to the manual flow.
6. A portfolio whose rows do not sum to the total Sofidy itself prints is refused
   wholesale, in the sidecar and again in the service. The last known-good balances
   are kept. A page carrying fund rows but no readable total row is refused as
   well: without that total nothing is left to catch a partial read, and reading
   it as "nothing to check" would pass a smaller portfolio as a complete one.
7. A complete snapshot is authoritative in both directions. A fund Sofidy no
   longer lists was sold, so its linked position goes to zero instead of keeping
   a balance the member no longer holds. This is what makes a full exit -- an
   empty portfolio, which Sofidy reports as complete -- syncable at all.
8. A fund row that cannot be matched — no product code, malformed figures — is
   refused rather than skipped. A silently dropped row understates a portfolio that
   still adds up.
9. Upstream I/O happens outside any transaction; the write is then one short
   transaction per position.

## Alternatives considered

### Drive the portal with a browser, as CORUM does

- **Pros**: one pattern across the SCPI connectors.
- **Cons**: Chromium in the image for a portal that has no bot wall. The browser is
  paid for on every run to solve a problem Sofidy does not have, and it multiplies
  memory use per pending second factor. The BoursoBank precedent already covers the
  browserless case, so the cost is a copy of an existing Dockerfile.

### The sync creates the accounts

- **Pros**: no manual linking step.
- **Cons**: a product code is not an account, and creating one silently bypasses the
  Immobilier flow a share is supposed to go through. It also makes a mis-read row
  create a phantom account.

### Match the fund on its marketing name

- **Pros**: no new column, and the name is what the user sees in the portal.
- **Cons**: names change, and a name is not a key. `Code_Produit` is the two-letter
  identifier the portal's own "Rapport détaillé" link carries, so it is already on the
  row we parse.

### Key the rows on their CSS class

- **Pros**: the shortest possible selector.
- **Cons**: the served HTML and the live DOM disagree on the spelling. This is the
  bug the spike caught before it was written, and a refonte would reintroduce it
  silently: a full portfolio would read as an empty one, and the empty portfolio is a
  legitimate state the parser accepts.

## Reasoning

Choosing the browserless sidecar is a direct consequence of the spike, not a
preference. The CORUM connector needed Chromium because Cloudflare fingerprinted the
client; Sofidy has no such wall, so the browser would be pure overhead. Writing the
parser against a live capture first also means the unit tests encode the real page's
shape, including the two traps above.

Failing closed is inherited from the SCPI model rather than invented. A SCPI position
is long-horizon money whose history matters more than intraday accuracy; a fund
silently missing from a sync would corrupt the net-worth series permanently, whereas
a refused sync costs a retry.

## Trade-offs accepted

- An unofficial integration needing maintenance whenever Sofidy ships a PHP change.
  The page paths and the row shape are the fragile parts, and both are asserted by
  `test_live_contract.py`, so a change surfaces as a failed contract test rather than
  as an empty portfolio.
- One more container.
- Public CI cannot prove the live login, so the sidecar's contract is tested against
  fixtures whose figures are invented. `test_live_portal.py` is the live check and is
  deliberately not mounted in CI: it costs a real login and a real verification e-mail.
- Multi-day session survival is unverified on a real account. A daily sync is the test,
  and an expired session deactivates itself and asks for a fresh login.
- The portal publishes no subscription price, so a synced position carries the
  withdrawal price and the quantity only. The subscription price stays whatever the
  manual entry recorded.

## Consequences

- New: `services/sofidy-auth/`, `SofidyPort` / `SofidyErrorCode` / `SofidyAdapter`,
  `SofidySyncService`, `SofidyController`, `SofidySession` / `SofidySyncStatus`,
  `SofidySessionRepository`, `SofidySyncConfig`, migration `V103`.
- `scpi_position` gains `sofidy_fund_code`, unique per member, and
  `ScpiPositionRequest` gains the matching field.
- `docker-compose.yml`, `docker/docker-compose.yml`, `.env.example` and the CI
  workflow gain the new sidecar.
- `SidecarSessionPanel` gains the four error codes only Sofidy can return
  (`MFA_INVALID`, `FIRST_VISIT_PENDING`, `EMAIL_UNREACHABLE`, `ACCOUNT_INACTIVE`)
  and the `RATE_LIMITED` mapping, so the panel can show what happened.