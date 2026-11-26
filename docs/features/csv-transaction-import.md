# Feature: CSV transaction import (trades and bank/card statements)

> Last updated: 2026-11-25

## Context

Users want to seed a PEA/CTO (or crypto) account with their broker's trade history without
re-keying every line ([issue #38](https://github.com/Cloeille/picsou-finance/issues/38)). This
adds a two-phase CSV importer for **manual investment accounts**. It accepts any broker layout
because the user maps the columns. It writes manual BUY/SELL transactions and folds per-trade fees
into the cost basis.

Since [issue #671](https://github.com/Cloeille/picsou-finance/issues/671) the same wizard imports the
**cash lines of a bank or card statement** into every other account type (checking, card, savings,
SCPI, …), manual or synced. `LOAN` and `PEER_LOAN` are refused (400): their balance comes from the
amortization or the repayment ledger, never from a sum of lines. The account type picks the mode (`ImportMode`):
`TRADES` for `AccountType::is_investment()`, `CASH` for everything else.

## How it works

Two phases, modelled on the Finary XLSX importer but mapping **columns** into an
**already-selected account** (not mapping accounts):

1. **Preview** — the raw file is uploaded, the dialect (delimiter / decimal / date format) is
   sniffed, a best-guess column mapping is built from the header names, and the raw file is cached
   under a `fileToken` **bound to the target account** (30-min TTL). The response returns the
   detected columns, a sample of rows, and the guesses — all overridable in the wizard.
2. **Execute** — the client echoes the `fileToken` plus the (possibly user-adjusted) mapping and
   dialect. The raw file is **re-parsed** with the confirmed dialect, each row is mapped to a
   manual BUY/SELL transaction, valid rows are bulk-inserted (`is_manual = true`), and holdings are
   recomputed **once**. Invalid rows are reported per-row rather than failing the whole file.

### Key files

- `backend-rs/src/transfer/csv_import/` — `CsvReader` (RFC-4180, configurable delimiter),
  `CsvDialectDetector` (delimiter/decimal/date sniffing), `CsvValueParser` (`BigDecimal`/`LocalDate`
  parsing), `CsvDialect` + `DecimalStyle`. Dependency-free; symmetric with the GDPR `CsvWriter`.
- `backend-rs/src/transfer/csv_import/mod.rs` — `ImportMode` (`TRADES` or `CASH`, from the
  account type; the preview returns it and the wizard shows that mode's mapping fields), and one
  trade row → unsaved
  `Transaction`; reuses `InstrumentFieldResolver` (ISIN→ticker) and `TransactionAmountCalculator`
  (signed amount incl. fees).
- `backend-rs/src/transfer/csv_import/ledger_lines.rs` — one statement row → unsaved cash
  `Transaction` (`DEPOSIT`/`WITHDRAWAL`, signed amount, `is_manual = true`), the statement header
  guesses and the removal of lines the account already holds.
- `backend-rs/src/transfer/csv_import/mod.rs` — preview/execute + the
  `fileToken` cache and its `@Scheduled` TTL sweep.
- `backend-rs/src/transfer/csv_import/mod.rs` —
  `POST /api/accounts/{id}/transactions/import/preview` (multipart) and
  `POST /api/accounts/{id}/transactions/import` (JSON, `201`). Member-scoped + `syncBuckets` throttle.
- `frontend/src/components/shared/ImportTransactionsModal.tsx` — the 3-step wizard.
- `frontend/src/features/accounts/{api,hooks}.ts` — `importPreview` / `importExecute` + hooks.

### Flow

```
upload CSV ─► preview() ─► detect dialect + guess mapping ─► cache raw file @token(account)
                                                                     │
user adjusts mapping/dialect ◄───────────────────────────────────────┘
        │
        ▼
execute(token, mapping, dialect) ─► consume token (atomic) ─► re-parse ─► map rows ─► saveAll(is_manual) ─► recomputeHoldings()
```

Cash mode replaces the last two steps:

```
map rows ─► drop lines already held ─► autoCategorize ─► insert(is_manual) ─► manual ledger? compute_manual_ledger_balance + rebuild snapshots
```

### Cash-line mapping

- **Date** (required), **label** (optional, `"CSV import"` when blank), **currency** (optional,
  defaults to the account's).
- **Amount**: one signed column, or separate **debit** / **credit** columns. A debit is an outflow
  whatever sign the bank printed; when both are filled on one row the line is `credit − debit`.
  When a signed amount column is mapped it wins over debit/credit.
- **Invert sign** (`invertSign`): flips every amount, debit/credit result included, for card exports
  that print purchases as positive figures.
- **Currency** cell: kept only when it is an ISO 4217 code; a symbol (`€`) or a word falls back to
  the account's currency.
- An amount of 10^12 or more is a per-row error (`amount` is `NUMERIC(20,8)`).
- A zero amount is a per-row error, like a missing date.
- Header guesses cover French and English titles (`Libellé`, `Débit`, `Crédit`, `Montant`,
  `Description`, `Amount`, …). A detected debit or credit column clears the amount guess.

### Duplicate lines

Statements are exported monthly over overlapping ranges. Before saving, every mapped line is
compared with the account's transactions over the file's date range on **date + amount + label**
(label compared case- and spacing-insensitively). Matching is one for one: two identical lines in
the file against one already stored import one, so a genuine repeat (two coffees the same day)
is kept. The result reports them as `duplicates`, separate from the `skipped` errors.

### Balance

Shares `manual_transactions::recompute_derived_state` with the hand-typed lines:

- Manual cash ledger (`is_manual && is_ledger()`): `compute_manual_ledger_balance` (anchored on the
  latest hand-entered statement), then the derived snapshots are rebuilt.
- Synced account: lines are added, the provider balance is never touched.
- Valued types (SCPI, property, employee savings): lines are history, the valuation stays.
- A manual account with no hand-entered statement has its balance replaced by the sum of its lines,
  as with a hand-typed line (issue #543 behaviour). Enter a statement first when the file does not
  start at the account's opening.

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Hand-rolled `CsvReader` (no lib) | Matches the existing hand-rolled `CsvWriter`; the hard part (delimiter/decimal/date sniffing) isn't solved by a lib anyway | opencsv / Commons-CSV |
| Column-mapping wizard | Works for any broker export without a per-broker parser | Fixed template · per-broker native parsers |
| Cache the **raw file**, re-parse on execute | The user can change the delimiter after preview; caching parsed rows would be stale | Cache parsed rows |
| Token bound to the account | A preview cannot be replayed against another account | Bare token |
| Token consumed atomically **before** the write | Two executes racing on one preview (a double click, a client retry after a timeout) import once; a failed import hands the token back, since its transaction rolled back | Remove the token after `saveAll` (the window was the whole import, and the file went in twice) |
| Tolerant per-row errors | One bad line shouldn't sink a multi-year import | All-or-nothing transaction |

## Gotchas / Pitfalls

- **French broker exports** commonly use `;` delimiter and `,` decimals (`1 234,56`). The detector
  handles both; the user can override delimiter, decimal style, and date format in the wizard.
- **A date format may carry a time** (`yyyy-MM-dd HH:mm:ss`, fractions, AM/PM, an offset or a
  short zone), as the Java parser accepted: the row keeps the calendar date and the time is
  checked, then dropped.
- **ISINs must be resolved to tickers at import time** (`InstrumentFieldResolver`) or Yahoo pricing
  never populates and an ISIN row won't merge with the equivalent ticker row. The ISIN is kept as
  the instrument's identity (#703): it is recorded against the resolved ticker, and the line takes
  it, as for a manual entry ([manual-transactions.md](manual-transactions.md)).
- **Amount signing lives in one place** (`TransactionAmountCalculator`, mirrored in the TS modal):
  BUY `= −(qty·price + fees)`, SELL `= +(qty·price − fees)`. Fees also fold into the PMP — see
  [manual-transactions.md](manual-transactions.md).
- **Trades** are only accepted on **manual** investment accounts. Synced investment accounts are
  rejected before preview data is cached or transactions are saved, so a CSV cannot replace
  provider-owned positions. **Cash lines** are accepted on any other account, synced included,
  because they never rewrite a synced balance.
- A synced account that later syncs the same period can hold a line twice (once from the CSV, once
  from the provider): the duplicate check runs at import time only, and provider labels rarely
  match the CSV's word for word.
- Multipart limit was raised to **10 MB** (`application.yml`) for multi-year histories; the endpoint
  is member-scoped and throttled.
- Demo mode returns `{}` for unhandled endpoints — UI consumers must guard accordingly.

## Tests

- `CsvReaderTest`, `CsvDialectDetectorTest`, `CsvValueParserTest` — parsing / sniffing.
- `TransactionRowMapperTest` — sign+fees, ISIN resolution, amount-derived price, bad rows.
- `LedgerRowMapperTest` — signed amount, debit/credit, invert sign, blank label, bad rows.
- `TransactionImportServiceTest` — happy path, expired token, **token↔account binding**,
  synced investment account rejection (400), foreign account (404), per-row error reporting, two
  executes racing on one preview import once, a failed import hands the preview back. Cash mode:
  statement column guesses, every account type accepted, manual checking re-anchored, synced
  checking and SCPI balances untouched, overlapping statement skips held lines, a repeat inside one
  file is kept, invert sign on a card.
- `ImportTransactionsModal.test.tsx` — preview → mapping → import request → result; statement
  mapping (debit/credit, invert sign, duplicates count).

## Links

- Sibling importer: [finary-import.md](finary-import.md) · [trade-republic.md](trade-republic.md)
- [manual-transactions.md](manual-transactions.md) · [ISIN_TO_TICKER_CONVERSION.md](ISIN_TO_TICKER_CONVERSION.md)
- Tickets: [issue #38](https://github.com/Cloeille/picsou-finance/issues/38) (trades),
  [issue #671](https://github.com/Cloeille/picsou-finance/issues/671) (bank and card statements)
