# Feature: SCPI shares

> Last updated: 2026-09-23

## Context

A SCPI share is paper property. It has no address and no floor area, so the open-data
estimator cannot price it. Folding it into `REAL_ESTATE` would either refuse it or, worse,
value it like a house.

## How it works

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

- PostgreSQL cannot use a new enum value in the transaction that added it. `V100` only adds
  `SCPI`. `V101` creates `scpi_position`.
- A generic account edit does not overwrite a SCPI balance. `ScpiPositionService` owns it.
- A linked loan on a SCPI reduces paper net, not the physical property's net.
- A CORUM contract holds several funds, so one client-space session writes to several
  accounts. They are matched by fund code, not by contract.
- Automatic sync with a management company's client area is a later change for every
  provider except CORUM. This note does not close that for the others.

## Tests

- `ScpiPositionServiceTest` — fractional shares, withdrawal value, missing price, wrong type
- `ScpiPositionServiceSyncTest` — a synced share is valued at the withdrawal price, not
  CORUM's displayed figure
- `RealEstateSummaryServiceTest` — a share stays out of the open-data gross

## Links

- Related ADR: [A SCPI share is not a property](../decisions/2026-09-23-scpi-not-a-property.md)
- Ticket: https://github.com/Cloeille/picsou-finance/issues/157
