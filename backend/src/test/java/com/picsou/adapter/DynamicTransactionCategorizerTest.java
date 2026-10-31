package com.picsou.adapter;

import com.picsou.config.AiConfigProvider;
import com.picsou.port.TransactionCategorizerPort;
import com.picsou.port.TransactionCategorizerPort.CategorizationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicTransactionCategorizerTest {

    @Mock
    AiConfigProvider provider;

    @Mock
    TransactionCategorizerPort delegate;

    // ─── 1. delegatesToCurrentCategorizer ────────────────────────────────────

    @Test
    void delegatesToCurrentCategorizer() {
        TransactionCategorizerPort.CategorizationInput input =
            new TransactionCategorizerPort.CategorizationInput("Amazon", "Order #123", new BigDecimal("42.50"));
        List<TransactionCategorizerPort.CategoryOption> cats =
            List.of(new TransactionCategorizerPort.CategoryOption("shopping", "Shopping"));
        List<TransactionCategorizerPort.Example> examples =
            List.of(new TransactionCategorizerPort.Example("Fnac", "shopping"));
        TransactionCategorizerPort.CategorySuggestion suggestion =
            new TransactionCategorizerPort.CategorySuggestion("shopping", 0.95);
        CategorizationResult delegateResult =
            new CategorizationResult(Optional.of(suggestion), "prompt", "response", 10, 5, 15, 120L, "OK", null);

        when(provider.currentCategorizer()).thenReturn(delegate);
        when(delegate.categorize(input, cats, examples)).thenReturn(delegateResult);

        CategorizationResult result =
            new DynamicTransactionCategorizer(provider).categorize(input, cats, examples);

        assertThat(result).isSameAs(delegateResult);
        assertThat(result.suggestion()).contains(suggestion);
        assertThat(result.status()).isEqualTo("OK");
    }

    // ─── 2. unconfigured_returnsEmpty ────────────────────────────────────────

    @Test
    void unconfigured_returnsEmpty() {
        TransactionCategorizerPort.CategorizationInput input =
            new TransactionCategorizerPort.CategorizationInput("Carrefour", "", new BigDecimal("15.00"));
        List<TransactionCategorizerPort.CategoryOption> cats =
            List.of(new TransactionCategorizerPort.CategoryOption("groceries", "Groceries"));
        List<TransactionCategorizerPort.Example> examples = List.of();

        when(provider.currentCategorizer()).thenReturn(new NoopCategorizer());

        CategorizationResult result =
            new DynamicTransactionCategorizer(provider).categorize(input, cats, examples);

        assertThat(result.suggestion()).isEmpty();
        assertThat(result.status()).isEqualTo("DISABLED");
    }
}
