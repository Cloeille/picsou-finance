package com.picsou.config;

import com.picsou.adapter.DynamicTransactionCategorizer;
import com.picsou.port.TransactionCategorizerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the {@link TransactionCategorizerPort} as a {@link DynamicTransactionCategorizer}, which
 *  resolves the configured provider at call time from {@link AiConfigProvider}. The provider/key/
 *  model are runtime config (Admin → Settings), not Spring AI auto-config — {@code spring.ai.model.chat}
 *  stays {@code none} so no ChatModel is auto-built at boot. */
@Configuration
public class AiCategorizationConfig {

    @Bean
    TransactionCategorizerPort transactionCategorizer(AiConfigProvider aiConfigProvider) {
        return new DynamicTransactionCategorizer(aiConfigProvider);
    }
}
