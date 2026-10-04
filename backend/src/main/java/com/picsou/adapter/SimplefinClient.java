package com.picsou.adapter;

import com.picsou.exception.SyncException;
import com.picsou.port.SimplefinPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;

/**
 * SimpleFIN over HTTPS. Redirects are refused. Credentials travel in an
 * Authorization header, never in the request URI.
 */
@Component
public class SimplefinClient implements SimplefinPort {

    private static final Logger log = LoggerFactory.getLogger(SimplefinClient.class);
    private static final int MAX_CLAIM_BODY = 8_192;
    private static final int MAX_ACCOUNTS_BODY = 2_000_000;

    private final SimplefinTransport transport;

    public SimplefinClient() {
        HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.transport = (method, uri, authorization) -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));
            if ("POST".equals(method)) builder.POST(HttpRequest.BodyPublishers.noBody());
            else builder.GET();
            if (authorization != null) builder.header("Authorization", authorization);
            builder.header("Accept", "application/json");
            try {
                HttpResponse<String> response = http.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return new SimplefinTransport.Response(response.statusCode(), response.body());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new SyncException("Could not reach the SimpleFIN server. Try again in a moment.", ex);
            } catch (IOException ex) {
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
        SimplefinTransport.Response response = transport.send("POST", claimUrl, null);
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
        SimplefinTransport.Response response = transport.send("GET", request.uri(), request.authorization());
        if (response.status() == 403) {
            throw new SyncException(
                "SimpleFIN refused the stored access. It may have been revoked. Disconnect and connect with a new setup token.");
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
}
