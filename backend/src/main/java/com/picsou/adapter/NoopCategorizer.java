package com.picsou.adapter;

import com.picsou.port.TransactionCategorizerPort;

import java.util.List;

/**
 * Default {@link TransactionCategorizerPort} when no AI provider is configured
 * ({@code spring.ai.model.chat=none}, the default). Always abstains, so the AI fallback is a
 * no-op and the deterministic pipeline is the only categorization path. Wired by
 * {@code AiCategorizationConfig} as the {@code @ConditionalOnMissingBean} fallback.
 */
public class NoopCategorizer implements TransactionCategorizerPort {

    @Override
    public CategorizationResult categorize(
        CategorizationInput input,
        List<CategoryOption> categories,
        List<Example> examples
    ) {
        return CategorizationResult.empty("DISABLED");
    }
}
