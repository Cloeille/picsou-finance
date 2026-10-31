package com.picsou.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Optional LLM-backed categorizer. It is a <em>fallback</em>: the deterministic pipeline
 * (USER/AUTO rules + the offline merchant knowledge base) runs first and always wins; this
 * port is only consulted for the long tail of transactions left uncategorized.
 *
 * <p>Implementations must never throw into the categorization pipeline — on any provider
 * failure (timeout, bad output, provider down) they return a {@link CategorizationResult}
 * with {@code status="ERROR"} and an empty suggestion. The default wiring is a no-op so the
 * feature is fully OFF until an operator configures a provider.
 */
public interface TransactionCategorizerPort {

    /**
     * Propose a category for one transaction.
     *
     * @param input      the transaction's classifiable signal (cleaned label, raw memo, amount)
     * @param categories the member's own categories the model must choose among (its taxonomy)
     * @param examples   a few of the member's recently hand-categorized transactions (few-shot)
     * @return a rich result capturing the suggestion (if any), the prompt sent, the response
     *         text, token usage, latency, and a status code
     */
    CategorizationResult categorize(
        CategorizationInput input,
        List<CategoryOption> categories,
        List<Example> examples
    );

    /** The classifiable signal of one transaction. {@code merchantLabel} is the cleaned name. */
    record CategorizationInput(String merchantLabel, String description, BigDecimal amount) {}

    /** One of the member's categories the model may pick: a stable {@code slug} + display {@code name}. */
    record CategoryOption(String slug, String name) {}

    /** A few-shot example: a past merchant label and the category slug the member assigned it. */
    record Example(String merchantLabel, String categorySlug) {}

    /** The model's answer: the chosen category {@code slug} and a self-reported {@code confidence} in 0..1. */
    record CategorySuggestion(String categorySlug, double confidence) {}

    /**
     * Rich result of one categorization attempt.
     *
     * <p>{@code status} is one of:
     * <ul>
     *   <li>{@code "OK"} — suggestion is present and the slug was recognized</li>
     *   <li>{@code "EMPTY"} — no categories provided, or model abstained / returned unknown slug</li>
     *   <li>{@code "ERROR"} — provider exception; {@code error} carries the message</li>
     *   <li>{@code "DISABLED"} — no AI provider configured (noop)</li>
     * </ul>
     */
    record CategorizationResult(
        Optional<CategorySuggestion> suggestion,
        String prompt,
        String response,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        long latencyMs,
        String status,
        String error
    ) {
        public static CategorizationResult empty(String status) {
            return new CategorizationResult(Optional.empty(), null, null, null, null, null, 0, status, null);
        }
    }
}
