# ADR: Key security lookups on the ISIN, and read fund facts from justETF

> Date: 2026-08-15
> Status: ✅ Active

## Context

The ETF look-through was not working. On a real portfolio most funds resolved to nothing, stored
no slices, and surfaced as "we looked, classify it by hand" — advice that is worse than useless,
because a hand-made override permanently masks whatever the provider publishes later.

The cause was not the source. **Boursorama's search resolves an ISIN**, and to the same symbol as
the ticker:

```text
/recherche/?query=LU1681043599  ->  /cours/1rTCW8/
/recherche/?query=CW8           ->  /cours/1rTCW8/
/recherche/?query=IE00B4L5Y983  ->  /cours/1rAIWDA/
```

What reached the provider was whatever `OpenFigiIsinConverter.pickBest` had chosen, and it
deliberately prefers **US OTC listings for non-US ISINs** — precisely the tickers a French retail
search box has never heard of. Meanwhile the ISIN that would have worked was discarded at
ingestion: every connector reports one, every sync converts it and drops it, and no table stored
it. The conversion and the lookup it feeds were working against each other.

Separately, nothing in the app could answer what a fund **costs**, or whether it accumulates or
distributes — both needed for a later fee scanner, and both free at fetch time if collected while
the composition is being read anyway.

## Decision

**1. Persist the ISIN, on `security_profile`.**

Not on `account_holding`: every sync deletes an account's holdings and re-inserts them, so a
column there survives only until the next run — the same reasoning that kept
`holding_classification` off that table. `security_profile` is already global and already keyed on
the ticker every reader uses.

Deliberately **not `UNIQUE`**: OpenFIGI can map two tickers onto one ISIN, and a constraint
violation would fail an entire sync over reference data nobody asked for.

`refreshed_at` becomes nullable, which is load-bearing rather than tidying. A sync now seeds rows
carrying an ISIN and nothing else, and `NULL` is exactly what `refreshStale` reads as "due". A
sentinel timestamp would have read as freshly resolved, and the seeded row would never have been
looked up at all.

**2. Ask every source by ISIN when one is known.** One line in `BoursoramaCompositionProvider`,
and the fix for most of the missing look-through.

**3. Read fund facts and a fallback breakdown from justETF, second in line.**

## Why not Yahoo `quoteSummary`

It is the generic answer and it does not work. Measured, 2026-08-15:

| Call | Result |
|---|---|
| `/v10/finance/quoteSummary/CW8.PA?modules=topHoldings,fundProfile` | **HTTP 401 `Invalid Crumb`** |
| `/v1/test/getcrumb` after a cookie handshake | returns the literal string **`Too Many Requests`**, repeatedly |

Recorded here so the option is not re-proposed from memory. Both earlier ETF ADRs had already
rejected it on other grounds.

## Why Boursorama stays first

| | Boursorama | justETF |
|---|---|---|
| Slices per axis | up to 10, no residual | top 4 + an explicit `Other` |
| Fee, distribution policy | — | ✅ |
| Keyed on | ticker **or** ISIN | ISIN only |

Ordering justETF first would **lower every sector score** without adding truth. The two merge
field by field — the breakdown taken whole from the first provider that has one, the fee taken
independently — because no single source has both and first-wins would silently discard the fee.
That is the same merge `SecurityProfileService.resolveEquity` already performs for equities.

Taking the breakdown *whole* rather than per axis is deliberate: `EtfComposition` carries one
`asOf`, and a Boursorama sector split beside a justETF country split would put two reference dates
behind one number.

## Consequences

- **An unknown ISIN does not 404.** justETF answers `200` with its ETF screener — the same trap
  Boursorama sets. The parser refuses any page not carrying the requested ISIN in its header node,
  and a fixture holds the screener response so the guard is tested rather than assumed.
- **Parsing keys on `data-testid`,** not the English labels beside them: the labels move with the
  URL's locale, the testids do not. Verified across a synthetic Luxembourg fund and a physical
  Irish one, so the basics table is not read positionally.
- **Unmapped sector labels are normalised, not passed through** — unlike `BoursoramaLabels`. A raw
  `Utilities` would otherwise become a bucket distinct from `utilities`, showing one sector twice
  and scoring a portfolio as more diversified than it is.
- The ISIN fills in **on the next sync per connector**. There is no backfill: every sync rebuilds
  its holdings, so the table populates within one cycle, and a boot-time repair runner would fight
  OpenFIGI's rate limit for data the next sync produces free.
- Amundi employee-savings plans needed no change at all: their FCPEs have no exchange quote, so
  the sync already stores the ISIN *as* the ticker, and `isinOf` recognises that outright.

## Links

- [`docs/features/portfolio-diversification.md`](../features/portfolio-diversification.md)
- [ADR: persisted security profiles](./2026-08-13-persisted-security-profiles.md)
- [ADR: domicile vs exposure](./2026-08-13-equity-domicile-vs-etf-exposure.md)
