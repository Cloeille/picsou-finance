package com.picsou.model;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AiProviderTest {
    @Test void fromKey_parsesCaseInsensitively() {
        assertThat(AiProvider.fromKey("anthropic")).contains(AiProvider.ANTHROPIC);
        assertThat(AiProvider.fromKey("OpenRouter")).contains(AiProvider.OPENROUTER);
    }
    @Test void fromKey_blankNoneUnknown_empty() {
        assertThat(AiProvider.fromKey("")).isEmpty();
        assertThat(AiProvider.fromKey(null)).isEmpty();
        assertThat(AiProvider.fromKey("none")).isEmpty();
        assertThat(AiProvider.fromKey("bogus")).isEmpty();
    }
    @Test void defaults_areSet() {
        assertThat(AiProvider.OLLAMA.defaultBaseUrl()).isEqualTo("http://ollama:11434");
        assertThat(AiProvider.OLLAMA.keyRequired()).isFalse();
        assertThat(AiProvider.ANTHROPIC.keyRequired()).isTrue();
        assertThat(AiProvider.OPENROUTER.defaultBaseUrl()).isEqualTo("https://openrouter.ai/api");
    }
}
