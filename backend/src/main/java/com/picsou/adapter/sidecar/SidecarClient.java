package com.picsou.adapter.sidecar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.SidecarErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * The request/error half of a sidecar call, shared by the connectors whose
 * sidecar speaks the strict {@code {"detail": "<CODE>"}} contract.
 *
 * <p>One client replaces what were ten per-bank copies of {@code post} / {@code mapError} /
 * {@code responseCode} / {@code causedByTimeout}, differing only in which error enum they named
 * and how they spelled the bank. Issue #119 calls that duplication out as the reason only one
 * connector ever got URL validation, so the behaviour lives here once and the per-bank part is
 * the {@link SidecarBank} descriptor.
 *
 * <p>A {@code detail} the bank does not declare is treated exactly like an unknown one: logged,
 * then the status decides. Each session table's CHECK constraint lists only its own bank's codes,
 * so an undeclared code must never be turned into a {@link SyncException} code.
 *
 * <p>Trade Republic, DEGIRO and Revolut deliberately stay out: their sidecars do not use the
 * {@code detail}-code contract (DEGIRO relays a bare 401 for an expired session, Trade Republic
 * relays the broker's own status), so they take the shared {@link SidecarWebClientFactory} client
 * and keep their own mapping.
 */
public final class SidecarClient {

    private static final Logger log = LoggerFactory.getLogger(SidecarClient.class);

    private final WebClient client;
    private final ObjectMapper objectMapper;
    private final SidecarBank bank;
    private final SidecarErrorCode attemptExpired;

    /**
     * @param client         the sidecar client from {@link SidecarWebClientFactory}
     * @param bank           the connector this client talks to
     * @param attemptExpired what HTTP 410 means for this client: {@code AUTH_ATTEMPT_EXPIRED} when the
     *                       pending login is gone, {@code SESSION_EXPIRED} when a stored session died
     */
    public SidecarClient(
        WebClient client,
        ObjectMapper objectMapper,
        SidecarBank bank,
        SidecarErrorCode attemptExpired
    ) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.bank = bank;
        this.attemptExpired = attemptExpired;
    }

    /**
     * POSTs {@code body} and decodes one object.
     *
     * @param authenticationFailure the code a bare 401 means for this call (the bank rejected the
     *                              secret the user supplied), or null when 401 carries no such meaning
     */
    public <T> T post(
        String path,
        Object body,
        Class<T> type,
        Duration timeout,
        String message,
        SidecarErrorCode authenticationFailure
    ) {
        try {
            T response = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).retrieve().bodyToMono(type)
                .timeout(timeout).block();
            if (response == null) {
                throw coded(SidecarErrorCode.UPSTREAM_UNAVAILABLE, message, null);
            }
            return response;
        } catch (RuntimeException ex) {
            throw mapError(message, ex, authenticationFailure);
        }
    }

    /**
     * POSTs {@code body} and decodes a non-empty array.
     *
     * <p>An empty array is a failure, not an empty portfolio: these endpoints only
     * answer once the sidecar has a complete snapshot, so nothing back means the
     * scrape fell short and must not be written over real holdings.
     */
    public <T> List<T> postForList(
        String path,
        Object body,
        Class<T[]> arrayType,
        Duration timeout,
        String message,
        SidecarErrorCode emptyCode,
        String emptyMessage
    ) {
        try {
            T[] response = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).retrieve().bodyToMono(arrayType)
                .timeout(timeout).block();
            if (response == null || response.length == 0) {
                throw coded(emptyCode, emptyMessage, null);
            }
            return List.of(response);
        } catch (RuntimeException ex) {
            throw mapError(message, ex, null);
        }
    }

    public SyncException mapError(String message, RuntimeException ex, SidecarErrorCode authenticationFailure) {
        // Covers SidecarAuthenticationException too: a rejected shared secret keeps
        // its own code instead of being relabelled as a bad password or an outage.
        if (ex instanceof SyncException sync) return sync;
        if (causedByTimeout(ex)) {
            log.warn("{}: sidecar request timed out", message);
            return coded(SidecarErrorCode.UPSTREAM_UNAVAILABLE, bank.label() + " took too long to respond. Please try again.", ex);
        }
        if (ex instanceof WebClientResponseException response) {
            SidecarErrorCode upstreamCode = responseCode(response);
            if (upstreamCode != null) {
                return coded(upstreamCode, bank.message(upstreamCode), ex);
            }
            if (response.getStatusCode().value() == 401 && authenticationFailure != null) {
                return coded(authenticationFailure, bank.message(authenticationFailure), ex);
            }
            if (response.getStatusCode().value() == 410) {
                return coded(attemptExpired, bank.message(attemptExpired), ex);
            }
            if (response.getStatusCode().is5xxServerError()) {
                log.warn("{} sidecar returned status {}", bank.label(), response.getStatusCode().value());
                return coded(SidecarErrorCode.UPSTREAM_UNAVAILABLE, bank.message(SidecarErrorCode.UPSTREAM_UNAVAILABLE), ex);
            }
        }
        log.error(message, ex);
        return coded(SidecarErrorCode.UPSTREAM_UNAVAILABLE, message, ex);
    }

    public SyncException coded(SidecarErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    private SidecarErrorCode responseCode(WebClientResponseException response) {
        try {
            JsonNode body = objectMapper.readTree(response.getResponseBodyAsString());
            JsonNode detailNode = body.path("detail");
            if (!detailNode.isTextual() || detailNode.asText().isBlank()) {
                log.debug(
                    "{} error response has no textual detail code (status={})",
                    bank.label(),
                    response.getStatusCode().value()
                );
                return null;
            }
            String detail = detailNode.asText();
            Optional<SidecarErrorCode> declared = bank.parse(detail);
            if (declared.isEmpty()) {
                log.warn("{} sidecar returned unknown error code '{}'", bank.label(), detail);
            }
            return declared.orElse(null);
        } catch (JsonProcessingException ex) {
            log.warn(
                "Could not parse {} sidecar error response (status={})",
                bank.label(),
                response.getStatusCode().value(),
                ex
            );
            return null;
        }
    }

    private static boolean causedByTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
