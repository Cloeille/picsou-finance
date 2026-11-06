package com.picsou.controller;

import com.picsou.dto.BoursoBankHealthResponse;
import com.picsou.config.SsoProperties;
import com.picsou.exception.SignInMethodException;
import com.picsou.dto.CryptoKeyGenerateResponse;
import com.picsou.dto.EnableBankingConfigRequest;
import com.picsou.dto.EnableBankingKeypairResponse;
import com.picsou.dto.EnableBankingTestResponse;
import com.picsou.dto.SetupAdminRequest;
import com.picsou.dto.SetupAdminResponse;
import com.picsou.dto.SetupSecurityRequest;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.service.CryptoKeyGeneratorService;
import com.picsou.service.EnableBankingKeyPairService;
import com.picsou.service.IntegrationsHealthService;
import com.picsou.service.IntegrationsService;
import com.picsou.service.SetupAuditService;
import com.picsou.service.SetupService;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SetupControllerTest {

    @Mock SetupService setupService;
    @Mock IntegrationsService integrationsService;
    @Mock IntegrationsHealthService healthService;
    @Mock EnableBankingKeyPairService keyPairService;
    @Mock CryptoKeyGeneratorService cryptoKeyService;
    @Mock SetupAuditService auditService;
    @Spy SsoProperties ssoProperties = new SsoProperties();

    private Map<String, Bucket> buckets;
    private SetupController controller;

    @BeforeEach
    void setUp() {
        buckets = new ConcurrentHashMap<>();
        controller = new SetupController(
            setupService, integrationsService, healthService, keyPairService,
            cryptoKeyService, auditService, ssoProperties, buckets
        );
    }

    @Test
    void seedAdmin_hashesPasswordAndReturnsResponse() {
        when(setupService.hashPassword("correct horse battery")).thenReturn("$2a$12$hashed");
        when(setupService.seedAdmin(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(user("alice", "Alice"));

        ResponseEntity<?> response = controller.seedAdmin(
            new SetupAdminRequest("alice", "correct horse battery", "Alice", "#ff0000"),
            request("10.0.0.1")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isInstanceOf(SetupAdminResponse.class);
        SetupAdminResponse body = (SetupAdminResponse) response.getBody();
        assertThat(body.username()).isEqualTo("alice");
        assertThat(body.displayName()).isEqualTo("Alice");
        verify(setupService).seedAdmin("alice", "$2a$12$hashed", "Alice", "#ff0000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://idp.test", "not-a-url"})
    void seedAdmin_refusesPasswordBootstrapWhenAdminGroupIsSetEvenWithInvalidIssuer(String issuer) {
        ssoProperties.setIssuerUri(issuer);
        ssoProperties.setClientId("picsou");
        ssoProperties.setAdminGroup("picsou-admin");

        assertThatThrownBy(() -> controller.seedAdmin(
            new SetupAdminRequest("alice", "password", "Alice", null), request("10.0.0.1")))
            .isInstanceOfSatisfying(SignInMethodException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(error.getCode()).isEqualTo("SSO_ADMIN_REQUIRED");
            });

        org.mockito.Mockito.verifyNoInteractions(setupService, auditService);
    }

    @Test
    void seedAdmin_returns410_whenSetupAlreadyComplete() {
        when(setupService.isComplete()).thenReturn(true);

        assertThatThrownBy(() -> controller.seedAdmin(
            new SetupAdminRequest("alice", "password", "Alice", null),
            request("10.0.0.1")
        ))
            .isInstanceOf(ResponseStatusException.class)
            .matches(ex -> ((ResponseStatusException) ex).getStatusCode() == HttpStatus.GONE);

        verify(setupService, never()).seedAdmin(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void writeSecurity_delegatesToService() {
        ResponseEntity<?> response = controller.writeSecurity(
            new SetupSecurityRequest(List.of("https://picsou.example.com"), true),
            request("10.0.0.1")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(setupService).writeSecurity(List.of("https://picsou.example.com"), true);
    }

    @Test
    void writeSecurity_returns410_whenSetupAlreadyComplete() {
        when(setupService.isComplete()).thenReturn(true);

        assertThatThrownBy(() -> controller.writeSecurity(
            new SetupSecurityRequest(List.of("https://x"), true),
            request("10.0.0.1")
        )).isInstanceOf(ResponseStatusException.class);

        verify(setupService, never()).writeSecurity(any(), anyBoolean());
    }

    @Test
    void generateEnableBankingKeyPair_flagsFirstCallAsRegenerated() {
        when(keyPairService.exists()).thenReturn(false, true);
        when(keyPairService.getOrGeneratePublicPem()).thenReturn("-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n");

        ResponseEntity<?> response = controller.generateEnableBankingKeyPair(request("10.0.0.1"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        EnableBankingKeypairResponse body = (EnableBankingKeypairResponse) response.getBody();
        assertThat(body.regenerated()).isTrue();
        assertThat(body.publicKeyPem()).contains("BEGIN PUBLIC KEY");
    }

    @Test
    void generateEnableBankingKeyPair_returnsSamePemWithoutRegenerateFlag_onSubsequentCalls() {
        when(keyPairService.exists()).thenReturn(true);
        when(keyPairService.getOrGeneratePublicPem()).thenReturn("pem");

        ResponseEntity<?> response = controller.generateEnableBankingKeyPair(request("10.0.0.2"));

        EnableBankingKeypairResponse body = (EnableBankingKeypairResponse) response.getBody();
        assertThat(body.regenerated()).isFalse();
    }

    @Test
    void testEnableBanking_enablesIntegration_onSuccess() {
        when(healthService.testEnableBanking()).thenReturn(EnableBankingTestResponse.success());

        ResponseEntity<EnableBankingTestResponse> response =
            controller.testEnableBanking(request("10.0.0.1"));

        assertThat(response.getBody().ok()).isTrue();
        verify(integrationsService).enable("enablebanking");
    }

    @Test
    void testEnableBanking_doesNotEnable_onFailure() {
        when(healthService.testEnableBanking()).thenReturn(
            EnableBankingTestResponse.failure("invalid_key_id", "bad key"));

        ResponseEntity<EnableBankingTestResponse> response =
            controller.testEnableBanking(request("10.0.0.1"));

        assertThat(response.getBody().ok()).isFalse();
        assertThat(response.getBody().code()).isEqualTo("invalid_key_id");
        verify(integrationsService, never()).enable(anyString());
    }

    @Test
    void boursoBankHealth_enablesIntegration_onSuccess() {
        when(healthService.checkBoursoBankSidecar()).thenReturn(
            new BoursoBankHealthResponse(true, "http://bourso-auth:8001", null));

        ResponseEntity<BoursoBankHealthResponse> response =
            controller.boursoBankHealth(request("10.0.0.1"));

        assertThat(response.getBody().ok()).isTrue();
        verify(integrationsService).enable("boursobank");
    }

    @Test
    void acknowledge_enablesTradeRepublicAndFinary_rejectsOthers() {
        controller.acknowledge("traderepublic", request("10.0.0.1"));
        controller.acknowledge("finary", request("10.0.0.1"));
        verify(integrationsService).enable("traderepublic");
        verify(integrationsService).enable("finary");

        assertThatThrownBy(() -> controller.acknowledge("crypto", request("10.0.0.1")))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void writeEnableBankingConfig_delegatesToService() {
        ResponseEntity<?> response = controller.writeEnableBankingConfig(
            new EnableBankingConfigRequest(
                "12345678-1234-1234-1234-1234567890ab",
                "https://picsou.example.com/sync/callback"
            ),
            request("10.0.0.1")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(setupService).writeEnableBankingConfig(
            "12345678-1234-1234-1234-1234567890ab",
            "https://picsou.example.com/sync/callback"
        );
    }

    @Test
    void generateCryptoKey_flagsExistingKeyAndEnablesIntegration() {
        when(cryptoKeyService.exists()).thenReturn(true);
        when(cryptoKeyService.keyLocation()).thenReturn("CRYPTO_ENCRYPTION_KEY");

        ResponseEntity<CryptoKeyGenerateResponse> response =
            controller.generateCryptoKey(request("10.0.0.1"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        CryptoKeyGenerateResponse body = response.getBody();
        assertThat(body.existed()).isTrue();
        assertThat(body.path()).isEqualTo("CRYPTO_ENCRYPTION_KEY");
        verify(cryptoKeyService).ensureKey();
        verify(integrationsService).enable("crypto");
    }

    @Test
    void generateCryptoKey_reportsFreshlyGeneratedKey() {
        when(cryptoKeyService.exists()).thenReturn(false);
        when(cryptoKeyService.keyLocation()).thenReturn("/data/.secrets/crypto_key");

        ResponseEntity<CryptoKeyGenerateResponse> response =
            controller.generateCryptoKey(request("10.0.0.1"));

        assertThat(response.getBody().existed()).isFalse();
        verify(cryptoKeyService).ensureKey();
    }

    @Test
    void complete_marksSetupAsCompleteAndAudits() {
        ResponseEntity<?> response = controller.complete(request("10.0.0.1"));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(setupService).markComplete();
        verify(auditService).record(
            org.mockito.ArgumentMatchers.eq("setup.completed"),
            org.mockito.ArgumentMatchers.isNull(),
            any(),
            org.mockito.ArgumentMatchers.isNull()
        );
    }

    @Test
    void complete_returns410_whenAlreadyComplete() {
        when(setupService.isComplete()).thenReturn(true);

        assertThatThrownBy(() -> controller.complete(request("10.0.0.1")))
            .isInstanceOf(ResponseStatusException.class)
            .matches(ex -> ((ResponseStatusException) ex).getStatusCode() == HttpStatus.GONE);

        verify(setupService, never()).markComplete();
    }

    @Test
    void rateLimit_returns429_afterBucketIsDrained() {
        // Pre-drain the bucket for this IP.
        Bucket empty = Bucket.builder()
            .addLimit(Bandwidth.builder().capacity(1).refillIntervally(1, Duration.ofMinutes(1)).build())
            .build();
        empty.tryConsume(1);
        buckets.put("10.0.0.5", empty);

        ResponseEntity<?> response = controller.writeSecurity(
            new SetupSecurityRequest(List.of("https://x"), true),
            request("10.0.0.5")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody()).isInstanceOf(ProblemDetail.class);
        verify(setupService, never()).writeSecurity(any(), anyBoolean());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static HttpServletRequest request(String ip) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn(ip);
        return req;
    }

    private static AppUser user(String username, String displayName) {
        FamilyMember member = FamilyMember.builder().displayName(displayName).build();
        return AppUser.builder().username(username).member(member).build();
    }
}
