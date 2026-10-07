# ADR: The client supplies the column labels for the account spreadsheet export

> Date: 2026-08-18
> Status: ✅ Active

## Context

`POST /api/accounts/export` builds an `.xlsx` workbook — one sheet per selected account, with
positions, property detail and loan amortization on it — so a user can analyse their own figures
outside Picsou. Every one of those sheets is mostly column and section headings: about eighty
distinct strings.

The UI is available in four languages (FR default, EN, DE, ES). The backend has **no i18n
mechanism at all**: no `messages.properties`, no `MessageSource`, no `LocaleResolver`. The only
precedent is the GDPR export, whose `csv/` column names are hardcoded English identifiers
(`average_buy_in`, `valued_at`) — machine-readable field names rather than headings a person
reads, so they never raised the question.

A French user asked for a French workbook. Something has to decide the wording.

## Decision

The **frontend sends the headings with the request**, keyed by the backend's `LabelKey` enum:

```json
POST /api/accounts/export
{
  "accountIds": [1, 4, 7],
  "labels": { "quantity": "Quantité", "averageBuyIn": "Prix de revient moyen" }
}
```

`SheetLabels.of(labels)` resolves each `LabelKey` from the supplied map and falls back to
`LabelKey.englishDefault()` for anything missing. `labels` may be omitted entirely.

## Alternatives considered

### Add a Spring `MessageSource` bundle and resolve from `Accept-Language`

- **Pros**: the idiomatic Spring answer; no dependency; the endpoint is fully self-sufficient;
  reusable by any future server-rendered content.
- **Cons**: introduces a second, parallel translation catalogue for strings that already exist in
  `frontend/src/i18n/locales/*.json`. Four files to keep in step with four others, with nothing
  enforcing that they agree — and the failure mode is a French UI producing a half-German
  workbook. It is infrastructure whose only consumer today is one export.

### Keep the headings in English

- **Pros**: zero work; consistent with the GDPR CSV columns.
- **Cons**: does not answer the request. The GDPR CSVs are field dumps; this workbook is a
  document a person opens and reads.

## Reasoning

The frontend already owns a complete, reviewed, four-language catalogue of exactly this
vocabulary — "Quantité", "Prix de revient", "Tableau d'amortissement" all already appear in the
UI. Sending it costs one field on a request that is already a POST, and it guarantees the workbook
matches the language the user is looking at, including any language added later, with no
server-side change.

A `MessageSource` bundle is the better answer the day a second consumer needs localized server
output — a scheduled email, a PDF report. Building it for one endpoint would mean maintaining two
catalogues that can silently disagree, which is a worse failure than the one it prevents.

## Trade-offs accepted

- **The endpoint is less self-describing.** A caller that is not the web UI — `curl`, the MCP
  server, an integration test — gets a workbook in English. This is mitigated, not just tolerated:
  every `LabelKey` carries an English default, `labels` is optional, and a test asserts the
  no-labels path produces a complete English workbook. It is never a failure or an empty header.
- **Two lists must stay in step.** `LabelKey.java` and `frontend/src/features/export/labels.ts`
  enumerate the same keys. Adding a column to the workbook without adding its key to the frontend
  list prints an English heading in a French file — visible, but quiet. Both files carry a comment
  pointing at the other.
- **Presentation text crosses a trust boundary.** Client-supplied strings land in cells, so
  `SheetLabels` trims them, strips control characters (a newline in a header breaks the grid) and
  caps length at 120 characters. A value that sanitizes to nothing falls back to the default.

## Consequences

- `LabelKey` is the contract. Its enum names, matched case- and separator-insensitively
  (`ACCOUNT_NAME` = `accountName` = `account-name`), are the wire keys.
- Unknown keys are **ignored, not rejected** — a frontend deployed ahead of the backend must not
  fail the export.
- Sheet names come from account names (server data), never from labels, so a hostile label cannot
  influence the workbook structure. The one exception is the summary sheet's own name, which goes
  through the same `createSafeSheetName` sanitizing as every other sheet.

## Links

- Feature: [`docs/features/account-xlsx-export.md`](../features/account-xlsx-export.md)
- Related: [`docs/features/data-export.md`](../features/data-export.md) — the GDPR export, whose
  English CSV column names this decision deliberately does not follow
- Related: [`docs/features/i18n.md`](../features/i18n.md) — the frontend catalogue this leans on
