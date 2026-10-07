package com.picsou.dto;
import java.util.List;
import java.util.Map;
public record AdminSettingsResponse(
    SecuritySettings security,
    EnableBankingSettings enableBanking,
    Map<String, Boolean> integrations,
    AiSettings ai
) {
    public record SecuritySettings(List<String> allowedOrigins, boolean secureCookies) {}
    public record EnableBankingSettings(String applicationId, String redirectUri, boolean privateKeyPresent) {}
    public record AiSettings(String provider, String model, String baseUrl, boolean apiKeyPresent, int maxConcurrency) {}
}
