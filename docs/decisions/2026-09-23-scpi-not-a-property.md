# ADR: A SCPI share is not a property

> Date: 2026-09-23
> Status: ✅ Active

## Context

Picsou can estimate a house or an apartment from French open data. A SCPI share has no
address and no floor area. The same estimator cannot price it, and must not be pointed at it.

Holders also need two prices. The subscription price is what a new share costs. The
withdrawal price is what the fund would pay to buy it back. Entry fees sit between them.
Using the subscription price as net worth overstates it.

## Decision

`SCPI` is its own account type, listed in the Immobilier filter beside physical property,
not inside `PropertyKind`.

One account per vehicle. The balance is the withdrawal price times the share count, including
a fraction. The subscription price is displayed and never becomes the balance. A missing
withdrawal price keeps the previous balance and reports `PRICE_INCOMPLETE`.

`SCPI` is splittable, like a house or a loan. It is not an investment account: quantity is
not rebuilt from trades, and this version writes no holding, so Yahoo is not asked to quote
the ISIN.

The property summary reports shares as a separate paper total. They do not enter the
open-data gross, its gain, or its loan-to-value. A loan linked to the SCPI reduces the
paper net only.

Automatic sync with a management company's client area is out of scope here.

## Alternatives considered

### A `PropertyKind` value

- **Pros**: no new account type, the existing property form gains one option.
- **Cons**: the estimator, the address form and the square-metre fields all have to learn
  an exception. One missed branch values a share like an apartment.

### An `account_holding` on a brokerage account

- **Pros**: quantity and price columns already exist.
- **Cons**: `isInvestment()` rebuilds quantity from BUY/SELL, and the price job quotes every
  holding ticker. A SCPI ISIN is not a Yahoo symbol. The portal, later, is the source of
  the quantity — not the local journal.

## Reasoning

The type boundary is what keeps the estimator off the share. The two prices are what keeps
entry fees out of net worth. Both are visible in the data model, so a later sync can fill
the same row without a second migration.

## Trade-offs accepted

- No automatic quantity update in this change. A scheduled purchase or a reinvested dividend
  is entered by hand until a connector exists.
- No holding row, so the share does not appear in the brokerage holdings table. The account
  balance and the SCPI section are the figures.

## Consequences

- `V89` adds the enum value alone. `V90` creates `scpi_position`.
- `PUT /api/accounts/{id}/scpi` is the write path. `PropertyValuationService` still refuses
  anything that is not `REAL_ESTATE`.
