# Feature: HomeBank iOS history import

> Last updated: 2026-10-04

## Context

Users migrating from the native **HomeBank iOS** app can bring their accounts, transaction history, payees and category labels into Picsou without entering them again. This implements the HomeBank portion of [issue #173](https://github.com/Cloeille/picsou-finance/issues/173), using the app's current `.hbk` export and password-protected `.hbexport` format rather than the issue's original desktop XML assumption.

**Actual Budget** and **GNU HomeBank desktop `.xhb`** are not included in this feature. HomeBank iOS can import the latter, but exports its own native compressed-JSON format.

## How it works

The HomeBank tab on the Sync page uses the same two-phase import pattern as the existing Finary and CSV importers:

1. **Preview:** upload a `.hbk` file, or a `.hbexport` file with its password. The server authenticates/decrypts when necessary, decompresses, parses and validates the complete source before caching an immutable normalized preview. No account, category or transaction is written during preview.
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
                                      └─ new-account balances + existing history helper
```

## Technical choices

- **Native iOS schema version 3:** the supported root is `{data, manifest}`. Source money is a decimal plus its currency; dates are `{year, month, day}` and become `LocalDate` directly. There is no floating-point conversion, sign inversion, exchange-rate conversion or timezone conversion.
- **Proven encrypted container version 1:** four-byte big-endian version, 32-byte salt, 12-byte nonce, ciphertext followed by a 16-byte authentication tag. The key is PBKDF2-HMAC-SHA256 with 600,000 iterations and 256 output bits; encryption is AES-256-GCM without additional authenticated data. The authenticated plaintext is raw DEFLATE, just like an unencrypted `.hbk`.
- **Fail closed:** unsupported schema/container versions, invalid IDs/references, duplicate JSON fields, invalid money/date values, corrupt data, invalid passwords and unsupported split rows are rejected before any persistence. The parser streams essential source arrays instead of materializing unused metadata. Uploads are limited to 10 MiB and inflated JSON to 50 MiB; source array, nesting, string and concurrent-decoding limits prevent memory amplification.
- **Reuse instead of replacement:** source account UUIDs identify HomeBank-created accounts; source transaction UUIDs identify rows independently of export date, compression or encryption. Importing again into the same target skips known IDs rather than deleting and recreating history. Moving an already-imported source row to a different target is rejected rather than duplicating it. A database member lock serializes imports from separate previews; atomic token consumption handles repeated confirmation of one preview.
- **Existing ownership remains intact:** mapping into an existing account adds transactions, but does not replace that account's provider identity, balance or balance snapshots. Currency mismatches and investment accounts are rejected: this importer carries cash-ledger history, not BUY/SELL positions.
- **Ledger-backed opening balance:** a newly created account's initial amount becomes a stable opening-balance row. This keeps the amount intact when subsequent manual edits recompute the ledger balance. Balance history uses the existing Finary reconstruction helper.
- **Transfers stay neutral:** original transfer legs are retained once each, without manufacturing another opposite leg. Their managed category is always of kind `TRANSFER`, regardless of the source category mapping. Opening balances also use `TRANSFER`, so neither inflates income or spending. Explicitly imported managed categories are marked as manual choices, protecting them from later automatic rules.

## Gotchas / Pitfalls

- HomeBank iOS and GNU HomeBank desktop are distinct sources. Do not parse `.hbk` as XML or mistake its raw DEFLATE stream for a ZIP archive or a zlib-wrapped stream.
- Category and payee information is repeated inside transaction objects. Resolve and validate the source IDs consistently; do not deduplicate transactions by mutable display names.
- Source forecast rows are excluded and counted in the preview. Scheduled transactions, envelopes/allocations, assignment rules and attachments are out of scope.
- Split rows are currently unsupported and reject the entire file rather than dropping part of a transaction.
- Notes become the transaction description; payees become the counterparty. If no note is present, the payee is the description fallback. Values that cannot fit the existing model must be rejected rather than silently truncated.
- Preview tokens expire after 30 minutes and belong to the current member. Restarting the app discards previews; a new upload is required.
- Token restoration must happen after an actual database rollback, including commit/flush failures. Restoring on a method-local exception alone is not enough.
- Create parent categories before children regardless of source array order. Archived or incompatible categories occupying a reserved source slug must trigger a clear mapping error, not an implicit unarchive or a database uniqueness failure.
- HomeBank permits a revenue subcategory under an expense parent (or vice versa), but Picsou requires parent and child to share the same kind. Such a child cannot be created under that source parent. Associate it explicitly with an existing Picsou category of its own kind instead; create that target in the budget settings and upload again if needed. Import does not flatten the hierarchy, clone the parent or alter revenue/expense meaning automatically. Leaving it uncategorized remains an explicit opt-out.
- Keep real financial exports outside the repository. Tests use synthetic schema-compatible files; opt-in real-file verification may print counts and equality results only.

## Tests

- Parser tests cover exact decimals/calendar dates, unencrypted and encrypted fixtures, passwords/authentication, versions, source references, duplicate IDs/keys, unsupported splits and size limits.
- Shared preview-store and CSV regression tests cover owner/TTL binding, atomic consumption and retry semantics.
- Import service/controller tests cover preview without writes, complete mapping validation, ownership/currency/type checks, repeated import, additive existing-account mapping, opening amounts and transfer categories.
- Wizard tests cover password upload/reset, preview/mapping/confirmation, result counters, double-submit prevention and error retry.
- `HomeBankRealFileSmokeTest` is opt-in using `-Dpicsou.homebank.fixtureDir=<private-directory>` and compares normalized clear/encrypted source data without logging personal values.
- `HomeBankImportPersistenceTest` is opt-in against an **isolated PostgreSQL database**. Set `PICSOU_HOMEBANK_TEST_JDBC_URL` and matching `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`; never point it at an existing user database. It runs the actual Flyway chain, parser, Spring-transactional service and repositories, then asserts an invalid mapping writes nothing, a real PostgreSQL constraint failure rolls back all writes, the same token succeeds after rollback, child-before-parent source categories retain their hierarchy, and a fresh preview creates no duplicate rows.
- `HomeBankPrivatePersistenceSmokeTest` additionally opts into the actual private exports with the same database variables, `picsou.homebank.fixtureDir` and `PICSOU_HOMEBANK_PASSWORD`. It rejects incompatible category-parent kinds without writes, then uses an explicit compatible existing-category mapping, verifies all account ledger balances and repeats the import using the encrypted counterpart without duplicates. Source data remains outside the repository and monetary assertion diagnostics are boolean-only.

For private clear/encrypted comparison, place the authorized exports in the private fixture directory under neutral names `homebank-clear.hbk` and `homebank-encrypted.hbexport`. `PICSOU_HOMEBANK_PASSWORD` is supplied through the environment, never checked into test fixtures. Without explicit opt-in, the private-file smoke and PostgreSQL persistence test are skipped; a skipped test is not proof of the corresponding layer.

## Links

- Ticket: [issue #173](https://github.com/Cloeille/picsou-finance/issues/173)
- Format/user documentation: [HomeBank iOS](https://homebank.app/docs)
- Existing importers: [Finary](finary-import.md), [CSV transaction import](csv-transaction-import.md)
- Ledger and budget semantics: [manual transactions](manual-transactions.md), [budget and cashflow](budget.md)
