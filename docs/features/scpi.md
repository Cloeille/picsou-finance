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

The account balance is the withdrawal price times the share count. The subscription price
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

- PostgreSQL cannot use a new enum value in the transaction that added it. `V89` only adds
  `SCPI`. `V90` creates `scpi_position`.
- A generic account edit does not overwrite a SCPI balance. `ScpiPositionService` owns it.
- A linked loan on a SCPI reduces paper net, not the physical property's net.
- Automatic sync with a management company's client area is a later change. This note does
  not close that.

## Tests

- `ScpiPositionServiceTest` — fractional shares, withdrawal value, missing price, wrong type
- `RealEstateSummaryServiceTest` — a share stays out of the open-data gross

## Links

- Related ADR: [A SCPI share is not a property](../decisions/2026-09-23-scpi-not-a-property.md)
- Ticket: https://github.com/Cloeille/picsou-finance/issues/157
