package com.picsou.config;
import com.picsou.model.AiProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import static org.assertj.core.api.Assertions.assertThat;

class AiChatModelFactoryTest {
    private final AiChatModelFactory factory = new AiChatModelFactory();

    @Test void openRouterBuildsOpenAiModel() {
        var m = factory.build(new AiProviderConfig(AiProvider.OPENROUTER, "sk-or-x", "", ""));
        assertThat(m).isPresent();
        assertThat(m.get()).isInstanceOf(OpenAiChatModel.class);
    }
    @Test void anthropicBuildsAnthropicModel() {
        assertThat(factory.build(new AiProviderConfig(AiProvider.ANTHROPIC, "sk-ant", "", "")).get())
            .isInstanceOf(AnthropicChatModel.class);
    }
    @Test void ollamaBuildsOllamaModelWithoutKey() {
        assertThat(factory.build(new AiProviderConfig(AiProvider.OLLAMA, null, "", "")).get())
            .isInstanceOf(OllamaChatModel.class);
    }
    @Test void missingRequiredKeyReturnsEmpty() {
        assertThat(factory.build(new AiProviderConfig(AiProvider.OPENAI, " ", "", ""))).isEmpty();
    }
}
