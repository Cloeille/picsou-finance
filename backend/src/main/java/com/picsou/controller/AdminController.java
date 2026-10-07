package com.picsou.controller;

import com.picsou.config.AiConfigProvider;
import com.picsou.config.EnableBankingConfigProvider;
import com.picsou.dto.AdminAiRequest;
import com.picsou.dto.AdminEnableBankingRequest;
import com.picsou.dto.AdminSecurityRequest;
import com.picsou.dto.AdminSettingsResponse;
import com.picsou.dto.AiCallLogPage;
import com.picsou.dto.AiTestResponse;
import com.picsou.dto.EnableBankingImportRequest;
import com.picsou.dto.EnableBankingKeypairResponse;
import com.picsou.service.AiCallLogService;
import com.picsou.service.EnableBankingCallLogger;
import com.picsou.service.EnableBankingKeyPairService;
import com.picsou.service.IntegrationsService;
import com.picsou.service.SetupService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.picsou.service.SetupService.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final SetupService setupService;
    private final IntegrationsService integrationsService;
    private final EnableBankingConfigProvider ebConfigProvider;
    private final EnableBankingKeyPairService keyPairService;
    private final AiConfigProvider aiConfigProvider;
    private final AiCallLogService aiCallLogService;
    private final EnableBankingCallLogger ebCallLogger;
    private final String envAllowedOrigins;

    public AdminController(SetupService setupService,
                           IntegrationsService integrationsService,
                           EnableBankingConfigProvider ebConfigProvider,
                           EnableBankingKeyPairService keyPairService,
                           AiConfigProvider aiConfigProvider,
                           AiCallLogService aiCallLogService,
                           EnableBankingCallLogger ebCallLogger,
                           @Value("${app.cors.allowed-origins:}") String envAllowedOrigins) {
        this.setupService = setupService;
        this.integrationsService = integrationsService;
        this.ebConfigProvider = ebConfigProvider;
        this.keyPairService = keyPairService;
        this.aiConfigProvider = aiConfigProvider;
        this.aiCallLogService = aiCallLogService;
        this.ebCallLogger = ebCallLogger;
        this.envAllowedOrigins = envAllowedOrigins;
    }

    @GetMapping("/settings")
    public ResponseEntity<AdminSettingsResponse> getSettings() {
        List<String> origins = setupService.readSetting(KEY_CORS_ALLOWED_ORIGINS)
            .map(s -> Arrays.asList(s.split(",")))
            .orElse(List.of());
        boolean secureCookies = setupService.readSetting(KEY_SECURE_COOKIES)
            .map(Boolean::parseBoolean).orElse(false);
        // Read the *resolved* config (DB first, then env-var fallback) — the same
        // source the connector uses — so an install configured purely via .env
        // doesn't show empty fields / a false "not configured" banner.
        String appId = ebConfigProvider.applicationId().orElse("");
        String redirectUri = ebConfigProvider.redirectUri().orElse("");

        // Effective state, not the raw wizard flag: an integration configured
        // purely via .env / compose (and thus live) reports on even though its
        // DB toggle was never flipped by the wizard.
        Map<String, Boolean> integrations = new LinkedHashMap<>();
        for (String name : INTEGRATIONS) {
            integrations.put(name, integrationsService.isEffectivelyEnabled(name));
        }

        AdminSettingsResponse.AiSettings ai = new AdminSettingsResponse.AiSettings(
            aiConfigProvider.storedProvider(),
            aiConfigProvider.storedModel(),
            aiConfigProvider.storedBaseUrl(),
            aiConfigProvider.apiKeyPresent(),
            aiConfigProvider.maxConcurrency()
        );

        return ResponseEntity.ok(new AdminSettingsResponse(
            new AdminSettingsResponse.SecuritySettings(origins, secureCookies),
            new AdminSettingsResponse.EnableBankingSettings(
                appId, redirectUri, ebConfigProvider.privateKeyPresent()),
            integrations,
            ai
        ));
    }

    @PutMapping("/settings/security")
    public ResponseEntity<Void> updateSecurity(@Valid @RequestBody AdminSecurityRequest request) {
        setupService.writeSecurity(request.allowedOrigins(), request.secureCookies());
        return ResponseEntity.noContent().build();
    }

    /**
     * Overwrites the persisted CORS origins with the live value of the
     * {@code ALLOWED_ORIGINS} env var. Useful when the operator has changed
     * the public URL (e.g. moved from http to https) but the wizard already
     * locked a stale value into {@code app_setting}.
     */
    @PostMapping("/settings/cors/reload-from-env")
    public ResponseEntity<Map<String, Object>> reloadCorsFromEnv() {
        setupService.reloadCorsFromEnv(envAllowedOrigins);
        List<String> origins = Arrays.stream(envAllowedOrigins.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return ResponseEntity.ok(Map.of("allowedOrigins", origins));
    }

    @PutMapping("/settings/enablebanking")
    public ResponseEntity<Void> updateEnableBanking(@Valid @RequestBody AdminEnableBankingRequest request) {
        setupService.writeEnableBankingConfig(request.applicationId(), request.redirectUri());
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns the current public key PEM, generating a new pair on disk only if
     * none exists yet. Idempotent — re-calling never invalidates a public key
     * already uploaded to the Enable Banking dashboard. Mirrors the setup-wizard
     * endpoint but without the "setup not complete" guard, so an operator can
     * recover a missing key after first-run setup is done.
     */
    @PostMapping("/settings/enablebanking/keypair")
    public ResponseEntity<EnableBankingKeypairResponse> generateEnableBankingKeyPair() {
        boolean existedBefore = keyPairService.exists();
        String publicPem = keyPairService.getOrGeneratePublicPem();
        return ResponseEntity.ok(new EnableBankingKeypairResponse(publicPem, !existedBefore));
    }

    @PostMapping("/settings/enablebanking/keypair/import")
    public ResponseEntity<?> importEnableBankingPrivateKey(@Valid @RequestBody EnableBankingImportRequest request) {
        try {
            String publicPem = keyPairService.importPrivateKey(request.privatePem());
            return ResponseEntity.ok(new EnableBankingKeypairResponse(publicPem, false));
        } catch (IllegalArgumentException ex) {
            ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
            pd.setDetail(ex.getMessage());
            return ResponseEntity.unprocessableEntity().body(pd);
        }
    }

    @PatchMapping("/settings/integrations/{key}")
    public ResponseEntity<Void> toggleIntegration(@PathVariable String key, @RequestParam boolean enabled) {
        if (enabled) integrationsService.enable(key);
        else integrationsService.disable(key);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/settings/ai")
    public ResponseEntity<Void> updateAi(@Valid @RequestBody AdminAiRequest request) {
        aiConfigProvider.save(request.provider(), request.model(), request.baseUrl(), request.apiKey(), request.maxConcurrency());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/settings/ai/test")
    public ResponseEntity<AiTestResponse> testAi(@Valid @RequestBody AdminAiRequest request) {
        return ResponseEntity.ok(aiConfigProvider.test(request.provider(), request.model(), request.baseUrl(), request.apiKey()));
    }

    @GetMapping("/ai-calls")
    public AiCallLogPage aiCalls(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return aiCallLogService.list(limit, offset);
    }

    @GetMapping("/enablebanking/call-log")
    public List<EnableBankingCallLogger.CallEntry> ebCallLog() {
        return ebCallLogger.entries();
    }

    @DeleteMapping("/enablebanking/call-log")
    public ResponseEntity<Void> clearEbCallLog() {
        ebCallLogger.clear();
        return ResponseEntity.noContent().build();
    }
}
