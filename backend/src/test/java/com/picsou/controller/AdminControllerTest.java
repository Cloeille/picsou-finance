package com.picsou.controller;

import com.picsou.config.AiConfigProvider;
import com.picsou.config.EnableBankingConfigProvider;
import com.picsou.dto.AdminAiRequest;
import com.picsou.dto.AdminEnableBankingRequest;
import com.picsou.dto.AdminSecurityRequest;
import com.picsou.dto.AiCallLogPage;
import com.picsou.dto.AiTestResponse;
import com.picsou.dto.EnableBankingImportRequest;
import com.picsou.dto.EnableBankingKeypairResponse;
import com.picsou.service.AiCallLogService;
import com.picsou.service.EnableBankingKeyPairService;
import com.picsou.service.IntegrationsService;
import com.picsou.service.SetupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.util.List;
import java.util.Optional;

import static com.picsou.service.SetupService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock SetupService setupService;
    @Mock IntegrationsService integrationsService;
    @Mock EnableBankingConfigProvider ebConfigProvider;
    @Mock EnableBankingKeyPairService keyPairService;
    @Mock AiConfigProvider aiConfigProvider;
    @Mock AiCallLogService aiCallLogService;

    @InjectMocks AdminController controller;

    @Test
    void getSettings_returnsAssembledResponse() {
        when(setupService.readSetting(KEY_CORS_ALLOWED_ORIGINS)).thenReturn(Optional.of("https://a.com,https://b.com"));
        when(setupService.readSetting(KEY_SECURE_COOKIES)).thenReturn(Optional.of("true"));
        // EB credentials now come from the resolved provider (DB-then-env), the same
        // source the connector uses — not raw setupService.readSetting calls.
        when(ebConfigProvider.applicationId()).thenReturn(Optional.of("app-id"));
        when(ebConfigProvider.redirectUri()).thenReturn(Optional.of("https://app.com/callback"));
        when(ebConfigProvider.privateKeyPresent()).thenReturn(true);
        for (String key : INTEGRATIONS) {
            when(integrationsService.isEffectivelyEnabled(key)).thenReturn("enablebanking".equals(key));
        }

        var response = controller.getSettings();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.security().allowedOrigins()).containsExactly("https://a.com", "https://b.com");
        assertThat(body.security().secureCookies()).isTrue();
        assertThat(body.enableBanking().applicationId()).isEqualTo("app-id");
        assertThat(body.enableBanking().privateKeyPresent()).isTrue();
        assertThat(body.integrations()).containsEntry("enablebanking", true);
        assertThat(body.integrations()).containsEntry("finary", false);
    }

    @Test
    void getSettings_reportsMissingPrivateKey() {
        when(ebConfigProvider.privateKeyPresent()).thenReturn(false);

        var body = controller.getSettings().getBody();

        assertThat(body).isNotNull();
        assertThat(body.enableBanking().privateKeyPresent()).isFalse();
    }

    @Test
    void generateKeyPair_returnsPublicPem_andFlagsNewlyGenerated() {
        when(keyPairService.exists()).thenReturn(false);
        when(keyPairService.getOrGeneratePublicPem()).thenReturn("PUBLIC-PEM");

        var response = controller.generateEnableBankingKeyPair();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().publicKeyPem()).isEqualTo("PUBLIC-PEM");
        assertThat(response.getBody().regenerated()).isTrue();
    }

    @Test
    void generateKeyPair_existingKey_isNotFlaggedRegenerated() {
        when(keyPairService.exists()).thenReturn(true);
        when(keyPairService.getOrGeneratePublicPem()).thenReturn("EXISTING-PUBLIC");

        var body = controller.generateEnableBankingKeyPair().getBody();

        assertThat(body).isNotNull();
        assertThat(body.regenerated()).isFalse();
    }

    @Test
    void importPrivateKey_valid_returnsDerivedPublic() {
        when(keyPairService.importPrivateKey("PRIV")).thenReturn("DERIVED-PUBLIC");

        var response = controller.importEnableBankingPrivateKey(new EnableBankingImportRequest("PRIV"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new EnableBankingKeypairResponse("DERIVED-PUBLIC", false));
    }

    @Test
    void importPrivateKey_invalid_returns422() {
        when(keyPairService.importPrivateKey("bad")).thenThrow(new IllegalArgumentException("not a PKCS8 pem"));

        var response = controller.importEnableBankingPrivateKey(new EnableBankingImportRequest("bad"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isInstanceOf(ProblemDetail.class);
        assertThat(((ProblemDetail) response.getBody()).getDetail()).isEqualTo("not a PKCS8 pem");
    }

    @Test
    void updateSecurity_delegatesToSetupService() {
        var request = new AdminSecurityRequest(List.of("https://app.com"), true);
        var response = controller.updateSecurity(request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(setupService).writeSecurity(List.of("https://app.com"), true);
    }

    @Test
    void updateEnableBanking_delegatesToSetupService() {
        var request = new AdminEnableBankingRequest("my-app", "https://app.com/cb");
        var response = controller.updateEnableBanking(request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(setupService).writeEnableBankingConfig("my-app", "https://app.com/cb");
    }

    @Test
    void toggleIntegration_enable_callsEnable() {
        var response = controller.toggleIntegration("finary", true);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(integrationsService).enable("finary");
        verify(integrationsService, never()).disable(any());
    }

    @Test
    void toggleIntegration_disable_callsDisable() {
        var response = controller.toggleIntegration("enablebanking", false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(integrationsService).disable("enablebanking");
    }

    // ─── AI provider endpoints ───────────────────────────────────────────────

    @Test
    void updateAi_delegatesToSave() {
        var request = new AdminAiRequest("anthropic", "claude-haiku-4-5", "https://api.anthropic.com", "sk-ant", 4);
        var response = controller.updateAi(request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(aiConfigProvider).save("anthropic", "claude-haiku-4-5", "https://api.anthropic.com", "sk-ant", 4);
    }

    @Test
    void testAi_returnsProviderResult() {
        when(aiConfigProvider.test(any(), any(), any(), any()))
            .thenReturn(new AiTestResponse(true, "Connected."));
        var response = controller.testAi(new AdminAiRequest("anthropic", "m", "u", null, null));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isTrue();
        assertThat(response.getBody().message()).isEqualTo("Connected.");
    }

    @Test
    void getSettings_includesAiBlock() {
        lenient().when(setupService.readSetting(KEY_CORS_ALLOWED_ORIGINS)).thenReturn(Optional.of(""));
        lenient().when(setupService.readSetting(KEY_SECURE_COOKIES)).thenReturn(Optional.empty());
        lenient().when(ebConfigProvider.applicationId()).thenReturn(Optional.empty());
        lenient().when(ebConfigProvider.redirectUri()).thenReturn(Optional.empty());
        lenient().when(ebConfigProvider.privateKeyPresent()).thenReturn(false);
        for (String key : INTEGRATIONS) {
            lenient().when(integrationsService.isEffectivelyEnabled(key)).thenReturn(false);
        }
        when(aiConfigProvider.storedProvider()).thenReturn("anthropic");
        when(aiConfigProvider.storedModel()).thenReturn("m");
        when(aiConfigProvider.storedBaseUrl()).thenReturn("u");
        when(aiConfigProvider.apiKeyPresent()).thenReturn(true);
        when(aiConfigProvider.maxConcurrency()).thenReturn(6);

        var body = controller.getSettings().getBody();

        assertThat(body).isNotNull();
        assertThat(body.ai()).isNotNull();
        assertThat(body.ai().provider()).isEqualTo("anthropic");
        assertThat(body.ai().model()).isEqualTo("m");
        assertThat(body.ai().baseUrl()).isEqualTo("u");
        assertThat(body.ai().apiKeyPresent()).isTrue();
        assertThat(body.ai().maxConcurrency()).isEqualTo(6);
    }

    @Test
    void aiCalls_delegatesToService() {
        var samplePage = new AiCallLogPage(List.of(), 0, 0);
        when(aiCallLogService.list(50, 0)).thenReturn(samplePage);

        var result = controller.aiCalls(50, 0);

        assertThat(result).isEqualTo(samplePage);
        verify(aiCallLogService).list(50, 0);
    }
}
