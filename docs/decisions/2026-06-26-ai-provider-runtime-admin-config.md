# ADR: AI provider runtime admin config (DB-only, no restart)

> Date: 2026-06-26
> Status: ✅ Active
> Refines: [2026-06-26-ai-transaction-categorization.md](./2026-06-26-ai-transaction-categorization.md) — supersedes its env-based provider selection

## Context

The prior ADR ([2026-06-26-ai-transaction-categorization](./2026-06-26-ai-transaction-categorization.md))
shipped the `TransactionCategorizerPort` infrastructure and selected the active provider via `AI_*`
environment variables and Spring AI's native auto-configuration at boot time. This required an
operator to edit env vars and restart the application to change the provider, API key, or model.

Two requirements emerged:

1. **No-restart config**: operators (self-hosted, single-instance) want to switch provider or rotate
   keys from the admin panel without restarting Docker.
2. **First-class Anthropic + OpenRouter**: these two providers were not first-class in the initial
   design (Anthropic was Spring AI auto-config only; OpenRouter had no dedicated path).

## Decision

Move all AI provider configuration — provider type, API key, model, base URL — out of environment
variables and into the `app_setting` table (DB-only). The change introduces:

- **`AiProvider` enum** (`OPENAI`, `OPENROUTER`, `ANTHROPIC`, `OLLAMA`) — exhaustive provider set.
- **`AiChatModelFactory`** — builds a `ChatModel` instance manually at runtime from the DB config,
  bypassing Spring AI auto-configuration entirely. OpenRouter reuses the OpenAI-compatible client
  with its custom base URL.
- **`AiConfigProvider`** — resolves and caches the active `TransactionCategorizerPort`
  implementation. When an admin saves new settings, the cache is immediately invalidated and rebuilt
  — no application restart.
- **`DynamicTransactionCategorizer`** — the `@Primary` `TransactionCategorizerPort` bean; it
  delegates all calls to the cached categorizer from `AiConfigProvider`. The rest of the codebase
  (notably `CategorizationService`) is unchanged.
- **`spring.ai.model.chat` stays `none`** — the Spring AI starter(s) remain on the classpath for
  their client libraries, but auto-configuration is suppressed so the starters never spin up an
  unused, key-requiring model bean at boot.
- **API key encrypted at rest** via `CryptoEncryption` (AES-256-GCM); the key is never returned in
  GET responses (only `apiKeyPresent: boolean` is surfaced).
- **New admin endpoints**: `PUT /api/admin/settings/ai` (save config) and
  `POST /api/admin/settings/ai/test` (fire one test categorization against the live model).
- **New admin UI**: `AiCategorizationSection` in `frontend/.../pages/admin/sections/` — provider
  picker, API key input, model + base URL fields, save + test buttons.
- **Optional Ollama Docker service** behind a compose `--profile ollama` — operators can run a
  local model without any external API key.
- **`AI_*` env vars removed entirely** — `AI_CATEGORIZATION_PROVIDER`, `AI_OPENAI_API_KEY`, etc.
  are no longer read or documented.

## Alternatives considered

### Keep env-var config, add an admin UI on top

Operators could still set provider via env; the admin form would be a convenience overlay. Rejected
because it creates two config sources for the same value and ambiguous precedence. DB-only is
simpler and explicit.

### Store config unencrypted in `app_setting`

The API key is a plaintext secret; storing it unencrypted alongside salted password hashes felt
wrong. Rejected in favor of reusing the existing `CryptoEncryption` infrastructure (already used
for bank connector secrets).

### One `app_setting` row per field (e.g., `ai.provider`, `ai.apiKey`, ...)

Matches how other settings are stored. Accepted for the individual fields; they are all namespaced
under the `ai.*` key prefix and loaded together by `AiConfigProvider`.

### Restart-based reload (keep auto-config, but write to application.yml)

Would require writing to a file on disk and triggering a JVM restart — too complex and fragile for
a self-hosted setup. The in-process `AiConfigProvider` cache invalidation is simpler and instant.

## Reasoning

The single-instance self-hosted context makes a per-JVM cache safe and sufficient. `AiChatModelFactory`
building the model manually is a deliberate trade-off: it couples us to the Spring AI client
constructors, but it gives us full runtime control without a restart and without a second config
surface. The OpenRouter path (OpenAI-compatible client + custom base URL) costs one extra enum
value and zero additional dependencies.

## Trade-offs accepted

- **`AI_*` env vars are a breaking change** for any deployment that was already setting them — but
  the AI feature shipped `OFF` by default in the same unreleased 1.1 line, so no operator has a
  production deployment relying on those vars.
- **The categorizer cache is per-JVM** — on a multi-instance deployment, only the instance that
  receives the `PUT /api/admin/settings/ai` request rebuilds immediately; others lag until their
  next cold lookup. Acceptable for a self-hosted, single-instance app.
- **Manual `ChatModel` construction** — if Spring AI changes its constructor signatures in a future
  minor, `AiChatModelFactory` needs updating. Mitigated by the BOM pin (`1.0.x`).

## Consequences

- Operators configure AI via the admin panel; no env vars, no restart.
- The API key is encrypted at rest and never returned over the wire.
- OpenAI, OpenRouter, Anthropic, and local Ollama are all first-class providers.
- The Docker Compose file gains an optional `ollama` service behind `--profile ollama`.
- `CategorizationService` and all callers are unchanged — the port abstraction absorbs the
  implementation swap transparently.

## Related

- Prior ADR: [2026-06-26-ai-transaction-categorization.md](./2026-06-26-ai-transaction-categorization.md)
- Feature note: [ai-categorization.md](../features/ai-categorization.md)
- Encryption infrastructure: [2026-03-01-aes-gcm-crypto-secrets.md](./2026-03-01-aes-gcm-crypto-secrets.md)

---

## Async categorization job + AI-call audit log (2026-06-27)

### Context

The original `POST /transactions/categorize-ai` ran all LLM calls **synchronously** inside one HTTP
request. On inboxes with many uncategorized transactions this could exceed nginx's 60-second proxy
timeout (504 Gateway Timeout), forcing users to re-trigger a partial run — and losing all progress
if they closed the tab.

A second gap was **observability**: there was no record of what prompts were sent, what the model
responded, or how many tokens were consumed.

### Decision

**Background job with bounded concurrency and incremental commits.**
`POST /api/transactions/categorize-ai` now returns **202 Accepted** immediately and starts an
in-memory job (`AiCategorizationJobService`). The job:

1. Takes a snapshot of uncategorized transaction IDs at submission time.
2. Processes them in **chunks of size C** (`ai.max-concurrency`, default 4, clamp 1..16).
3. Runs C `categorizer.categorize()` calls **concurrently** via a dedicated `inferenceExecutor`
   thread pool (`AiExecutorConfig`).
4. Commits each chunk's results in its own transaction (incremental — progress is durable after
   each chunk even if the JVM restarts later, though the job state itself is lost — see trade-offs).
5. Exposes progress via `GET /api/transactions/categorize-ai/status` → `AiJobStatus { running,
   total, processed, applied, suggested, done, error }`, which the frontend polls. The Categorize
   tab resumes the progress display on reload because the job keeps running server-side.

An **atomic one-per-member guard** (`ConcurrentHashMap.compute`) prevents parallel jobs for the
same member; re-submitting while a job is running returns the existing `AiJobStatus`.

**Per-call audit log.**
`TransactionCategorizerPort.categorize()` now returns a rich `CategorizationResult` (slug +
confidence + prompt text + token usage). Every call — success, suggestion, or failure — is recorded
in `ai_call_log` (Flyway **V42**) by `AiCallLogService.saveAll(List)`. After each batch the table
is pruned to the **newest 2000 rows** via `AiCallLogService.prune()`. Admins can browse the log
via **Admin → AI activity** (`GET /api/admin/ai-calls`): a paginated modal with expandable
prompt/response and total token aggregates.

### Alternatives considered

- **Async via Spring `@Async` on `CategorizationService`**: simpler, but gives no per-chunk commit
  and no progress API. Rejected.
- **Persistent job table (DB-backed job queue)**: survives restarts, supports multi-instance.
  Overkill for a single-instance self-hosted app; adds schema + polling complexity. Rejected.
- **Server-Sent Events instead of polling**: cleaner UX, but harder to resume on reload (SSE is a
  live stream, not a snapshot). Polling `GET /status` is trivially resumable. Rejected.
- **Unlimited retention on `ai_call_log`**: prompts contain merchant labels + amounts (financial
  data). Unbounded growth is both a storage risk and a privacy risk. 2000-row cap accepted.

### Trade-offs accepted

- **In-memory job state is lost on JVM restart.** The per-chunk commits mean already-processed
  transactions are permanently categorized, but the `AiJobStatus` (RUNNING / progress counter)
  disappears. The client sees `NOT_STARTED` on the next poll and the user must re-trigger to
  process the remaining uncategorized transactions. Acceptable for a single-instance deployment
  where restarts are rare and deliberate.
- **`ai_call_log` holds financial data.** Prompts include merchant labels and amounts. Mitigated by:
  admin-only endpoint and the 2000-row retention cap. Prompts and responses are stored as plaintext
  `TEXT` columns (only the API key receives field-level AES-256-GCM encryption); operators who
  require encryption-at-rest for the audit log must rely on disk- or DB-level encryption.
- **`CategorizationResult` couples the port to token metadata.** The port now carries
  `promptTokens` / `completionTokens` fields that `NoopCategorizer` returns as zero. This is a
  minor leakage of infrastructure concerns into the domain port, accepted for observability value.
