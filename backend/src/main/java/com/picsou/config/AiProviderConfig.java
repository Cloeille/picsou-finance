package com.picsou.config;
import com.picsou.model.AiProvider;

public record AiProviderConfig(AiProvider provider, String apiKey, String model, String baseUrl) {
    public String effectiveBaseUrl() {
        return baseUrl == null || baseUrl.isBlank() ? provider.defaultBaseUrl() : baseUrl.trim();
    }
    public String effectiveModel() {
        return model == null || model.isBlank() ? provider.defaultModel() : model.trim();
    }
}
