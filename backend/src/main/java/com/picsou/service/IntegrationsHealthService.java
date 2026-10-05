package com.picsou.service;

import com.picsou.config.EnableBankingConfigProvider;
import com.picsou.dto.BoursoBankHealthResponse;
import com.picsou.dto.EnableBankingTestResponse;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Live-tests Picsou's integrations for the setup wizard:
 *
 * <ul>
 *   <li><strong>Enable Banking</strong> — signs a JWT with the stored
 *       private key, calls {@code GET /aspsps} (cheap, read-only), and
 *       maps errors to stable machine-readable codes for the UI.</li>
 *   <li><strong>BoursoBank sidecar</strong> — pings the sidecar's
 *       {@code /health} endpoint with a short timeout so the wizard
 *       can give a quick "ready ✓ / down ✗" signal.</li>
 * </ul>
 */
@Service
public class IntegrationsHealthService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationsHealthService.class);
    private static final Duration EB_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration BOURSO_TIMEOUT = Duration.ofSeconds(3);

    private final EnableBankingConfigProvider configProvider;
    private final SetupService setupService;
    private final WebClient ebClient;
    private final String envBoursoUrl;

    public IntegrationsHealthService(
        EnableBankingConfigProvider configProvider,
        SetupService setupService,
        @Value("${app.enablebanking.base-url:https://api.enablebanking.com}") String ebBaseUrl,
        @Value("${app.bourso-auth.url:http://bourso-auth:8001}") String envBoursoUrl
    ) {
        this.configProvider = configProvider;
        this.setupService = setupService;
        this.ebClient = WebClient.builder().baseUrl(ebBaseUrl).build();
        this.envBoursoUrl = envBoursoUrl;
    }

    public EnableBankingTestResponse testEnableBanking() {
        Optional<String> appId = configProvider.applicationId();
        Optional<String> keyId = configProvider.keyId();
        Optional<PrivateKey> privateKey = configProvider.privateKey();

        if (appId.isEmpty()) {
            return EnableBankingTestResponse.failure("invalid_application_id",
                "Application ID is missing. Fill it in on the previous step.");
        }
        if (keyId.isEmpty()) {
            return EnableBankingTestResponse.failure("invalid_key_id",
                "Key ID is missing. Fill it in on the previous step.");
        }
        if (privateKey.isEmpty()) {
            return EnableBankingTestResponse.failure("public_key_not_uploaded",
                "No private key on the server yet. Go back one step to generate the key pair.");
        }

        try {
            String jwt = buildJwt(appId.get(), keyId.get(), privateKey.get());
            ebClient.get()
                .uri(uri -> uri.path("/aspsps").queryParam("country", "FR").build())
                .header("Authorization", "Bearer " + jwt)
                .retrieve()
                .toBodilessEntity()
                .timeout(EB_TIMEOUT)
                .block();
            log.info("setup.integration.enablebanking.test ok");
            return EnableBankingTestResponse.success();
        } catch (WebClientResponseException.Unauthorized ex) {
            log.info("setup.integration.enablebanking.test failed code=invalid_key_id");
            return EnableBankingTestResponse.failure("invalid_key_id",
                "Enable Banking rejected the JWT. Double-check your Application ID and Key ID, " +
                "and make sure the public key has been uploaded to the Enable Banking dashboard.");
        } catch (WebClientResponseException.Forbidden ex) {
            return EnableBankingTestResponse.failure("public_key_not_uploaded",
                "Your public key does not match any key on the Enable Banking dashboard. " +
                "Go back one step, copy the public key, and paste it in your Enable Banking app.");
        } catch (WebClientResponseException ex) {
            log.warn("setup.integration.enablebanking.test http_error status={}", ex.getStatusCode());
            return EnableBankingTestResponse.failure("unknown",
                "Enable Banking returned " + ex.getStatusCode() + ". " + ex.getStatusText());
        } catch (Exception ex) {
            log.warn("setup.integration.enablebanking.test network_error", ex);
            return EnableBankingTestResponse.failure("network",
                "Could not reach Enable Banking. Check your network and retry.");
        }
    }

    public BoursoBankHealthResponse checkBoursoBankSidecar() {
        String url = setupService.readSetting(SetupService.KEY_BOURSO_AUTH_URL)
            .filter(s -> !s.isBlank())
            .orElse(envBoursoUrl);

        try {
            WebClient.builder().baseUrl(url).build()
                .get().uri("/health")
                .retrieve()
                .toBodilessEntity()
                .timeout(BOURSO_TIMEOUT)
                .block();
            return new BoursoBankHealthResponse(true, url, null);
        } catch (Exception ex) {
            log.info("setup.integration.boursobank.health_failed url={} err={}", url, ex.getMessage());
            return new BoursoBankHealthResponse(false, url,
                "Sidecar at " + url + " did not respond within " + BOURSO_TIMEOUT.toSeconds() +
                "s. Make sure the bourso-auth container is running, or override the URL below.");
        }
    }

    private static String buildJwt(String appId, String keyId, PrivateKey key) {
        Instant now = Instant.now();
        return Jwts.builder()
            .header().keyId(keyId).and()
            .issuer(appId)
            .claim("aud", "api.enablebanking.com")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(60)))
            .signWith(key, Jwts.SIG.RS256)
            .compact();
    }
}
