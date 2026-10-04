# Feature: SCPI shares

> Last updated: 2026-10-01

## Context

A SCPI share is paper property. It has no address and no floor area, so the open-data
estimator cannot price it. Folding it into `REAL_ESTATE` would either refuse it or, worse,
value it like a house.

## How it works

### Integration with 1.1.0 and V82

V82 already defines the PostgreSQL `SCPI` account type alongside `ASSURANCE_VIE`.
It also underpins the existing real-estate classification and wealth pyramid.
This feature extends that same type; it does not replace or recreate it. The
former main-based enum migration is redundant and is not replayed on 1.1.0.
V82 and all other migrations already present on the release branch remain unchanged.

The new `scpi_position` table stores the fractional quantity, the two prices and
the provider links. `ScpiPositionService` computes the existing account balance
from the withdrawal price instead of leaving it as a manually typed amount.
`SCPI` still does not enter `AccountType.isInvestment()`, while `ASSURANCE_VIE`
keeps its investment behaviour. Existing account metadata, translations and
wealth-allocation features from 1.1.0 are retained.

The consolidated change includes the manual slice originally reviewed in #158
and the two connectors in #159; it implements the manual requirements of #157
plus their read-only synchronization follow-up.

One Picsou account per vehicle, typed `SCPI`. The user enters the share count (fractional,
because a scheduled purchase or a reinvested dividend rarely lands on a whole share), the
subscription price and the withdrawal price.

The account balance is the withdrawal price times the share count, in euros. A SCPI
account is always manual and always `EUR`: the figure is not a foreign-currency cash
balance, and converting it again would double-count the exchange rate. Changing an
existing account into `SCPI` is refused while it still has holdings, because those
rows would keep being priced instead of the withdrawal value. The subscription price
is stored and shown beside that figure. It is never used as the balance: entry fees sit
between the two, and substituting one for the other would overstate net worth.

A missing withdrawal price leaves the previous balance alone and returns `PRICE_INCOMPLETE`.

`SCPI` is not an investment account. Quantity is not rebuilt from BUY/SELL. No holding row
is written in this version, so the hourly price job cannot send the ISIN to Yahoo.

The Immobilier filter lists `SCPI` next to physical property. The property summary reports
it as paper gross, outside the open-data gross and outside that gross's loan-to-value.

The wealth pyramid deducts both the physical-property debt and the SCPI debt
from the real-estate tier exactly once. Loan accounts themselves are skipped
there. Only physical-property debt enters the existing physical loan-to-value
indicator, so financing a SCPI does not change a house's LTV.

### Key files

- `AccountType.java` — `SCPI`, not included in `isInvestment()`
- `ScpiPositionService.java` — writes the withdrawal value
- `AccountController.java` — `PUT /api/accounts/{id}/scpi`
- `RealEstateSummaryService.java` — paper line, not DVF gross
- `frontend/src/components/scpi/AddScpiModal.tsx` — no address, no floor area

### CORUM sync

A CORUM client-space connection fills the share count and both prices of accounts
that are already linked to one of its funds. It writes through
`ScpiPositionService.applySyncedPosition`, the same method the manual form uses, so the
withdrawal-price rule has one implementation and a sync cannot value a share differently.

Picsou models one account per vehicle while a CORUM contract holds several funds, so a
fund is matched to its account by `scpi_position.corum_fund_code`, unique per member. A
fund with no linked account is skipped: creating accounts belongs to the manual flow. A
fund whose withdrawal price is missing still updates the share count but leaves the
balance alone and reports `PRICE_INCOMPLETE`, exactly as a manual entry would.

CORUM displays `quantity × subscription price`, which is not the withdrawal value. The
sync never uses that figure for a balance — see the
[CORUM sidecar ADR](../decisions/2026-09-26-corum-scpi-sidecar.md).

### Sofidy sync

A Sofidy Espace Associé connection fills the share count and the withdrawal price of
accounts already linked to one of its funds, through the same
`ScpiPositionService.applySyncedPosition` the manual form and the CORUM sync use.

The login is two steps, not one: Sofidy always emails a six-digit code and refuses the
session until it is typed, so `/api/sofidy/auth/initiate` returns a process id and
`/api/sofidy/auth/complete` opens the session. Neither the password nor the code is
persisted — only the sidecar's cookie jar, encrypted.

Funds are matched by `scpi_position.sofidy_fund_code`, Sofidy's own `Code_Produit`
(`DY` for SOFIDYNAMIC), unique per member, with the same two rules as CORUM: a fund with
no linked account is skipped, and a fund whose withdrawal price is missing still updates
the share count while leaving the balance alone and reporting `PRICE_INCOMPLETE`.

Sofidy publishes no subscription price, so a synced position carries the quantity and
the withdrawal price only; the subscription price stays whatever the manual entry
recorded. See the
[Sofidy sidecar ADR](../decisions/2026-09-27-sofidy-scpi-sidecar.md).

### Flow

```
PUT /api/accounts/{id}/scpi
  └─ withdrawal price present?
       ├─ yes → balance = withdrawal price × share count, status OK
       └─ no  → previous balance kept, status PRICE_INCOMPLETE
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Own account type | A share is not a house | `PropertyKind.SCPI`, which the estimator would have to special-case forever |
| Withdrawal price as the balance | That is what could be sold | Subscription price, which includes entry fees |
| No `account_holding` yet | The price job quotes every holding ticker | A holding keyed by ISIN, which Yahoo would then be asked to price |

## Gotchas / Pitfalls

- `CORUM_AUTH_URL` and `SOFIDY_AUTH_URL` are bound in both backend configurations.
  Development defaults use loopback ports 8006 and 8007, avoiding BoursoBank's
  8004 and Fortuneo's 8005. Compose uses the internal service names on port 8001;
  no new sidecar port is published to the host.
- The backend, CORUM and Sofidy share the `APP_SIDECAR_API_KEY` every
  `*-auth` sidecar requires. See
  [docker-deployment.md](./docker-deployment.md#sidecar-shared-secret--app_sidecar_api_key)
  for the contract: startup refusal, the `/health` exemption, and the 401
  challenge that separates a key mismatch from a provider login or session
  failure.
- `V82` already adds `SCPI` and `ASSURANCE_VIE` on 1.1.0; do not add the enum twice.
  The appended migrations are `V104` (`scpi_position`), `V105` (`corum_session`)
  and `V106` (`sofidy_session`), above the base branch's `V103`.
- A generic account edit does not overwrite a SCPI balance. `ScpiPositionService` owns it.
- A linked loan on a SCPI reduces paper net, not the physical property's net.
- A CORUM contract holds several funds, so one client-space session writes to several
  accounts. They are matched by fund code, not by contract.
- A Sofidy fund row carries no subscription price, so `subscription_price_eur` keeps
  whatever the manual entry recorded. The portal has no such column.
- A Sofidy login is always two steps. A single-call flow would show the user a
  connected panel while the portal was still waiting on a verification code.
- The empty unit-value cell in the Sofidy portfolio prints its `€` mark, so an
  empty-string check reads a missing price as a parse failure.
- A positive, negative or unreadable portfolio total with missing lines is refused,
  not stored as an empty portfolio. CORUM also refuses a fund whose displayed value
  is missing, and the Java sync repeats the envelope check so a stale sidecar cannot
  skip it. A Sofidy row with too few cells is a format change, not a row to skip.

## Tests

- `ScpiPositionServiceTest` — fractional shares, withdrawal value, missing price, wrong type
- `ScpiPositionServiceSyncTest` — a synced share is valued at the withdrawal price, not
  CORUM's displayed figure
- `RealEstateSummaryServiceTest` — a share stays out of the open-data gross
- `SofidyAdapterTest` — the two-step login, a rejected verification code, a fund
  without a withdrawal price, a complete empty portfolio, an untrusted flag
- `services/sofidy-auth/test_positions_parser.py` — the real page shape, a row with
  no product code refused, a missing unit value, a total that does not reconcile,
  a truncated row refused rather than read as an empty portfolio
- `services/corum-auth/test_positions_parser.py` — a missing displayed value, a
  negative envelope, and a positive total with no lines are refused
- `CorumSyncServiceTest` — a stale sidecar whose displayed values miss the envelope
  writes nothing; entry fees do not fail the envelope check
- `ImpersonationControllerTest` — an activated member cannot be impersonated, a
  managed-member override is the id passed downstream, a co-owner can read ownership
  but cannot replace it
- `services/sofidy-auth/test_live_contract.py` — the routes, the 2FA handshake, and
  the codes the Java adapter depends on
- `services/corum-auth/test_sidecar_auth.py` and
  `services/sofidy-auth/test_sidecar_auth.py` — missing, empty and wrong keys,
  authenticated dispatch, public health and fail-closed configuration

## Links

- Related ADR: [A SCPI share is not a property](../decisions/2026-09-23-scpi-not-a-property.md)
- Related ADR: [CORUM client space fills existing SCPI accounts through a browser sidecar](../decisions/2026-09-26-corum-scpi-sidecar.md)
- Related ADR: [Sofidy Espace Associé fills existing SCPI accounts through a browserless sidecar](../decisions/2026-09-27-sofidy-scpi-sidecar.md)
- Ticket: https://github.com/Cloeille/picsou-finance/issues/157
