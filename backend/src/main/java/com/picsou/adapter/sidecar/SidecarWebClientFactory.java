package com.picsou.adapter.sidecar;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.function.Consumer;

/**
 * Builds the {@link WebClient} every connector uses to reach its Python sidecar.
 *
 * <p>The sidecars are the only hop on this stack that carries a bank login,
 * password and one-time code over a socket the backend does not own, so the
 * policy for that hop lives here rather than in five adapters that each grew
 * their own client (issue #119). One place applies, to all of them:
 *
 * <ul>
 *   <li>the base URL is validated at bean-construction time, so a deployment
 *       that would put credentials on a real network never starts
 *       ({@link SidecarBaseUrl});</li>
 *   <li>every request carries the shared secret, so a container that can reach
 *       port 8001 cannot drive the sidecar or replay a captured
 *       {@code sessionState};</li>
 *   <li>the sidecar's own rejection of that secret is turned into a
 *       {@link SidecarAuthenticationException} before any adapter sees it, so a
 *       key mismatch can never be mistaken for the bank rejecting a password.</li>
 * </ul>
 *
 * <p>The secret authenticates the caller; it does not encrypt the hop. See the
 * ADR (docs/decisions/2026-09-17-sidecar-shared-secret-channel.md) for why that
 * is the accepted policy for a co-located sidecar.
 */
@Component
public class SidecarWebClientFactory {

    /** Carries the shared secret. Named, not {@code Authorization}, so no proxy strips or caches on it. */
    public static final String API_KEY_HEADER = "X-Picsou-Sidecar-Key";

    /**
     * The sidecars answer 401 with this challenge when the key is missing or wrong.
     * Matching on the challenge rather than the bare status matters: 401 already
     * means "the bank rejected this" on several of these endpoints, and Amundi
     * answers 403 for a captcha wall.
     */
    public static final String AUTH_CHALLENGE = "Picsou-Sidecar-Key";

    private final String apiKey;

    public SidecarWebClientFactory(@Value("${app.sidecar.api-key:}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "SIDECAR_API_KEY is required: it is the shared secret that authenticates Picsou to "
                    + "its connector sidecars, which carry your bank credentials. Generate one with: "
                    + "openssl rand -base64 32 -- and give the same value to the backend and to every "
                    + "*-auth service.");
        }
        this.apiKey = apiKey.trim();
    }

    public WebClient create(String sidecarName, String baseUrl) {
        return create(sidecarName, baseUrl, builder -> { });
    }

    /**
     * @param customizer applied last, for the one adapter that needs its own codecs
     *                   (DEGIRO decodes money straight to {@code BigDecimal})
     */
    public WebClient create(String sidecarName, String baseUrl, Consumer<WebClient.Builder> customizer) {
        WebClient.Builder builder = WebClient.builder()
            .baseUrl(SidecarBaseUrl.validate(sidecarName, baseUrl))
            .defaultHeader(API_KEY_HEADER, apiKey)
            .filter(rejectOnBadKey(sidecarName));
        customizer.accept(builder);
        return builder.build();
    }

    /**
     * Turns the sidecar's own authentication challenge into a typed failure while the
     * response is still in the pipeline. Doing it here rather than per adapter is what
     * keeps a key mismatch legible: every adapter already lets a {@link com.picsou.exception.SyncException}
     * through untouched, so none of them gets the chance to relabel it as
     * INVALID_CREDENTIALS, SESSION_EXPIRED or a generic outage.
     */
    private static ExchangeFilterFunction rejectOnBadKey(String sidecarName) {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            if (isAuthChallenge(response)) {
                // Drain the body: an undisposed response leaks the connection.
                return response.releaseBody()
                    .then(Mono.error(new SidecarAuthenticationException(sidecarName)));
            }
            return Mono.just(response);
        });
    }

    private static boolean isAuthChallenge(ClientResponse response) {
        if (response.statusCode().value() != 401) {
            return false;
        }
        return response.headers().header("WWW-Authenticate").stream()
            .anyMatch(value -> value.contains(AUTH_CHALLENGE));
    }
}
