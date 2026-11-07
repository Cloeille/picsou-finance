# ADR: A generic BROKERAGE type and the bank behind a SimpleFIN account

> Date: 2026-11-06
> Status: ✅ Active

## Context

SimpleFIN delivers every linked bank under one provider, so a Chase checking account and a Fidelity
Roth IRA looked alike: the subtitle said "SimpleFIN", no logo, and only a name prefix told them
apart. Brokerage accounts also arrived as a single `CHECKING` balance, because the sync ignored the
`holdings` array that Fidelity fills in (a stock, an index fund, a money-market sweep).
`AccountType` has no home for a US Roth IRA, HSA or 401(k): PEA, PER and COMPTE_TITRES are French
wrappers with French rules.

## Decision

- Add **one** `BROKERAGE` account type for an investment account at any broker, in any country and
  under any wrapper. It is an investment type, a wealth-pyramid equity tier and not a locked wrapper.
- Keep `provider = "SimpleFIN"` (Sync All and deletion find the connection through it) and add
  `account.institution_name` and `account.institution_domain`, filled from `connections[]`.
- Create a **new** account as `BROKERAGE` when the bridge reports at least one non-cash position for
  it. This is structural, not one of the heuristics the earlier ADR rejected: a cash account answers
  an empty list. A cash-management account whose only holding is a sweep fund stays `CHECKING`.
  An existing account is never retyped by a sync.
- Serve the bank logo through an opt-in proxy that reuses the merchant-logo gates.

## Consequences

- A new enum value needs its own migration (V133, `ALTER TYPE ... ADD VALUE`) and an entry in every
  exhaustive switch and in the frontend mirrors (`ACCOUNT_TYPES`, `HOLDING_ACCOUNT_TYPES`,
  `TYPE_TO_GROUP`).
- A member who wants a wrapper-specific type in future can retype the account; nothing here blocks a
  second, more specific type.
- A member whose SimpleFIN account was synced before this, and so is typed `CHECKING`, retypes it
  to Brokerage once to have its positions imported. Silently retyping it would have overridden a
  type the member may have chosen. Transactions imported before the retype keep their budget status
  (investment-account rows are budget-neutral from then on); they are not backfilled.
- Positions are imported only when the whole portfolio can be carried over and converted to EUR;
  otherwise the stored positions, cash and type are left alone (see `docs/features/simplefin-sync.md`).
- `AccountResponse` gains an `institution` component. The domain is never exposed to the browser.

## Alternatives considered

- One enum value per country or wrapper (ROTH_IRA, HSA, 401K...): an unbounded list, and the bank's
  own account name already says which wrapper it is.
- Reusing `COMPTE_TITRES`: it implies French tax treatment the account does not have.
- Bundled logos per bank: cannot cover the institutions the bridge adds.
