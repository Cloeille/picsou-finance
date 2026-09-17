package com.picsou.adapter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.exception.DegiroSessionExpiredException;
import com.picsou.exception.SyncException;
import com.picsou.port.DegiroPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Component
public class DegiroAdapter implements DegiroPort {

    private static final Logger log = LoggerFactory.getLogger(DegiroAdapter.class);

    private static final Duration SIDECAR_TIMEOUT = Duration.ofSeconds(30);
    private static final String NO_RESPONSE = "No response from the DEGIRO service. Please try again later.";

    private final WebClient sidecarClient;

    @Autowired
    public DegiroAdapter(
        SidecarWebClientFactory clients,
        ObjectMapper objectMapper,
        @Value("${app.degiro-auth.url:http://degiro-auth:8001}") String degiroAuthUrl
    ) {
        // Every number this sidecar returns is money or a share quantity, and they feed
        // the quantity * currentPrice sum behind currentBalance and the daily snapshot.
        // Jackson maps JSON floats to DoubleNode by default, so the amounts would reach
        // BigDecimal only after a round-trip through binary floating point; this decoder
        // parses them straight into DecimalNode so decimal() below stays exact.
        ObjectMapper exactDecimals = objectMapper.copy()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

        this.sidecarClient = clients.create(
            "DEGIRO",
            degiroAuthUrl,
            builder -> builder.codecs(
                c -> c.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(exactDecimals)))
        );
    }

    DegiroAdapter(WebClient sidecarClient) {
        this.sidecarClient = sidecarClient;
    }

    @Override
    public InitiateResult initiateAuth(String username, String password) {
        log.info("Delegating DEGIRO auth initiation to degiro-auth sidecar");

        JsonNode response = sidecarClient.post()
            .uri("/initiate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("username", username, "password", password))
            .retrieve()
            .bodyToMono(JsonNode.class)
            // timeout() must sit upstream of the error handlers: Reactor emits its
            // TimeoutException downstream, so with the opposite order a hung sidecar
            // surfaced a raw TimeoutException out of blockOptional() instead of a SyncException.
            .timeout(SIDECAR_TIMEOUT)
            .onErrorResume(WebClientResponseException.class, ex -> {
                log.error("degiro-auth /initiate failed ({}) : {}", ex.getStatusCode(), ex.getResponseBodyAsString());
                return Mono.error(new SyncException(
                    "DEGIRO authentication failed. Please check your credentials and try again."));
            })
            .onErrorMap(TimeoutException.class, ex -> new SyncException(NO_RESPONSE, ex))
            .blockOptional()
            .orElseThrow(() -> new SyncException(NO_RESPONSE));

        String processId = response.path("processId").asText(null);
        if (processId == null || processId.isBlank()) {
            throw new SyncException("DEGIRO did not return a valid session. Please try again.");
        }

        boolean totpRequired = response.path("totpRequired").asBoolean(false);
        if (!totpRequired) {
            String blob = response.path("sessionBlob").asText(null);
            if (blob == null || blob.isBlank()) {
                throw new SyncException("DEGIRO authentication did not complete. Please try again.");
            }
            return new InitiateResult(processId, false, blob);
        }

        return new InitiateResult(processId, true, null);
    }

    @Override
    public String completeAuth(String processId, String code) {
        log.info("Delegating DEGIRO TOTP completion to degiro-auth sidecar, processId={}", processId);

        JsonNode response = sidecarClient.post()
            .uri("/complete")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("processId", processId, "code", code))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(SIDECAR_TIMEOUT)
            .onErrorResume(WebClientResponseException.class, ex -> {
                log.error("degiro-auth /complete failed ({}) : {}", ex.getStatusCode(), ex.getResponseBodyAsString());
                return Mono.error(new SyncException(
                    "The verification code is invalid or has expired. Please request a new one."));
            })
            .onErrorMap(TimeoutException.class, ex -> new SyncException(NO_RESPONSE, ex))
            .blockOptional()
            .orElseThrow(() -> new SyncException(NO_RESPONSE));

        String blob = response.path("sessionBlob").asText(null);
        if (blob == null || blob.isBlank()) {
            throw new SyncException("DEGIRO verification did not complete. Please try again.");
        }
        return blob;
    }

    @Override
    public DegiroPortfolioData fetchPortfolio(String sessionBlob) {
        log.info("Fetching DEGIRO portfolio via degiro-auth sidecar");

        JsonNode response = sidecarClient.post()
            .uri("/portfolio")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("sessionBlob", sessionBlob))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(SIDECAR_TIMEOUT)
            .onErrorResume(WebClientResponseException.class, ex -> {
                if (ex.getStatusCode().value() == 401) {
                    return Mono.error(new DegiroSessionExpiredException());
                }
                log.error("degiro-auth /portfolio failed ({}) : {}", ex.getStatusCode(), ex.getResponseBodyAsString());
                return Mono.error(new SyncException(
                    "Could not fetch your DEGIRO portfolio. Please try again later."));
            })
            .onErrorMap(TimeoutException.class, ex -> new SyncException(NO_RESPONSE, ex))
            .blockOptional()
            .orElseThrow(() -> new SyncException(NO_RESPONSE));

        BigDecimal cashEur = decimal(response.path("cashEur"));

        List<DegiroPosition> positions = new ArrayList<>();
        for (JsonNode p : response.path("positions")) {
            positions.add(new DegiroPosition(
                nullIfBlank(p.path("isin").asText()),
                p.path("symbol").asText(),
                p.path("name").asText(),
                decimal(p.path("quantity")),
                decimal(p.path("buyingPrice")),
                decimal(p.path("currentPrice"))
            ));
        }

        log.info("DEGIRO: fetched portfolio with {} positions", positions.size());
        return new DegiroPortfolioData(cashEur, positions);
    }

    private String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Reads a monetary/quantity field as an exact decimal, defaulting to zero when the
     * field is missing or non-numeric (Jackson's own {@code decimalValue()} contract).
     *
     * <p>Deliberately not {@code BigDecimal.valueOf(node.asDouble(0))}: that routes every
     * amount through {@code double}, and these values feed straight into the
     * {@code quantity * currentPrice} sum behind {@code currentBalance} and the daily
     * snapshot. Going through Jackson's decimal accessor keeps whatever precision the
     * sidecar actually sent instead of a binary-float approximation of it.
     */
    private static BigDecimal decimal(JsonNode node) {
        return node.decimalValue();
    }
}
