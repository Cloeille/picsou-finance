# Feature: Account spreadsheet export (xlsx)

> Last updated: 2026-08-19

## Context

Picsou holds positions, cost bases and amortization schedules that a user may want to slice their
own way — a pivot table, a comparison against a broker statement, a model the app does not have.
The only export before this was the GDPR ZIP (`POST /api/me/export`): everything about the user,
flattened into CSV and JSON, gated behind a re-authentication. That answers the right-of-access
question, not "let me look at these four accounts in a spreadsheet".

This feature adds a targeted export from the accounts toolbar: pick accounts, get one `.xlsx` with
a sheet per account.

## How it works

### Flow

```
/accounts toolbar → "Exporter des données"
   │
   ▼
ExportAccountsModal
   ├─ every account listed, all ticked by default
   ├─ select all / clear, live counter
   └─ submit
   │
   │  POST /api/accounts/export
   │     { accountIds: [1,4,7], labels: { quantity: "Quantité", … } }
   ▼
AccountExportController
   ├─ Bucket4j 20/h keyed on userId          → 429 if exceeded
   ├─ log.warn("accounts_export.requested …")
   ├─ SheetLabels.of(labels)                  → client wording, English fallback per key
   └─ ResponseEntity<StreamingResponseBody>
            Content-Type: application/vnd.openxmlformats-…-spreadsheetml.sheet
            Content-Disposition: attachment; filename="picsou-comptes-<ts>.xlsx"
   │
   ▼
AccountsWorkbookService.export(ids, memberId, labels, out)
   ├─ per id: AccountService.findById(id, memberId)     ← member-scoped, raises on foreign id
   │          AccountService.getHoldings(id, memberId)
   │          PropertyValuationRepository (REAL_ESTATE only)
   │          DebtRepository.findByLinkedAccountId    (REAL_ESTATE only — its financing)
   │          DebtRepository + LoanAmortizationService.compute (LOAN only)
   ├─ once:   MemberProfileService.get(memberId)
   │          GoalService.findAll(memberId) + SavingsRateCalculator
   │          DebtRepository.findAllByMemberId        ← every loan, not just the selection
   ├─ SXSSFWorkbook(200-row window)
   ├─ summary sheet: profile, recurring plans, debt, then one row per exported account
   ├─ debt sheet (always, even empty)
   ├─ one sheet per account via AccountSheetWriter
   └─ finally { dispose(); close(); }
```

### What the summary sheet contains

Three blocks, in the order a reader needs them: **who**, **what goes out every month**, then
**what is held**.

| Block | Shown when | Contents |
|---|---|---|
| Profile | any field is stated | age, target retirement age, household, shares, dependents, gross annual income, monthly net before tax, derived monthly net, savings capacity, risk profile, and the two rates |
| Recurring investments | the member has at least one plan | monthly total, savings rate, then one row per plan (name, account, monthly amount, expected return, window) |
| Monthly position breakdown | at least one plan is detailed | one row per allocated line, plus the plan's unallocated remainder |
| Debt | **always** | total borrowed, outstanding, monthly payments, then one row per loan — or, with no debt, an explicit "none" and a zero |
| Accounts | always | one row per exported account |

A portfolio read without knowing the reader's age or bracket is a list of numbers; the profile is
what makes it a situation. **Every unstated field is omitted** — a label with an empty cell beside
it says less than no label at all — and a block whose fields are all unstated disappears entirely.

**The birth date is deliberately not written**, only the age. The age is what bears on a
portfolio; the date is personal data with nothing further to say, and an export travels.

**Savings targets are not in the plans block.** Its columns are a monthly amount and a split, and
a goal with a deadline has neither. They stay in the GDPR export's `goals.csv`.

The savings rate is `SavingsRateCalculator`, which mirrors the frontend's `plan-math.ts` — see
the gotcha below.

### The debt sheet, and why it exists when there is none

`Debts` is a fixed sheet, written whether or not the member has any. An absent sheet is
indistinguishable from a sheet nobody thought to add, and "does this person carry debt?" is a
question the workbook has to answer in both directions. With nothing to report it says so in
words and prints a zero outstanding.

It carries each loan's terms and the figures derived from them — instalments paid and total,
total interest and insurance cost — but **not the instalment rows**. Those are on the loan's own
account sheet; repeating a 25-year schedule would multiply the file for a table already in it.

**It covers every loan the member has, not only the exported accounts**, and says so on the
sheet. The alternative reports "no debt" for someone with a mortgage whose loan account they
happened not to tick — the exact misreading the block exists to prevent. It is the one place the
workbook deliberately steps outside the selection.

### What an account sheet contains

Every sheet opens with the account's identity — type, **opening date** where one is stated,
provider, currency, balance, balance in EUR, cash balance, ownership share, last sync, creation
date. The opening date sits directly under the type because for a PEA or an assurance-vie it is
half of what "what kind of wrapper is this" means: the taxation is a function of the plan's age.
It is written only when the member has stated it, and never derived from `createdAt`, which dates
the row rather than the plan. Then whichever of these the account
actually has; a passbook gets the header and stops there.

| Block | Shown when | Columns |
|---|---|---|
| Positions | the account has holdings | ticker, name, quantity, average cost, price, quote currency, value EUR, cost basis EUR, gain/loss EUR, gain/loss %, price as of, stale |
| Property | `type == REAL_ESTATE` | purchase price, agency/notary fees, works, cost basis, kind, category, address, area, rooms, energy class, rental income, valuation mode, last valued |
| Property financing | `type == REAL_ESTATE`, **always** | outstanding debt on the property — an explicit `0` when nothing finances it — then a row per loan |
| Valuation history | the property has `property_valuation` rows | date, estimate, low, high, price per m², source, confidence, sample size, source year |
| Loan | the account carries a `DebtResponse` | lender, borrowed amount, rate, monthly payment, insurance, file fees, dates, financed account, plus the summary figures |
| Amortization | the loan has a `Debt` row behind it | instalment no., date, capital, interest, insurance, payment, remaining balance |

**Transactions and balance-snapshot history are deliberately out of scope** for this iteration.
They live in the GDPR export's `transactions.csv` / `balance_snapshots.csv` for anyone who needs
them, and per-account transaction sheets would multiply the file size for a use case that had not
been asked for. Adding a block is a new method on `AccountSheetWriter` plus its label keys.

### Column headings come from the client

The request carries the localized wording; the backend has no message bundle. See the
[ADR](../decisions/2026-08-18-client-supplied-labels-for-xlsx-export.md) for why, and for the
trade-offs that come with it.

### Key files

**Backend**
- `service/SavingsRateCalculator.java` — monthly contributions and the rate, shared with nothing
  else yet
- `controller/AccountExportController.java` — `POST /api/accounts/export`, rate limit, audit log,
  streaming response
- `export/xlsx/AccountsWorkbookService.java` — gathers each account's data, opens the workbook,
  names the sheets
- `export/xlsx/AccountSheetWriter.java` — one account's sheet, block by block
- `export/xlsx/SheetCursor.java` — append-only write head; also where "typed cell, not string"
  is enforced
- `export/xlsx/WorkbookStyles.java` — the shared cell formats
- `export/xlsx/SheetLabels.java` / `LabelKey.java` — heading resolution and the wire contract
- `export/xlsx/AccountExportData.java` — one account's gathered data
- `export/xlsx/DebtExportData.java` — one loan and its amortization
- `dto/AccountsExportRequest.java` — `(List<Long> accountIds, Map<String,String> labels)`
- `config/RateLimitConfig.java` — `accountExportBuckets` bean + `createAccountExportBucket()`

**Frontend**
- `components/shared/ExportAccountsModal.tsx` — the picker
- `features/export/api.ts` — `exportApi.downloadAccountsXlsx`
- `features/export/hooks.ts` — `useExportAccountsXlsx`
- `features/export/labels.ts` — the key list sent to the backend
- `lib/download.ts` — `triggerBlobDownload` / `filenameFromDisposition`, shared with the GDPR export
- `components/ui/checkbox.tsx` — added for this (the app had only `switch`)
- `pages/accounts/AccountsPage.tsx` — the toolbar button

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Apache POI `SXSSFWorkbook` | Already a dependency (POI 5.4.0, used for parsing in `FinaryImportService`). Streaming keeps heap bounded when a 25-year loan schedule meets a large selection. | `XSSFWorkbook` — whole workbook in memory; or a new library for a dependency we already have |
| A new `export/xlsx` package, not the existing `EntityExporter` | `EntityExporter` is package-private, CSV/JSON-shaped and scoped to the whole user. This is per-account and cell-typed — implementing it would have meant widening an interface built for a different question. | Extending `EntityExporter` with an `xlsx` method |
| Rate limit but no re-auth | A subset the user picks from their own account list during ordinary use, not a full personal-data dump. A TOTP prompt on every export would make the feature unpleasant enough to go unused. | Reusing `ReAuthService` like `MeExportController` |
| Client-supplied headings | The frontend already owns a four-language catalogue of this exact vocabulary. | A Spring `MessageSource` bundle — see the ADR |
| A debt block and sheet even when empty | Silence reads as an omission; "owned outright" is a statement the workbook should be able to make | Rendering them only when a debt exists |
| Debt looked up member-wide | A property financed by an unticked loan would otherwise report zero debt | Scoping debt to the exported accounts |
| Derived loan figures on the debt sheet, not the schedule | The instalment rows are already on the loan's account sheet | Repeating 300 rows per loan |
| Profile and plans on the summary sheet | The context that turns a list of figures into a situation, and it belongs where the reader starts | A separate sheet per block |
| Unstated fields omitted entirely | A label with an empty cell reads as a gap in the data rather than a question never answered | Printing every field with blanks |
| Age exported, birth date not | The age is what bears on a portfolio; the date is PII that adds nothing, and an export travels | Writing the stored date |
| All accounts ticked on open | Exporting the lot is the common intent; unticking two beats ticking eight. | Empty selection |
| Export button hidden in demo mode | The demo adapter has no handler for the route, and an unhandled route resolves to `{}` — which would download a corrupt file rather than fail visibly. | Leaving it enabled |

## Gotchas / Pitfalls

- **`HoldingResponse.pnlPercent` is already multiplied by 100** (`AccountService.toHoldingResponse`).
  The cell uses the `#,##0.00" %"` format, **never** Excel's `0.00%`, which would rescale it a
  second time and report 3000 % on a 30 % gain. Same for `DebtResponse.interestRate`, which is the
  other way round: it is stored as a ratio (`0.0325`) and is multiplied by 100 on the way in,
  because the column says `(%)`.
- **Figures go in as numeric cells, dates as date cells.** The entire point is that the reader can
  sum and sort them. `SheetCursor` is the only place that writes cells, precisely so a string
  cannot slip in — a money value written with `setCellValue(String)` looks identical on screen and
  silently breaks every formula pointed at it.
- **`SXSSFWorkbook.dispose()` is not optional.** SXSSF spills rows past the access window to temp
  files; without `dispose()` in the `finally` they survive the request. `close()` alone does not
  remove them.
- **Duplicate sheet names make POI throw, they do not degrade.** Two accounts called "Livret A" is
  the ordinary case, so `uniqueSheetName` appends ` (2)`, ` (3)` — and *truncates the base* to make
  room, because the suffix must land inside Excel's 31-character cap rather than push past it.
  `WorkbookUtil.createSafeSheetName` handles the cap and the forbidden `: \ / ? * [ ]`; a name that
  sanitizes to nothing falls back to `Account <id>`.
- **A mid-stream failure cannot become a JSON 500.** Spring has flushed the 200 and the content
  headers before the first byte of the workbook exists, so `IOException` is logged and swallowed
  and the user gets a truncated file. Same constraint as [`data-export.md`](./data-export.md).
- **`Debt.interestRate` is a ratio, the column says (%).** `ratePercent` multiplies it on the way
  in; leaving it to Excel's percent format would rescale it a second time. Same trap as
  `pnlPercent`, which is already multiplied and must *not* be.
- **The two fixed sheets are in the dedup set.** An account genuinely called "Summary" or
  "Debts" used to reach `createSheet` with a name already taken, and POI throws on a duplicate
  rather than degrading. `used` is seeded with both; adding a third fixed sheet means seeding it
  too.
- **`outstanding` comes from the loan account's balance, not the schedule.** The two can disagree
  — one is derived from the terms, the other is what the bank last stated — and the balance is
  what the accounts table in the same workbook prints. One document, one number.
- **The savings rate is computed twice.** `SavingsRateCalculator` (backend, for this workbook) and
  `plan-math.ts` (frontend, for the Goals page) implement the same two rules: which plans are
  running on a date, and contributions over net monthly income. Both suites pin the same worked
  example — 400 + 200 over 3 000 is 20.0 % — so a change to one side fails a test next to the
  other. Any third rule added to either must be added to both.
- **The two rates go in through `fieldPercent`.** `marginalTaxRate` and `withholdingTaxRate` are
  already out of 100, exactly like `pnlPercent`; Excel's own percent format would rescale 30 to
  3000 %.
- **`LabelKey` and `features/export/labels.ts` must stay in step.** A new column added on one side
  only prints an English heading in a localized workbook — visible, but quiet. Both files say so.
- **Balances are the account's full value, not the viewer's share.** `sharePercent` is written
  beside them so a reader can weight a co-owned account themselves; nothing in the workbook
  pre-multiplies. Consistent with the account list (see
  [account-ownership-shares.md](account-ownership-shares.md)), and different from the dashboard's
  `RealEstateSummaryCard`, which is weighted server-side.
- **A `LOAN` account can have no `Debt` row.** It is typed in and never detailed; the sheet then
  shows whatever `DebtResponse` carried and no schedule, instead of failing the whole export.
- **The service is `@Transactional(readOnly = true)`** so the lazy associations behind the property
  and loan lookups resolve on one connection rather than one per account.

## Tests

Backend:
- `AccountsWorkbookServiceTest` — reads the produced bytes back with `XSSFWorkbook` and asserts:
  summary sheet first then one per account, duplicate names disambiguated, forbidden characters and
  over-31-character names sanitized, a blank name falling back to the id, duplicate ids collapsing
  to one sheet, positions rows with **numeric** cells and the `#,##0.00" %"` format on the
  percentage, no positions block without holdings, property cost basis and valuation history, a
  never-valued property keeping its metadata block, the loan rate written out of 100, the full
  schedule and nothing after it, a `LOAN` with no `Debt` row, and supplied labels replacing the
  English headings.
  Plus the summary blocks: the profile precedes the accounts and omits what is unstated, it
  disappears when nothing is stated, both rates are written out of 100, the plans block reports
  the total and the rate, the rate row is dropped with no stated income, the breakdown lists each
  line and states the remainder, and a savings target does not produce a plans block.
  And on debt: both the block and the sheet exist with no debt and state a zero, the block totals
  and lists every loan, the rate is written out of 100, the schedule-derived figures land on the
  debt sheet, an account named after a fixed sheet no longer collides, and a property sheet
  states its financing — or an explicit zero when nothing finances it.
- `SavingsRateCalculatorTest` — the running-plan window (including the start and end days
  themselves), savings targets ignored, the rate rounded to one decimal, and null rather than
  zero without a denominator.
- `SheetLabelsTest` — fallback per key, case/separator-insensitive matching, unknown keys ignored,
  control characters stripped, length capped, blank and null values falling back, and every
  `LabelKey` resolving to something non-blank.
- `AccountExportControllerTest` — the two response headers, the member id passed to the service,
  labels forwarded, the no-labels path still building a workbook, 429 once the quota is spent, the
  quota being per user, no service call when rate-limited, and a mid-stream `IOException` not
  propagating out of `writeTo`.

Frontend:
- `ExportAccountsModal.test.tsx` — accounts listed and sorted with all ticked, unticking one, the
  submitted payload's ids and labels, clear/restore, error toast, success closing the dialog, and
  the empty state.

## Links

- ADR: [Client-supplied labels for the xlsx export](../decisions/2026-08-18-client-supplied-labels-for-xlsx-export.md)
- Related: [`data-export.md`](./data-export.md) — the GDPR ZIP, and where transactions and balance
  history are available today
- Related: [`accounts-overview.md`](./accounts-overview.md) — the toolbar this button lives in
- Related: [`loans.md`](./loans.md) — the amortization schedule reused here
- Related: [`real-estate-valuation.md`](./real-estate-valuation.md) — the valuation history reused here
- i18n keys: `accounts.export.*`, `export.sheet.*`
