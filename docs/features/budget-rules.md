# Feature: Budget categorization rules — authoring UX

> Last updated: 2026-06-28
> Status: **Phase 1 implemented.** Phases 2 & 3 are roadmap (vision only, not detailed design).

## Context

Picsou already has a categorization rules engine, but the only way to "teach" it from a transaction is a binary **"Remember a rule"** switch that blindly learns a rule on the *entire* counterparty. Users cannot choose *which part* of a transaction label should drive the rule (e.g. only the word `MB`, or `MB` + `Factures`). This feature turns rule authoring into a **direct, no-code, click-the-words** interaction, and lays out a roadmap toward a full rule scripter and optional AI-assisted suggestions.

## Scope

- **Phase 1 (implemented):** "Remember a rule" becomes a **button**. The user clicks individual **words** of a transaction's label to build a rule by pure selection (one or several words, not necessarily adjacent). Beautiful, no scripting. The rule applies to the current transaction **and retro-applies to matching history** (with a checkable preview list). An AND/OR toggle (`KEYWORDS_ALL` / `KEYWORDS_ANY`) controls the match semantics. A cherry-pick per-row checkbox on the preview list lets the user scope retro-apply to a selected subset.
- **Phase 2 (roadmap):** a separate **"Rules"** section with a full condition/action rule builder *à la* Actual Budget, UI/UX inspired by **Apple Shortcuts**.
- **Phase 3 (roadmap):** optional **AI-assisted rule suggestion** behind a port, with a local (Ollama) adapter and a bring-your-own-key adapter. **Off by default.**

## How it works (Phase 1)

### Existing engine (recap — unchanged foundations)

- `CategorizationRule` (`model/CategorizationRule.java`, table from `V33__budget_foundation.sql`): `member_id`, `match_type`, `pattern`, `category_id`, `priority`, `source` (USER/AUTO), `created_at`.
- `RuleMatchType`: `COUNTERPARTY` (exact, case-insensitive on `counterparty`) and `KEYWORD` (case-insensitive substring on `counterparty` **or** `description`).
- Precedence (`CategorizationService`): (1) `categoryRef != null` guard — never overrides an existing category; (2) USER/AUTO rules, `priority DESC, id ASC`, first match wins; (3) brand KB fallback; (4) uncategorized.
- Today's learn flow: `PUT /transactions/{id}/category { categoryId, createRule }` → `learnRule()` creates an **AUTO COUNTERPARTY** rule on the whole counterparty, idempotently.
- Rules **do not** retro-apply: `recategorizeUncategorized()` only touches *uncategorized* rows.

### Backend additions

1. **Two new match types** (`RuleMatchType`):
   - **`KEYWORDS_ALL`** (AND): `pattern` holds space-joined tokens; the matcher splits on space and requires **every** token to be a case-insensitive substring of the match source. Single-word selections collapse to one token (equivalent to a `KEYWORD`).
   - **`KEYWORDS_ANY`** (OR): at least one of the space-split tokens must match. Same tokenization and match-source broadening as `KEYWORDS_ALL`.
   - **No migration needed.** `categorization_rule.match_type` is a plain `VARCHAR(20)` with no `CHECK` constraint (V33 `budget_foundation.sql`, line 30), so adding enum values is a Java-only change — the same deliberate "string, not a native PG enum, so the value set can grow without a migration" choice already made for `merchant_alias.match_type` (V39).
2. **Match source includes `merchantLabel` (3 sources).** `CategorizationService.matches()` receives `(counterparty, description, merchantLabel)` as three strings; `KEYWORD`, `KEYWORDS_ALL`, and `KEYWORDS_ANY` all test all three. This guarantees *the words the user sees and clicks are the words the rule matches* — dissolving the display-vs-match mismatch.
3. **Dry-run preview.** `POST /api/categorization-rules/preview { matchType, pattern }` → `{ matchCount, transactions: [...] }`: a **checkable list** of the transactions the rule would change — rows where `category_ref IS NULL OR category_manual = false`. Lives on the existing `CategorizationRuleController` (`/api/categorization-rules`). Feeds both the live "concerns N transactions" counter and the per-row cherry-pick checkboxes in the picker UI.
4. **Retro-apply on creation.** Rule creation categorizes all matching transactions **respecting the manual-override guard** — it never overwrites a category the user set by hand. Honoring this needs a way to tell *manually set* categories apart from *auto-rule* ones, which the schema does not record today (the engine only knows `categoryRef != null`). Add a `transaction.category_manual` boolean (Flyway **V45** — `V45__category_manual.sql`; V42–V44 were already taken by other migrations; default `false`); it is set `true` **only** on the manual inbox path (`categorize()` with the user picking a category) and by Revolut-pocket detection (so a learned rule can never clobber a `virement-interne` transfer leg — see `revolut-pockets.md`). Retro-apply and the preview count both target rows where `category_ref IS NULL OR category_manual = false`. The preview count reflects exactly what will change. This is a deliberate departure from the uncategorized-only `recategorizeUncategorized`. (A dedicated `POST /api/categorization-rules/{id}/apply` for re-running an existing rule is deferred to Phase 2, where rules become independently editable.)
5. **Generalized learning.** The categorize/create-rule request carries `{ categoryId, createRule, rulePattern?, ruleMatchType?, applyToTransactionIds?: number[] }`. When `applyToTransactionIds` is present, retro-apply is **scoped to that subset** (the rows the user checked in the preview list); otherwise it applies to all matching uncategorized/auto rows. Idempotency check reuses `findFirstByMemberIdAndMatchTypeAndPatternIgnoreCase` with the new type.

### Frontend additions

- In `CategorizeTab.tsx` `InboxRow`, replace the plain truncated `<p>` label with a tokenized, click-to-select word picker, and turn the "Remember a rule" `Switch` into a **"Create rule"** button.
- New `<RuleWordPicker>` component:
  - Tokenizes the displayed label (`merchantLabel || counterparty || description`) on whitespace + punctuation, preserving original casing. UUID-heavy tokens (e.g. `To EUR MB:<uuid>` in Revolut pocket labels) are kept as independent, skippable tokens.
  - Tracks a `Set<tokenIndex>` of selected words; selected tokens render highlighted, reusing the `ColorPicker` interaction pattern (`border-2`, accent bg, `hover:scale-110`).
  - **AND/OR toggle** switches between `KEYWORDS_ALL` and `KEYWORDS_ANY` match semantics live.
  - Assembles selected words into the chosen pattern and shows a **live preview list** (debounced from the dry-run endpoint) with **per-row checkboxes** for cherry-picking which transactions receive the rule on confirm. Live counter: *"Concerns N transactions."*
- **Surface:** a polished `Dialog` on desktop, **bottom-sheet** on mobile (responsive is mandatory). The roomy surface hosts the word picker, category select, the live preview/count, and a confirm button.
- API/hooks: extend `budgetApi`/`features/budget/hooks.ts` with `previewRule()` and reuse `createRule()`/`categorize()`.

```
InboxRow → [Create rule] button
        ▼
RuleWordPicker (Dialog / bottom-sheet)
  tokenize label → tap words → KEYWORDS_ALL/ANY pattern (AND/OR toggle)
        │  debounced
        ├─► POST /categorization-rules/preview  → "concerns N transactions"
        ▼  confirm
  categorize(tx) + createRule(KEYWORDS_ALL/ANY) + retro-apply (manual-guarded, optional applyToTransactionIds subset)
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| `KEYWORDS_ALL` / `KEYWORDS_ANY` (AND/OR toggle) | Covers "all words must appear" and "any one word" in a single UX gesture; small, contained engine change | Contiguous-only single `KEYWORD` (can't express scattered words) |
| Add `merchantLabel` to match fields | Words shown == words matched; removes silent non-firing rules | Map clicks back to raw field (fragile, surprising) |
| Retro-apply with manual-guard + preview count | Instant, "magic" payoff without clobbering manual choices | Apply blindly (mass mis-tag risk) / never retro-apply (feels inert) |
| Dialog (desktop) / bottom-sheet (mobile) | Room for preview + count; meets mobile-responsive requirement | Inline-in-row (cramped, no room for preview) |
| Reuse `ColorPicker` toggle pattern for tokens | Existing, polished, accessible interaction precedent | Bespoke selection styling |

## Gotchas / Pitfalls

- **Retro-apply must respect the manual-override guard.** Never overwrite a manually set category; the preview count must count only rows that will actually change, or the "N transactions" promise lies.
- **Idempotent learning for the new type.** Dedupe learned rules on `(matchType, pattern)`, not just `COUNTERPARTY`.
- **Tokenization vs matching parity.** Tokenize and match against the *same* normalized text (case-insensitive); punctuation stripped for display tokens must not change the stored pattern's match behavior.
- **Priority.** Learned `KEYWORDS_ALL` rules default to `priority 0` like today's learned rules; multi-word rules are naturally more specific but priority ordering still decides ties — surfacing/reordering priority is a Phase 2 concern.
- **UUID-heavy labels.** For pocket rows like `To EUR MB:<uuid>`, the picker should let the user select `MB` without forcing selection of the noisy UUID token — selection is per-word, so this works, but the tokenizer should treat the UUID as its own (skippable) token.

## Roadmap (not specified in detail here)

### Phase 2 — full rule scripter (Actual Budget × Apple Shortcuts)

Conditions/actions model (store as Postgres `JSONB` columns on a rules table; the current single-`pattern` row can't hold arrays):

```jsonc
{
  "conditionsOp": "and",            // and | or
  "conditions": [
    { "field": "counterparty", "op": "contains", "value": "MB" },
    { "field": "description",  "op": "matches",  "value": "facture.*" },  // regex (new)
    { "field": "amount",       "op": "lt",       "value": -50.0 }          // amount (new)
  ],
  "actions": [ { "field": "category", "op": "set", "value": 7 } ]
}
```

- Fields: `counterparty`, `description`, `merchantLabel`, `amount`, `date`, `account`, `category`, `merchantBrandId`. Operators: `is/isNot`, `contains/notContains`, `matches` (regex — new), `oneOf/notOneOf`, `gt/lt/gte/lte`.
- Shortcuts UX to borrow: condition/action **cards** with verb-first summaries, **inline pill tokens** for values, live "matches N" feedback, **drag-to-reorder** for priority (finally exposing the hidden `priority` field), 44px+ touch targets.
- Refs: [Actual Budget rules](https://actualbudget.org/docs/budgeting/rules/), [custom rules](https://actualbudget.org/docs/budgeting/rules/custom/).

### Phase 3 — optional AI rule suggestion (off by default)

- **Apple Foundation Models is NOT reachable** from Picsou (Swift-only, on-device, no REST/browser access). Dropped unless a separate native companion app is ever shipped.
- A new `RuleSuggestionPort` (mirroring `PriceProviderPort`) with two adapters: **Ollama sidecar** (local small model, Docker-native, `logprobs` → confidence) and **BYOK proxy** (user key, AES-GCM encrypted, backend-injected). Confidence feeds the existing silent auto-confirm machinery (cf. recurring detection, commit `fa443b0`).
- **Shipped sibling (2026-06-26):** the *direct categorizer* half of this idea now exists as `TransactionCategorizerPort` — instead of proposing a rule pattern, it categorizes the uncategorized transaction itself (Ollama / OpenAI-compatible / Claude via Spring AI, off by default). See [`ai-categorization.md`](./ai-categorization.md) and its ADR. A future `RuleSuggestionPort` can reuse the same provider plumbing.

## Tests

- `CategorizationServiceTest` (extension) — `KEYWORDS_ALL` (all tokens required) and `KEYWORDS_ANY` (any token sufficient) matching, case-insensitive, order-independent; `merchantLabel` now matched by `KEYWORD`/`KEYWORDS_ALL`/`KEYWORDS_ANY`; precedence and manual guard unchanged.
- `RulePreviewServiceTest` / endpoint test — preview returns `{ matchCount, transactions }` for rows where `category_ref IS NULL OR category_manual = false`; `applyToTransactionIds` scopes retro-apply to the given subset.
- Retro-apply test — applies to matching uncategorized/auto rows, never overwrites manual categories; idempotent on re-run.
- `learnRule` idempotency for `KEYWORDS_ALL`.
- Frontend: `RuleWordPicker` unit test (tokenize, multi-select, pattern assembly, skipping UUID token) + mobile bottom-sheet rendering.

## Links

- Related: `docs/features/budget.md`, `docs/decisions/2026-06-02-budget-cycle-and-categorization.md`, `docs/decisions/2026-06-09-merchant-kb-and-budget-ia.md`.
- Sibling feature (shares categorization): `docs/features/revolut-pockets.md`.
- Phase 3 ADR to write if pursued: "Optional AI rule suggestion via RuleSuggestionPort".
