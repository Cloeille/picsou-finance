package com.picsou.adapter.sidecar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * The request/error half of a sidecar call, shared by the connectors whose
 * sidecar speaks the strict {@code {"detail": "<CODE>"}} contract.
 *
 * <p>Amundi, BoursoBank and Bourse Direct had three byte-identical copies of
 * {@code post} / {@code mapError} / {@code responseCode} / {@code causedByTimeout},
 * differing only in which error enum they named and how they spelled the bank.
 * Issue #119 calls that duplication out as the reason only one connector ever got
 * URL validation, so the behaviour lives here once, parameterized by the enum.
 *
 * <p>Trade Republic and DEGIRO deliberately stay out: their sidecars do not use
 * the {@code detail}-code contract (DEGIRO relays a bare 401 for an expired
 * session, Trade Republic relays the broker's own status), so they take the
 * shared {@link SidecarWebClientFactory} client and keep their own mapping.
 *
 * @param <E> the connector's error-code enum, whose constant names are the
 *            {@code detail} values its sidecar emits
 */
public final class SidecarErrorTranslator<E extends Enum<E>> {

    private static final Logger log = LoggerFactory.getLogger(SidecarErrorTranslator.class);

    private final WebClient client;
    private final ObjectMapper objectMapper;
    private final Class<E> codeType;
    private final String label;
    private final E unavailable;
    private final E attemptExpired;
    private final Function<E, String> friendlyMessage;

    /**
     * @param client          the sidecar client from {@link SidecarWebClientFactory}
     * @param label           how the bank is named to the user ("BoursoBank", "Bourse Direct")
     * @param unavailable     the enum's "upstream is down" constant
     * @param attemptExpired  the enum's "this auth attempt is gone" constant, mapped from HTTP 410
     * @param friendlyMessage the connector's own user-facing wording per code
     */
    public SidecarErrorTranslator(
        WebClient client,
        ObjectMapper objectMapper,
        Class<E> codeType,
        String label,
        E unavailable,
        E attemptExpired,
        Function<E, String> friendlyMessage
    ) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.codeType = codeType;
        this.label = label;
        this.unavailable = unavailable;
        this.attemptExpired = attemptExpired;
        this.friendlyMessage = friendlyMessage;
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
        E authenticationFailure
    ) {
        try {
            T response = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).retrieve().bodyToMono(type)
                .timeout(timeout).block();
            if (response == null) {
                throw coded(unavailable, message, null);
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
        E emptyCode,
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

    public SyncException mapError(String message, RuntimeException ex, E authenticationFailure) {
        // Covers SidecarAuthenticationException too: a rejected shared secret keeps
        // its own code instead of being relabelled as a bad password or an outage.
        if (ex instanceof SyncException sync) return sync;
        if (causedByTimeout(ex)) {
            log.warn("{}: sidecar request timed out", message);
            return coded(unavailable, label + " took too long to respond. Please try again.", ex);
        }
        if (ex instanceof WebClientResponseException response) {
            E upstreamCode = responseCode(response);
            if (upstreamCode != null) {
                return coded(upstreamCode, friendlyMessage.apply(upstreamCode), ex);
            }
            if (response.getStatusCode().value() == 401 && authenticationFailure != null) {
                return coded(authenticationFailure, friendlyMessage.apply(authenticationFailure), ex);
            }
            if (response.getStatusCode().value() == 410) {
                return coded(attemptExpired, friendlyMessage.apply(attemptExpired), ex);
            }
            if (response.getStatusCode().is5xxServerError()) {
                log.warn("{} sidecar returned status {}", label, response.getStatusCode().value());
                return coded(unavailable, friendlyMessage.apply(unavailable), ex);
            }
        }
        log.error(message, ex);
        return coded(unavailable, message, ex);
    }

    public SyncException coded(E code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    private E responseCode(WebClientResponseException response) {
        try {
            JsonNode body = objectMapper.readTree(response.getResponseBodyAsString());
            JsonNode detailNode = body.path("detail");
            if (!detailNode.isTextual() || detailNode.asText().isBlank()) {
                log.debug(
                    "{} error response has no textual detail code (status={})",
                    label,
                    response.getStatusCode().value()
                );
                return null;
            }
            String detail = detailNode.asText();
            try {
                return Enum.valueOf(codeType, detail);
            } catch (IllegalArgumentException ex) {
                log.warn("{} sidecar returned unknown error code '{}'", label, detail);
                return null;
            }
        } catch (JsonProcessingException ex) {
            log.warn(
                "Could not parse {} sidecar error response (status={})",
                label,
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
