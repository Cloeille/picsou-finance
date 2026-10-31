package com.picsou.model;
import java.util.Locale;
import java.util.Optional;

/** A configurable AI chat provider for transaction categorization. OpenRouter reuses the
 *  OpenAI-compatible client with a different base URL. */
public enum AiProvider {
    OPENAI("https://api.openai.com", "gpt-4o-mini", true),
    OPENROUTER("https://openrouter.ai/api", "anthropic/claude-3.5-haiku", true),
    ANTHROPIC("https://api.anthropic.com", "claude-haiku-4-5", true),
    OLLAMA("http://ollama:11434", "qwen3:0.6b", false);

    private final String defaultBaseUrl;
    private final String defaultModel;
    private final boolean keyRequired;

    AiProvider(String defaultBaseUrl, String defaultModel, boolean keyRequired) {
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
        this.keyRequired = keyRequired;
    }
    public String defaultBaseUrl() { return defaultBaseUrl; }
    public String defaultModel() { return defaultModel; }
    public boolean keyRequired() { return keyRequired; }

    public static Optional<AiProvider> fromKey(String key) {
        if (key == null || key.isBlank() || key.equalsIgnoreCase("none")) return Optional.empty();
        try { return Optional.of(valueOf(key.trim().toUpperCase(Locale.ROOT))); }
        catch (IllegalArgumentException e) { return Optional.empty(); }
    }
}
