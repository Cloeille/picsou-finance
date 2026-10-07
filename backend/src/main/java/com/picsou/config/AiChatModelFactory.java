package com.picsou.config;
import com.picsou.model.AiProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;
import java.util.Optional;

/** Builds a Spring AI {@link ChatModel} at runtime from {@link AiProviderConfig}. We construct the
 *  models by hand (auto-config is disabled via spring.ai.model.chat=none) so the admin panel can
 *  swap providers without a restart. OpenAI and OpenRouter share the OpenAI-compatible client. */
@Component
public class AiChatModelFactory {
    private static final Logger log = LoggerFactory.getLogger(AiChatModelFactory.class);

    public Optional<ChatModel> build(AiProviderConfig cfg) {
        if (cfg == null) return Optional.empty();
        AiProvider p = cfg.provider();
        String key = cfg.apiKey();
        if (p.keyRequired() && (key == null || key.isBlank())) return Optional.empty();
        try {
            return switch (p) {
                case OPENAI, OPENROUTER -> {
                    OpenAiApi api = OpenAiApi.builder()
                        .baseUrl(cfg.effectiveBaseUrl()).apiKey(key).build();
                    yield Optional.of(OpenAiChatModel.builder().openAiApi(api)
                        .defaultOptions(OpenAiChatOptions.builder().model(cfg.effectiveModel()).build())
                        .build());
                }
                case ANTHROPIC -> {
                    AnthropicApi api = AnthropicApi.builder()
                        .baseUrl(cfg.effectiveBaseUrl()).apiKey(key).build();
                    yield Optional.of(AnthropicChatModel.builder().anthropicApi(api)
                        .defaultOptions(AnthropicChatOptions.builder().model(cfg.effectiveModel()).build())
                        .build());
                }
                case OLLAMA -> {
                    OllamaApi api = OllamaApi.builder().baseUrl(cfg.effectiveBaseUrl()).build();
                    yield Optional.of(OllamaChatModel.builder().ollamaApi(api)
                        .defaultOptions(OllamaOptions.builder().model(cfg.effectiveModel()).build())
                        .build());
                }
            };
        } catch (Exception e) {
            log.warn("Failed to build ChatModel for provider {}: {}", p, e.getMessage());
            return Optional.empty();
        }
    }
}
