# Feature: HomeBank iOS and desktop history import

> Last updated: 2026-10-07

## Context

Users migrating from the native **HomeBank iOS** app can bring their accounts, transaction history, payees and category labels into Picsou without entering them again. This implements the HomeBank portion of [issue #173](https://github.com/Cloeille/picsou-finance/issues/173), using the app's current `.hbk` export and password-protected `.hbexport` format rather than the issue's original desktop XML assumption.

GNU HomeBank desktop **`.qif` exports** also use this wizard. Desktop `.xhb` XML remains unsupported. Actual Budget has its own separate import tab and endpoint.

This covers the full HomeBank export scope requested in issue #173: native iOS `.hbk`/`.hbexport` and GNU desktop QIF. QIF is the desktop export available to the requesting user; support for its native `.xhb` data file is not required for this export-import workflow.

## How it works

The HomeBank tab on the Sync page uses the same two-phase import pattern as the existing Finary and CSV importers:

1. **Preview:** upload a `.hbk` file, a `.hbexport` file with its password, or a desktop `.qif` file with an explicitly selected currency. The server decodes and validates the complete source before caching an immutable normalized preview. No account, category or transaction is written during preview.
2. **Mapping:** choose a new Picsou cash-ledger account (with name and type), an existing account in the same currency, or skip each source account. Map source categories to existing budget categories, create them, or keep them uncategorized. Inspect a sample of the exact signed amounts, currencies, calendar dates, payees and notes.
3. **Confirmation:** commit the reviewed mappings using a member-bound preview token. The server validates all mappings, consumes the preview once and applies the import in one database transaction.

The password is used only to decode the upload. It is not put in the preview cache, persisted, included in the JSON confirmation request or logged. The browser clears its password field after a successful preview.

### Key files

- `backend/src/main/java/com/picsou/imports/homebank/` — immutable normalized source model and dependency-free file decoding using Jackson and JDK cryptography/compression.
- `backend/src/main/java/com/picsou/imports/ImportPreviewStore.java` — shared bounded preview storage, owner binding, expiration and atomic consumption; also used by CSV import.
- `backend/src/main/java/com/picsou/service/HomeBankImportService.java` — preview, mapping validation, account/category reuse, additive ledger import and history reconstruction.
- `backend/src/main/java/com/picsou/controller/HomeBankImportController.java` — member-scoped, IP-throttled multipart preview and JSON confirmation endpoints.
- `backend/src/main/java/com/picsou/dto/HomeBankImportDtos.java` — typed preview, mappings and result counters.
- `frontend/src/features/homebank/` — API calls, mutations and frontend contract.
- `frontend/src/pages/sync/HomeBankTab.tsx` — upload, mapping, confirmation and result UI.

### Flow

```text
.hbk raw DEFLATE ───────────────────────────────┐
                                               ├─► validate schema/IDs/money/dates
.hbexport ─► PBKDF2 ─► authenticated AES-GCM ────┘       │
                         └─► raw DEFLATE                ▼
                                               member-bound preview token
                                                       │
                                            account + category mapping
                                                       │
                                                       ▼
                                      validate all targets / consume token
                                                       │
                                                       ▼
                                       member DB lock / one atomic transaction
                                      ├─ reuse/create accounts and categories
                                      ├─ stable-ID ledger insert (no deletes)
                                      └─ balances of manual targets that received rows + existing history helper
```

## Technical choices

### GNU HomeBank desktop QIF

- **Currency is explicit:** QIF does not carry account currencies. The multipart `currency` field must be a supported ISO 4217 code for `.qif`; there is no silent EUR default. All accounts in one export use the selected currency. Export different-currency accounts separately. The browser validates real currency codes through `Intl.supportedValuesOf`; older browsers use Picsou's curated account-currency list, so a valid code outside that fallback list requires a modern browser.
- **Exact cash ledger:** QIF `Bank`, `Cash` and `CCard` accounts and calendar dates are parsed without floating-point or timezone conversions. One date format is detected for the whole file: `yyyy/MM/dd`, `dd/MM/yyyy` or `MM/dd/yyyy` (`/`, `-` or `.` separators). A file whose dates read differently as day-first and month-first is rejected as ambiguous; two-digit years are not supported. Transfer labels `[Account]` resolve to accounts in the same file; each exported leg is kept once, without creating a mirror leg. A transfer to an account absent from the file is imported as an uncategorized plain row, its target name kept in the notes.
- **Splits are preserved:** each `S`/`E`/`$` part becomes its own category-bearing ledger row. Parent `M` and split `E` notes are both retained; combined notes exceeding 255 characters reject the export instead of being truncated. The parts must sum exactly to the parent `T` amount; the parent is not inserted as an extra transaction. Preview/import counters count normalized ledger rows, not QIF parent records.
- **No invented opening amount:** QIF account definitions do not supply an opening balance. New accounts start at zero and are reconstructed from the exported rows. Use a complete history and inspect the preview balances; this is not a balance snapshot migration.
- **No native transaction IDs:** deterministic, desktop-namespaced source IDs use account/content identity and an occurrence discriminator. Repeating unchanged rows skips them, while genuinely identical repeated rows remain distinct. Clearing-status changes and unrelated row insertions do not alter the IDs. Editing identifying content in HomeBank produces a new row on later additive import; renaming accounts changes their source identity. QIF cannot provide iOS UUID-level edit tracking. Review before confirming; imports do not delete or reconcile earlier rows.
- **Category hierarchy:** colon-separated labels become parent/child categories. Explicit QIF category income/expense flags take precedence and stay strict. Without flags, a category with any negative row is treated as expense (including its refunds); positive-only categories are income. Such sign-inferred kinds are not authoritative: they may map onto an existing income or expense category, a category created by an earlier import keeps its existing kind when a later export flips the sign mix, and a new inferred sub-category adopts its parent's kind. Verify category kinds in the preview, especially for partial-history exports.
- **Fail closed:** corrupt UTF-8, unsupported investment sections, invalid/ambiguous dates, invalid decimals, incomplete records and inconsistent splits reject the complete export. Lines are read incrementally with limits of 500,000 lines and 256 characters per line; categories and normalized split rows are capped incrementally. The iOS split restriction below applies only to native iOS JSON, not supported QIF splits.

### Native HomeBank iOS

- **Native iOS schema version 3:** the supported root is `{data, manifest}`. Source money is a decimal plus its currency; dates are `{year, month, day}` and become `LocalDate` directly. There is no floating-point conversion, sign inversion, exchange-rate conversion or timezone conversion.
- **Proven encrypted container version 1:** four-byte big-endian version, 32-byte salt, 12-byte nonce, ciphertext followed by a 16-byte authentication tag. The key is PBKDF2-HMAC-SHA256 with 600,000 iterations and 256 output bits; encryption is AES-256-GCM without additional authenticated data. The authenticated plaintext is raw DEFLATE, just like an unencrypted `.hbk`.
- **Fail closed:** unsupported schema/container versions, invalid IDs/references, duplicate JSON fields, invalid money/date values, corrupt data, invalid passwords and unsupported split rows are rejected before any persistence. The parser streams essential source arrays instead of materializing unused metadata. Uploads are limited to 10 MiB and inflated JSON to 50 MiB; source array, nesting, string and concurrent-decoding limits prevent memory amplification.
- **Reuse instead of replacement:** source account UUIDs identify HomeBank-created accounts; source transaction UUIDs identify rows independently of export date, compression or encryption. Importing again into the same target skips known IDs rather than deleting and recreating history. Moving an already-imported source row to a different target is rejected rather than duplicating it. A database member lock serializes imports from separate previews; atomic token consumption handles repeated confirmation of one preview.
- **Existing ownership remains intact:** mapping into an existing account adds transactions without replacing its provider identity. A manual target that received at least one new row gets its balance and snapshots rebuilt from the ledger, as a manual edit would; synced (non-manual) targets keep their provider balance and snapshots. Currency mismatches and investment accounts are rejected: this importer carries cash-ledger history, not BUY/SELL positions.
- **Ledger-backed opening balance:** a non-zero source initial amount becomes a stable opening-balance row for a newly created or mapped existing manual account; a zero amount (always the case for QIF) creates none. Re-importing the same source opening into the same target skips it. Existing ledger rows remain additive; mapping is not a replacement of an existing account's history. Synced targets receive no opening row and keep their provider balance and snapshots. This keeps the amount intact when subsequent manual edits recompute the ledger balance. Balance history uses the existing Finary reconstruction helper.
- **Transfers stay neutral:** original transfer legs are retained once each, without manufacturing another opposite leg. Their managed category is always of kind `TRANSFER`, regardless of the source category mapping. Opening balances also use `TRANSFER`, so neither inflates income or spending. Explicitly imported managed categories are marked as manual choices, protecting them from later automatic rules. Rows left uncategorized are not marked manual, so categorization rules can still apply to them.
- **Manual balance semantics:** recomputation uses existing ledger rows plus the source opening and imported rows, not the previously typed `currentBalance`. A balance entered when creating an account without any ledger rows is replaced by this ledger-derived amount, consistently with manual transaction edits. It is not added as a second opening: doing that would double-count the same opening when migrating HomeBank history. For example, a source opening of 1,000 and a new transaction of +250 produce 1,250 regardless of a previously typed balance; existing persisted transactions remain additive.

## Gotchas / Pitfalls

- HomeBank iOS and GNU HomeBank desktop are distinct sources. Do not parse `.hbk` as XML or mistake its raw DEFLATE stream for a ZIP archive or a zlib-wrapped stream.
- QIF account identity is based on the account name, not the originating HomeBank file. Two independent files with the same account name share a source identity: mapping them to the same Picsou account merges their history, and identical rows can be treated as already imported. Import unrelated files into separate target accounts; explicit file identity remains a follow-up design decision.
- Ambiguous day-first/month-first QIF dates are rejected; the wizard currently has no date-format override. Export unambiguous year-first dates when possible.
- Category and payee information is repeated inside transaction objects. Resolve and validate the source IDs consistently; do not deduplicate transactions by mutable display names.
- Source forecast rows are excluded and counted in the preview. Scheduled transactions, envelopes/allocations, assignment rules and attachments are out of scope.
- Native iOS JSON split rows are unsupported and reject the entire file rather than dropping part of a transaction. Desktop QIF splits are supported as described above.
- Notes become the transaction description; payees become the counterparty. If no note is present, the payee is the description fallback. Values that cannot fit the existing model must be rejected rather than silently truncated.
- Preview tokens expire after 30 minutes and belong to the current member. Restarting the app discards previews; a new upload is required. The shared store keeps at most 3 HomeBank previews per member and 8 overall (CSV: 4 and 32): a new preview evicts the member's oldest one, then the oldest unconsumed preview of anyone. Previews held by a running import are never evicted; if only those remain, the preview request returns 429.
- Another member's upload can evict a preview while it is being mapped. Confirmation then fails without importing data; upload again to obtain a fresh preview.
- Token restoration must happen after an actual database rollback, including commit/flush failures. Restoring on a method-local exception alone is not enough.
- Create parent categories before children regardless of source array order. Archived or incompatible categories occupying a reserved source slug must trigger a clear mapping error, not an implicit unarchive or a database uniqueness failure.
- HomeBank permits a revenue subcategory under an expense parent (or vice versa), but Picsou requires parent and child to share the same kind. Such a child cannot be created under that source parent. Associate it explicitly with an existing Picsou category of its own kind instead; create that target in the budget settings and upload again if needed. Import does not flatten the hierarchy, clone the parent or alter revenue/expense meaning automatically. Leaving it uncategorized remains an explicit opt-out.
- Keep real financial exports outside the repository. Tests use synthetic schema-compatible files; opt-in real-file verification may print counts and equality results only.

## Tests

- Parser tests cover exact decimals/calendar dates, unencrypted and encrypted fixtures, passwords/authentication, versions, source references, duplicate IDs/keys, unsupported splits and size limits.
- Shared preview-store and CSV regression tests cover owner/TTL binding, per-member and global eviction, atomic consumption and retry semantics.
- Import service/controller tests cover preview without writes, complete mapping validation, ownership/currency/type checks, repeated import, additive existing-account mapping, opening amounts and transfer categories.
- Wizard tests cover password upload/reset, preview/mapping/confirmation, result counters, double-submit prevention and error retry.
- Desktop QIF parser tests cover multi-account sections, exact money/dates, transfers, category hierarchy, splits, stable IDs and rejection cases. The wizard and API tests verify explicit currency is sent only for QIF.
- `HomeBankQifPersistenceTest` runs against a Testcontainers PostgreSQL 16 in CI. It verifies split/transfer ledger sums, complete rollback with token retry and repeated-import deduplication. `HomeBankQifPrivateSmokeTest` stays opt-in: `-Dpicsou.homebank.qifFile=<private-file>` enables the authorized desktop export check; only counts and boolean equality results may be printed.
- `HomeBankRealFileSmokeTest` is opt-in using `-Dpicsou.homebank.fixtureDir=<private-directory>` and compares normalized clear/encrypted source data without logging personal values.
- `HomeBankImportPersistenceTest` runs against a disposable Testcontainers PostgreSQL 16 (skipped locally without Docker; CI requires it and checks the surefire report). It runs the actual Flyway chain, parser, Spring-transactional service and repositories, then asserts an invalid mapping writes nothing, a real PostgreSQL constraint failure rolls back all writes, the same token succeeds after rollback, child-before-parent source categories retain their hierarchy, and a fresh preview creates no duplicate rows.
- `HomeBankPrivatePersistenceSmokeTest` additionally opts into the actual private exports with the same database variables, `picsou.homebank.fixtureDir` and `PICSOU_HOMEBANK_PASSWORD`. It rejects incompatible category-parent kinds without writes, then uses an explicit compatible existing-category mapping, verifies all account ledger balances and repeats the import using the encrypted counterpart without duplicates. Source data remains outside the repository and monetary assertion diagnostics are boolean-only.

For private clear/encrypted comparison, place the authorized exports in the private fixture directory under neutral names `homebank-clear.hbk` and `homebank-encrypted.hbexport`. `PICSOU_HOMEBANK_PASSWORD` is supplied through the environment, never checked into test fixtures. Without explicit opt-in, the private-file smoke and PostgreSQL persistence test are skipped; a skipped test is not proof of the corresponding layer.

## Links

- Ticket: [issue #173](https://github.com/Cloeille/picsou-finance/issues/173)
- Format/user documentation: [HomeBank iOS](https://homebank.app/docs)
- Existing importers: [Finary](finary-import.md), [CSV transaction import](csv-transaction-import.md)
- Ledger and budget semantics: [manual transactions](manual-transactions.md), [budget and cashflow](budget.md)
