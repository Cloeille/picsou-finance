package com.picsou.config;
import com.picsou.model.AiProvider;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AiProviderConfigTest {
    @Test void blankFieldsFallBackToProviderDefaults() {
        var c = new AiProviderConfig(AiProvider.OPENAI, "k", "", "  ");
        assertThat(c.effectiveBaseUrl()).isEqualTo("https://api.openai.com");
        assertThat(c.effectiveModel()).isEqualTo("gpt-4o-mini");
    }
    @Test void explicitValuesWin() {
        var c = new AiProviderConfig(AiProvider.OPENROUTER, "k", "x/y", "https://h");
        assertThat(c.effectiveBaseUrl()).isEqualTo("https://h");
        assertThat(c.effectiveModel()).isEqualTo("x/y");
    }
}
