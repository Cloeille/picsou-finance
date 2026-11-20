package com.picsou.adapter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One HTTPS exchange with Louve Invest. Production uses {@code java.net.http.HttpClient}; tests
 * pass a fake. Redirects are refused: a token or an authorization code must never follow one.
 */
interface LouveTransport {

    Response send(Request request);

    /** {@code body} is null for a request without one. */
    record Request(String method, URI uri, Map<String, String> headers, String body) {}

    record Response(int status, Map<String, List<String>> headers, String body) {

        /** The first value of a header, matched case-insensitively. */
        Optional<String> header(String name) {
            if (headers == null) {
                return Optional.empty();
            }
            String wanted = name.toLowerCase(Locale.ROOT);
            return headers.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(wanted))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst();
        }
    }

    /**
     * Louve could not be reached, or answered more than the body cap. Carries no request or
     * response content, so it is safe to log by type.
     */
    final class Unreachable extends RuntimeException {
        Unreachable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The production transport. */
    final class Http implements LouveTransport {

        private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
        private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
        /** A portfolio or a fund sheet is a few kilobytes; a price history a few dozen. */
        private static final int MAX_BODY = 2_000_000;

        private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

        @Override
        public Response send(Request request) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri()).timeout(REQUEST_TIMEOUT);
            request.headers().forEach(builder::header);
            builder.method(request.method(), request.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(request.body()));
            try {
                HttpResponse<String> response = http.send(builder.build(), BoundedUtf8Body.handler(MAX_BODY));
                return new Response(response.statusCode(), response.headers().map(), response.body());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new Unreachable("Interrupted while calling Louve Invest", ex);
            } catch (IOException ex) {
                throw new Unreachable(BoundedUtf8Body.isTooLarge(ex)
                    ? "Louve Invest returned a response that is too large"
                    : "Could not reach Louve Invest", ex);
            }
        }
    }
}
