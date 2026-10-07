# ADR: Optional AI transaction categorization via `TransactionCategorizerPort`

> Date: 2026-06-26
> Status: ✅ Active

## Context

Budget categorization is a deterministic, zero-cost pipeline (`CategorizationService`): manual
guard → USER/AUTO rules → 137-brand offline knowledge base → learn-on-manual. It is precise but
only covers merchants it already knows; unknown merchants — the long tail — stay uncategorized and
must be sorted by hand. We want an **opt-in** AI categorizer to absorb that long tail, configurable
to run locally (Ollama), against a self-hosted/external OpenAI-compatible endpoint, or against
Claude — without compromising privacy-by-default or the existing deterministic guarantees.

## Decision

Add an optional LLM categorizer behind a new `TransactionCategorizerPort` (in `port/`), used as a
**fallback** that only runs on transactions the deterministic pipeline left uncategorized
(`CategorizationService.aiCategorizeUncategorized`). It is implemented with **Spring AI's
`ChatClient`** (`SpringAiCategorizer`), whose underlying `ChatModel` — **Ollama / OpenAI-compatible
/ Anthropic** — is chosen by configuration (`spring.ai.model.chat`). It is **off by default**: with
`none`, no `ChatModel` is auto-configured and a `NoopCategorizer` is wired, so the app behaves
exactly as before.

The model is asked to pick one of the **member's own category slugs** (plus a few-shot set drawn
from the member's recent manual choices) and to return a slug + confidence as **structured JSON**.
Application is **member-configurable**: a per-member `AiCategorizationMode`
(`SUGGEST` / `AUTO_HIGH_CONFIDENCE` / `AUTO_ALL`) and a 0–100 confidence threshold decide whether an
answer is auto-applied or stored as a pending suggestion on the transaction for the inbox.

## Alternatives considered

### Hand-rolled `WebClient` adapters (one per provider)

- **Pros**: matches the existing adapter style (CoinGecko, Binance); zero new framework.
- **Cons**: ~3× the integration code (separate request/response shapes and JSON-mode handling per
  provider); we'd re-implement structured-output parsing the framework already does.

### LangChain4j

- **Pros**: clean multi-provider `ChatLanguageModel` abstraction.
- **Cons**: introduces a *second* AI framework alongside the Spring AI already present for MCP.

### Official Anthropic Java SDK for the Claude path

- **Pros**: the most canonical Claude client.
- **Cons**: a different code shape from the Ollama/OpenAI paths; an extra dependency. (Spring AI's
  Anthropic starter is a native Anthropic client, not an OpenAI shim, so it satisfies the intent.)

### Embedding + nearest-category similarity instead of generative classification

- **Pros**: fast, deterministic, cosine score as natural confidence.
- **Cons**: needs an embedding model; the models we were pointed at (Qwen3-0.6B, LFM2.5-230M) are
  generative. Kept as a possible future provider behind the same port.

## Reasoning

Spring AI is **already a dependency** (1.0.3, for the MCP server) and natively speaks all three
requested providers behind one `ChatClient`, so the three-provider requirement costs almost no new
code and stays idiomatic. Keeping the LLM as a **fallback** preserves the high-precision
deterministic pipeline and the single invariant — a USER/manual category is never overwritten — and
minimizes inference (most transactions are already categorized by rules/KB before the model sees
anything). Structured output + the member's own slug set make a sub-1B model reliable enough to
trust behind a confidence gate.

## Trade-offs accepted

- **Provider config is instance-level via env / `application.yml`** (the Spring AI native
  properties), not a runtime admin UI. Appropriate for an operator-set secret; a DB-backed admin
  form is deferred.
- **Only categories with a `slug` are offered to the model** (the default taxonomy). User-created
  categories without a slug aren't AI-targetable in v1 — acceptable because those are exactly what
  the user already categorizes by hand (which the rules then learn).
- **Confidence is self-reported** by the model (portable across providers). Logprob-based
  confidence (Ollama/OpenAI) is a future refinement.
- The batch runs **on demand** (inbox button / `POST /api/transactions/categorize-ai`); a scheduled
  nightly pass is deferred.

## Consequences

- New `port/TransactionCategorizerPort`, `adapter/SpringAiCategorizer`, `adapter/NoopCategorizer`,
  `config/AiCategorizationConfig` (selects the bean via `ObjectProvider<ChatModel>.getIfUnique()`).
- `CategorizationService.aiCategorizeUncategorized(memberId)` + endpoint
  `POST /api/transactions/categorize-ai` returning `{applied, suggested}`.
- Flyway `V41`: `budget_settings.ai_categorization_enabled / ai_mode / ai_confidence_threshold`;
  `transaction.ai_suggested_category_id / ai_confidence`.
- Three Spring AI model starters added (governed by the existing 1.0.3 BOM — do **not** bump to
  1.1.x). `spring.ai.model.{chat,embedding,image,audio,moderation}` default to `none`, so adding the
  starters never spins up an unused, key-requiring client at boot.
- Frontend: an AI settings card (toggle + mode + sensitivity), an inbox suggestion chip + a gated
  "Categorize with AI" button.

## Related

- Sibling roadmap item: [budget-rules.md](../features/budget-rules.md) Phase 3 (`RuleSuggestionPort`)
  — suggests a *rule pattern* rather than directly categorizing; both could share provider plumbing.
- Builds on [2026-06-09-merchant-kb-and-budget-ia.md](./2026-06-09-merchant-kb-and-budget-ia.md).
