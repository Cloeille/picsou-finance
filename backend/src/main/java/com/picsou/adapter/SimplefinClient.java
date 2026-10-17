package com.picsou.adapter;

import com.picsou.exception.SyncException;
import com.picsou.port.SimplefinPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * SimpleFIN over HTTPS. Redirects are refused. Credentials travel in an
 * Authorization header, never in the request URI.
 */
@Component
public class SimplefinClient implements SimplefinPort {

    private static final Logger log = LoggerFactory.getLogger(SimplefinClient.class);
    private static final int MAX_CLAIM_BODY = 8_192;
    private static final int MAX_ACCOUNTS_BODY = 2_000_000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final SimplefinTransport transport;

    public SimplefinClient() {
        HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
        this.transport = (method, uri, authorization, maxBody) -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT);
            if ("POST".equals(method)) builder.POST(HttpRequest.BodyPublishers.noBody());
            else builder.GET();
            if (authorization != null) builder.header("Authorization", authorization);
            builder.header("Accept", "application/json");
            try {
                HttpResponse<String> response = http.send(builder.build(), boundedUtf8(maxBody));
                return new SimplefinTransport.Response(response.statusCode(), response.body());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new SyncException("Could not reach the SimpleFIN server. Try again in a moment.", ex);
            } catch (IOException ex) {
                if (tooLarge(ex)) {
                    throw new SyncException("SimpleFIN returned a response that is too large.", ex);
                }
                throw new SyncException("Could not reach the SimpleFIN server. Try again in a moment.", ex);
            }
        };
    }

    /** Visible for tests. */
    SimplefinClient(SimplefinTransport transport) {
        this.transport = transport;
    }

    @Override
    public String claim(String setupToken) {
        URI claimUrl = SimplefinUrls.claimUri(setupToken);
        SimplefinTransport.Response response = transport.send("POST", claimUrl, null, MAX_CLAIM_BODY);
        if (response.status() == 403) {
            throw new SyncException(
                "This SimpleFIN setup token is invalid or has already been used. Create a new one and paste it again.");
        }
        requireSuccess(response, MAX_CLAIM_BODY, "claim");
        String accessUrl = response.body() == null ? "" : response.body().trim();
        if (accessUrl.length() >= 2 && accessUrl.startsWith("\"") && accessUrl.endsWith("\"")) {
            accessUrl = accessUrl.substring(1, accessUrl.length() - 1).trim();
        }
        return SimplefinUrls.requireAccessUrl(accessUrl);
    }

    @Override
    public SimplefinAccountSet fetchAccounts(String accessUrl, LocalDate startDate) {
        SimplefinUrls.AccountsRequest request = SimplefinUrls.accountsRequest(accessUrl, startDate);
        SimplefinTransport.Response response = transport.send(
            "GET", request.uri(), request.authorization(), MAX_ACCOUNTS_BODY);
        if (response.status() == 403) {
            throw new SyncException(
                "SimpleFIN refused the stored access. It may have been revoked. Disconnect and connect with a new setup token.",
                null, "SESSION_EXPIRED");
        }
        if (response.status() == 402) {
            throw new SyncException("SimpleFIN requires payment before it will return accounts.");
        }
        requireSuccess(response, MAX_ACCOUNTS_BODY, "accounts");
        SimplefinAccountSet set = SimplefinJson.parse(response.body());
        if (set.accounts().isEmpty() && !set.errors().isEmpty()) {
            throw new SyncException(String.join(" ", set.errors()));
        }
        return set;
    }

    private static void requireSuccess(SimplefinTransport.Response response, int maxBody, String call) {
        int status = response.status();
        if (status >= 300 && status < 400) {
            throw new SyncException("The SimpleFIN server redirected the request, which Picsou refuses.");
        }
        if (status < 200 || status >= 300) {
            log.warn("SimpleFIN {} failed with HTTP {}", call, status);
            throw new SyncException("SimpleFIN could not complete the request (HTTP " + status + ").");
        }
        if (response.body() != null && response.body().length() > maxBody) {
            throw new SyncException("SimpleFIN returned a response that is too large.");
        }
    }

    /** Stops reading once {@code maxBody} bytes have arrived, including a lying Content-Length. */
    static HttpResponse.BodyHandler<String> boundedUtf8(int maxBody) {
        return info -> {
            long advertised = info.headers().firstValueAsLong("Content-Length").orElse(-1);
            return boundedSubscriber(maxBody, advertised > maxBody);
        };
    }

    private static boolean tooLarge(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof ResponseTooLarge) return true;
        }
        return false;
    }

    private static HttpResponse.BodySubscriber<String> boundedSubscriber(int maxBody, boolean alreadyTooBig) {
        return new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<String> body = new CompletableFuture<>();
            private final ByteArrayOutputStream out = new ByteArrayOutputStream();
            private Flow.Subscription subscription;
            private int seen;

            @Override
            public CompletionStage<String> getBody() {
                return body;
            }

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                if (alreadyTooBig) {
                    subscription.cancel();
                    body.completeExceptionally(new ResponseTooLarge());
                    return;
                }
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(List<ByteBuffer> buffers) {
                for (ByteBuffer buffer : buffers) {
                    int n = buffer.remaining();
                    if (seen > maxBody - n) {
                        subscription.cancel();
                        body.completeExceptionally(new ResponseTooLarge());
                        return;
                    }
                    byte[] chunk = new byte[n];
                    buffer.get(chunk);
                    out.write(chunk, 0, n);
                    seen += n;
                }
            }

            @Override
            public void onError(Throwable throwable) {
                body.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                if (!body.isDone()) {
                    body.complete(out.toString(StandardCharsets.UTF_8));
                }
            }
        };
    }

    private static final class ResponseTooLarge extends IOException {
        ResponseTooLarge() {
            super("response too large");
        }
    }
}
