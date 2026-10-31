# Feature: Optional AI transaction categorization

> Last updated: 2026-06-27

## Context

The deterministic categorization pipeline (rules + offline brand knowledge base) only categorizes
merchants it already knows; the long tail of unknown merchants stays in the inbox for manual
sorting. This feature adds an **opt-in** LLM categorizer that absorbs that long tail, with a
swappable provider (local Ollama / OpenAI / OpenRouter / Anthropic) and member-tunable
autonomy. It is **off by default** and never overrides a rule or manual choice.

The AI provider (provider type, API key, model, base URL) is configured at runtime through
**Admin → Settings → AI categorization** and stored encrypted in the database — no env vars,
no restart required.

## How it works

AI is a **fallback layer**, not a replacement. The deterministic pipeline runs first and always
wins; the LLM is only asked about transactions still uncategorized afterwards, and only when the
member has enabled it. It runs as a **background job** (not inline during sync, which must stay fast
and offline), triggered on demand from the inbox.

When a member clicks "Categorize with AI", `POST /api/transactions/categorize-ai` returns **202
immediately** and launches a server-side job (`AiCategorizationJobService`). The client polls
`GET /api/transactions/categorize-ai/status` for progress — the budget Categorize tab shows "AI
categorizing… X/total" and **resumes on page reload** (tab-close-survive). Only one job runs per
member at a time (atomic guard).

The job processes a snapshot of uncategorized transaction IDs in **chunks of size C**
(`ai.max-concurrency`, default 4, clamp 1..16 — set in Admin → Settings → AI categorization
"Parallel requests"). Each chunk runs C `categorizer.categorize()` calls **concurrently** on a
dedicated inference executor, then commits the chunk's results in its own transaction. Progress
increments after every chunk.

For each uncategorized transaction the model is given the member's **own category slugs** (its
taxonomy), a few **few-shot examples** from the member's recent manual choices, and the cleaned
`merchantLabel` + amount. It returns a slug + confidence as **structured JSON**. The member's
`AiCategorizationMode` and confidence threshold then decide what happens:

- `AUTO_ALL` — always set the category.
- `AUTO_HIGH_CONFIDENCE` (default) — set it when `confidence ≥ threshold`, else store a suggestion.
- `SUGGEST` — only store a suggestion.

A stored suggestion lives on the transaction (`ai_suggested_category_id` + `ai_confidence`) so the
inbox can render "AI: Transport · 92%" and pre-select that category — accepting is one click.

### Key files

- `backend/.../port/TransactionCategorizerPort.java` — the provider-agnostic port; `categorize()` returns `CategorizationResult` (slug + confidence + prompt + token usage)
- `backend/.../adapter/SpringAiCategorizer.java` — Spring AI `ChatClient` implementation; parses the first non-blank generation with a `BeanOutputConverter` and reads `ChatResponse` usage
- `backend/.../adapter/NoopCategorizer.java` — default when no provider is configured
- `backend/.../adapter/DynamicTransactionCategorizer.java` — `TransactionCategorizerPort` bean; delegates to cached categorizer from `AiConfigProvider`
- `backend/.../config/AiCategorizationConfig.java` — wires the `TransactionCategorizerPort` as a `DynamicTransactionCategorizer` (resolves the provider at call time via `AiConfigProvider`)
- `backend/.../config/AiChatModelFactory.java` — builds a `ChatModel` at runtime from DB config (OpenAI/OpenRouter/Anthropic/Ollama)
- `backend/.../config/AiConfigProvider.java` — resolves + caches the active categorizer; rebuilt on save (no restart)
- `backend/.../config/AiExecutorConfig.java` — dedicated `ThreadPoolTaskExecutor` (`inferenceExecutor`) for concurrent AI calls
- `backend/.../config/AiProviderConfig.java` — plain Java `record` for provider config
- `backend/.../model/AiProvider.java` — enum: `OPENAI`, `OPENROUTER`, `ANTHROPIC`, `OLLAMA`
- `backend/.../model/AiCallLog.java` — JPA entity: per-call audit record (prompt, response, tokens, latency, status, provider/model, chosen category, applied flag)
- `backend/.../repository/AiCallLogRepository.java` — JPA repository for `AiCallLog`
- `backend/.../service/AiCallLogService.java` — `saveAll(List)` persists a batch of call rows, `prune()` enforces the 2000-row retention cap
- `backend/.../service/budget/AiCategorizationJobService.java` — background job: snapshot → chunked concurrent categorize → per-chunk commit → progress tracking; one-per-member guard (`ConcurrentHashMap.compute`)
- `backend/.../service/budget/CategorizationService.java` — `loadAiContext`, `uncategorizedIds`, `inputsFor`, `applyAiResults` are the real entry points consumed by the job service (`aiCategorizeUncategorized` was removed)
- `backend/.../dto/AiJobStatus.java` — poll response: `{ running, total, processed, applied, suggested, done, error }`
- `backend/.../controller/TransactionCategorizationController.java` — `POST /api/transactions/categorize-ai` (202) + `GET /api/transactions/categorize-ai/status`
- `backend/.../controller/AdminController.java` — `PUT /api/admin/settings/ai` + `POST /api/admin/settings/ai/test` + `GET /api/admin/ai-calls`
- `backend/.../resources/db/migration/V41__ai_categorization.sql` — settings + suggestion columns
- `backend/.../resources/db/migration/V42__ai_call_log.sql` — `ai_call_log` table
- `frontend/.../pages/budget/ManageTab.tsx` — `AiCategorizationCard` (toggle + mode + sensitivity)
- `frontend/.../pages/budget/CategorizeTab.tsx` — suggestion chip + "Categorize with AI" button + live job progress poll
- `frontend/.../pages/admin/sections/AiCategorizationSection.tsx` — admin UI: provider, API key, model, base URL, parallel requests, test button
- `frontend/.../pages/admin/sections/AiActivitySection.tsx` — admin "AI activity" modal: paginated call log with expandable prompt/response + total token counts

### Flow

```
sync / import ─► deterministic pipeline (rules → brand KB) ─► still uncategorized?
                                                                     │
member clicks "Categorize with AI" ──► POST /api/transactions/categorize-ai
                                              │
                                     202 Accepted + AiJobStatus{RUNNING}
                                              │
                                    job starts (one per member, atomic guard)
                                              │
                           snapshot of uncategorized tx IDs
                                              │
                               ┌── chunk (size = ai.max-concurrency) ──┐
                               │  C concurrent categorize() calls        │
                               │  SpringAiCategorizer → LLM             │
                               │  CategorizationResult: slug+conf+       │
                               │    prompt+tokens → AiCallLogService     │
                               │                                         │
                               │  confidence ≥ threshold (or AUTO_ALL)  │
                               │    yes → set categoryRef                │
                               │    no  → store suggestion (inbox chip)  │
                               │                                         │
                               │  per-chunk DB commit → progress++       │
                               └────────── repeat until done ───────────┘
                                              │
                     client polls GET /api/transactions/categorize-ai/status
                     → { status, processed, total } (survives tab close/reload)
```

### Token & prompt audit log

Every AI call — whether it sets a category, stores a suggestion, or fails — is recorded in the
`ai_call_log` table (Flyway **V42**). Each row stores: prompt sent, raw model response, input/output
token counts, latency ms, status, provider + model name, the chosen category slug, and whether it
was applied. The table is pruned to the **newest 2000 rows** after each write.

Admins can inspect the log via **Admin → AI activity**: a modal lists calls paginated, with
expandable prompt/response columns and total token usage aggregated across the page. The data is
served by `GET /api/admin/ai-calls` (admin-only).

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Spring AI `ChatClient` | Already a dependency (MCP); one interface for all 3 providers | Hand-rolled WebClient adapters; LangChain4j |
| Fallback over uncategorized only | Preserves the precise deterministic pipeline; minimizes inference | LLM as the whole engine |
| Choices keyed by category `slug` | Stable round-trip + human-readable labels for a small model | Keying by category id |
| `spring.ai.model.chat=none` default | Suppresses Spring AI auto-configuration; the `ChatModel` is built at runtime by `AiChatModelFactory` from DB config, so the app boots cleanly with no model and no env vars | Always-on / a separate feature flag |
| Suggestion stored on the transaction | Inbox renders without re-running inference each load | Recompute on every inbox load |

## Gotchas / Pitfalls

- **Never inline in sync.** `autoCategorize` runs per-transaction during import and must stay
  offline/fast — the LLM only runs in the separate background job.
- **The `categoryRef != null` guard is sacred.** The job only ever iterates the already-
  uncategorized set; a model answer for a slug the member doesn't have is ignored.
- **Job state is in-memory.** `AiCategorizationJobService` holds the `AiJobStatus` in a
  `ConcurrentHashMap` keyed by member ID. A JVM restart loses in-flight jobs; the client will see
  the status endpoint return `NOT_STARTED` and the user must re-trigger. Acceptable for a
  single-instance self-hosted setup.
- **One job per member.** The atomic guard (`ConcurrentHashMap.compute`) prevents double-submission;
  re-clicking "Categorize with AI" while a job is running returns the existing `AiJobStatus` (no stacking).
- **Provider config is runtime admin, not env.** Provider, API key, model, and base URL are stored
  in `app_setting` (DB-only, key encrypted via `CryptoEncryption`). There are no `AI_*` env vars.
  Saving the config triggers an immediate cache rebuild in `AiConfigProvider` — no restart needed.
  OpenRouter reuses the OpenAI-compatible client with a different base URL.
- **Audit log holds financial data.** `ai_call_log` contains the full prompt (which includes
  merchant label + amount). The endpoint is admin-only; retention is capped at 2000 rows to bound
  exposure.
- **Anthropic requires `max_tokens`.** `AnthropicChatModel.builder()` carries `maxTokens=500` in
  its default options, but `AiChatModelFactory` passes its own `defaultOptions` (for the model id),
  which *replaces* them — so the factory sets `maxTokens` (4096) itself. Without it every Anthropic
  call fails with `400 max_tokens: Field required`. The cap covers thinking + answer; tokens are
  billed as generated, so the headroom costs nothing.
- **No sampling parameters for Anthropic.** The builder defaults also carried `temperature=0.8`;
  current Claude models reject non-default sampling values with a 400, so the factory sets none.
- **Thinking models put a thinking generation first.** Claude Haiku 5.5 (and the other Claude 5.x
  models) think by default, and Spring AI maps the thinking block to the *first* `Generation`
  (empty text under the default `display: omitted`, a summary under `display: summarized`).
  `ChatClient...responseEntity()` and `.content()` read only that one, so the categorizer and the
  admin Test button use `SpringAiCategorizer.answerText()`, which skips generations Spring AI tags
  as thinking (`signature` / `data` property) and takes the first one with text. A response with
  no text at all (e.g. thinking used up `max_tokens`) is an `ERROR` for the categorizer and a
  failed Test (`ok=false`), never a silent success. Disabling
  thinking is not an option: Opus 5.5 / Sonnet 5.5 reject `thinking: disabled` with a 400.
- **Console API keys only.** Spring AI authenticates with `x-api-key`: a Console key (personal,
  service-account or workspace) works; a Claude Pro/Max subscription token (`sk-ant-oat…`) does
  not, and the API is billed to the Console organization, not the subscription. A key that isn't
  scoped to a workspace needs an `anthropic-workspace-id` header Picsou doesn't send yet (400).
- **Spring AI is on 1.1.x** since the MCP keep-alive fix — see
  [ADR](../decisions/2026-10-27-spring-ai-1-1-for-mcp-sse-keep-alive.md); the two Anthropic
  pitfalls above were observed on 1.1.8.
- **Only slugged categories are offered** to the model in v1 (the default taxonomy); user-created
  categories without a slug are not AI-targetable yet.

## Tests

- `CategorizationServiceTest` — `aiCategorize_*` cases: each mode, the threshold boundary, unknown
  slug ignored, model abstain ignored, disabled = no-op (and never calls the model).
- `AiCategorizationJobServiceTest` — job lifecycle: starts, processes chunks, increments progress,
  finishes; one-per-member guard (second call returns existing status); error path sets `done=true, error=<message>`.
- `AiCallLogServiceTest` — `saveAll(List)` persists rows; `prune()` deletes oldest rows when count
  exceeds 2000.
- `BudgetSeedWriteOnReadPostgresTest` — boots the full context with the three provider starters
  present and runs `V41`+`V42` on real Postgres (verifies `none` keeps startup clean).
- `SpringAiCategorizerTest` — a thinking-block-first response (empty or summarized) is parsed from
  the text block; the JSON format instructions are sent and audited; a fenced answer parses and
  token usage is recorded; no text at all is an `ERROR`; `unknown` yields no suggestion.
- `AiConfigProviderTest` — `test_thinking*`: thinking then `OK` passes, thinking only fails.
- `AiChatModelFactoryTest` — the Anthropic model carries `maxTokens`.
- `CategorizeTab.test.tsx` — suggestion pre-selects the dropdown + renders the chip; the
  "Categorize with AI" button is gated on the setting; progress indicator renders while status is
  `RUNNING`.

## Running Ollama in Docker

The Docker Compose file ships an optional `ollama` service behind a compose profile:

```bash
docker compose --profile ollama up -d ollama
docker compose exec ollama ollama pull qwen3:0.6b
```

Then in **Admin → Settings → AI categorization**, select "Ollama (local)" and set the base URL to
`http://ollama:11434`. The `ollama` container is on the same Docker network as the backend.

## Links

- ADR: [2026-06-26-ai-transaction-categorization.md](../decisions/2026-06-26-ai-transaction-categorization.md)
- ADR: [2026-06-26-ai-provider-runtime-admin-config.md](../decisions/2026-06-26-ai-provider-runtime-admin-config.md) — supersedes env-based provider selection
- Related: [budget-rules.md](./budget-rules.md) Phase 3 (`RuleSuggestionPort`, a sibling idea)
