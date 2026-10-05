package com.picsou.finary.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.config.FinaryProperties;
import com.picsou.finary.dto.*;
import com.picsou.exception.FinaryServiceUnavailableException;
import com.picsou.exception.SyncException;
import com.picsou.exception.TotpRequiredException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Finary API client. Handles Clerk authentication and all Finary HTTP calls.
 * Stateless per-sync (re-authenticates on every sync for MVP).
 */
@Component
@Slf4j
public class FinaryApiClient {

    private static final String CLERK_BASE = "https://clerk.finary.com";
    private static final String FINARY_BASE = "https://api.finary.com";
    private static final String CLERK_QUERY_PARAMS = "?__clerk_api_version=2025-11-10&_clerk_js_version=5.125.4";
    private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final int MAX_RETRIES = 3;

    private final ObjectMapper objectMapper;
    private final FinaryProperties finaryProperties;
    private final HttpClient finaryHttpClient = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .build();

    public FinaryApiClient(ObjectMapper objectMapper, FinaryProperties finaryProperties) {
        this.objectMapper = objectMapper;
        this.finaryProperties = finaryProperties;
    }

    /**
     * Helper records for Clerk auth responses
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClerkSignInResponse(
        String id,
        String status,
        String createdSessionId
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClerkSessionInfo(
        String id
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClerkClientResponse(
        List<ClerkSessionInfo> sessions
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClerkClient(
        List<ClerkSessionInfo> sessions
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClerkSignInApiResponse(
        ClerkSignInResponse response,
        ClerkClient client
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClerkSessionApiResponse(
        ClerkClient client
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClerkTokenResponse(
        String jwt
    ) {}

    /**
     * Context needed for Finary API calls
     */
    public record OrgContext(
        String orgId,
        String membershipId
    ) {}

    /**
     * Check if TOTP is required for authentication
     * Returns the signInId if 2FA is needed, null otherwise
     */
    public String checkTotpRequired(String email, String password) {
        try {
            CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            HttpClient httpClient = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(TIMEOUT)
                .build();

            // Steps 1-3: Get to sign_in status
            log.debug("Finary auth check: GET /v1/environment");
            clerkGet(httpClient, "/v1/environment");

            log.debug("Finary auth check: GET /v1/client");
            clerkGet(httpClient, "/v1/client");

            log.debug("Finary auth check: POST /v1/client/sign_ins");
            String signInBody = "identifier=" + URLEncoder.encode(email, StandardCharsets.UTF_8) +
                    "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
            String signInResponse = clerkPost(httpClient, "/v1/client/sign_ins", signInBody);
            ClerkSignInApiResponse apiResp = objectMapper.readValue(signInResponse, ClerkSignInApiResponse.class);
            ClerkSignInResponse signIn = apiResp.response;

            if (signIn.status == null) {
                // Do not log the raw Clerk response — it can carry session tokens / PII.
                log.warn("Finary sign-in returned no status field in the Clerk response");
                throw new SyncException("Finary sign-in failed. Please check your credentials and try again.");
            }

            // If needs_second_factor, return the signInId for later completion
            if ("needs_second_factor".equals(signIn.status)) {
                log.info("Finary auth requires TOTP: {}", signIn.id);
                return signIn.id;
            }

            // Otherwise no TOTP needed
            return null;

        } catch (SyncException e) {
            throw e;
        } catch (IOException e) {
            if (isNetworkError(e)) {
                throw new FinaryServiceUnavailableException("Unable to reach Finary. Please check your connection and try again.", e);
            }
            throw new SyncException("Finary auth check failed: " + e.getMessage(), e);
        }
    }

    /**
     * Complete authentication with TOTP code
     */
    public String authenticateWithTotp(String email, String password, String signInId, String totp) {
        try {
            CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            HttpClient httpClient = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(TIMEOUT)
                .build();

            // Re-establish cookies (steps 1-2)
            clerkGet(httpClient, "/v1/environment");
            clerkGet(httpClient, "/v1/client");

            // Re-send sign_ins to get back to the same state
            String signInBody = "identifier=" + URLEncoder.encode(email, StandardCharsets.UTF_8) +
                    "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
            clerkPost(httpClient, "/v1/client/sign_ins", signInBody);

            // Step 4: Send TOTP code
            log.debug("Finary auth: POST /v1/client/sign_ins/{}/attempt_second_factor", signInId);
            String totpBody = "strategy=totp&code=" + URLEncoder.encode(totp, StandardCharsets.UTF_8);
            String totpResponse = clerkPost(httpClient, "/v1/client/sign_ins/" + signInId + "/attempt_second_factor", totpBody);
            ClerkSessionApiResponse sessionResp = objectMapper.readValue(totpResponse, ClerkSessionApiResponse.class);

            String sessionId = null;
            if (sessionResp.client.sessions != null && !sessionResp.client.sessions.isEmpty()) {
                sessionId = sessionResp.client.sessions.get(0).id;
            }

            if (sessionId == null) {
                throw new SyncException("Could not obtain Clerk session ID after TOTP");
            }

            // Steps 5-6: Get JWT
            clerkPost(httpClient, "/v1/client/sessions/" + sessionId + "/touch", "active_organization_id=");
            String tokenResponse = clerkPost(httpClient, "/v1/client/sessions/" + sessionId + "/tokens", "organization_id=");
            ClerkTokenResponse tokenResp = objectMapper.readValue(tokenResponse, ClerkTokenResponse.class);

            if (tokenResp.jwt == null || tokenResp.jwt.isBlank()) {
                throw new SyncException("Clerk did not return a JWT token after TOTP");
            }

            log.info("Finary authentication successful with TOTP");
            return tokenResp.jwt;

        } catch (SyncException e) {
            throw e;
        } catch (IOException e) {
            if (isNetworkError(e)) {
                throw new FinaryServiceUnavailableException("Unable to reach Finary. Please check your connection and try again.", e);
            }
            throw new SyncException("Finary TOTP authentication failed: " + e.getMessage(), e);
        }
    }

    /**
     * Authenticate to Finary via Clerk. Returns JWT token.
     */
    public String authenticate(String email, String password, String totp) {
        try {
            // Create cookie manager for this auth session
            CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

            // Build HttpClient with cookie support
            HttpClient httpClient = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(TIMEOUT)
                .build();

            // Step 1: GET /v1/environment
            log.debug("Clerk step 1: GET /v1/environment");
            clerkGet(httpClient, "/v1/environment");

            // Step 2: GET /v1/client (sets __client cookie)
            log.debug("Clerk step 2: GET /v1/client");
            clerkGet(httpClient, "/v1/client");

            // Step 3: POST /v1/client/sign_ins
            log.debug("Clerk step 3: POST /v1/client/sign_ins");
            String signInBody = "identifier=" + URLEncoder.encode(email, StandardCharsets.UTF_8) +
                    "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
            log.debug("Sign-in request: identifier=[REDACTED], password length={}", password.length());
            String signInResponse = clerkPost(httpClient, "/v1/client/sign_ins", signInBody);
            log.debug("Sign-in response (first 500 chars): {}", signInResponse.substring(0, Math.min(500, signInResponse.length())));
            ClerkSignInApiResponse apiResp = objectMapper.readValue(signInResponse, ClerkSignInApiResponse.class);
            ClerkSignInResponse signIn = apiResp.response;

            if (signIn.status == null) {
                throw new SyncException("Finary sign-in failed. Please check your credentials and try again.");
            }

            String signInId = signIn.id;
            String sessionId = null;

            // Step 4: Handle TOTP if needed
            if ("needs_second_factor".equals(signIn.status)) {
                if (totp == null || totp.isBlank()) {
                    throw new TotpRequiredException("TOTP required: account has 2FA enabled");
                }
                log.debug("Clerk step 4: POST /v1/client/sign_ins/{}/attempt_second_factor", signInId);
                String totpBody = "strategy=totp&code=" + URLEncoder.encode(totp, StandardCharsets.UTF_8);
                String totpResponse = clerkPost(httpClient, "/v1/client/sign_ins/" + signInId + "/attempt_second_factor", totpBody);
                log.debug("TOTP response (first 500 chars): {}", totpResponse.substring(0, Math.min(500, totpResponse.length())));
                // Extract sessionId from response wrapper
                ClerkSessionApiResponse sessionResp = objectMapper.readValue(totpResponse, ClerkSessionApiResponse.class);
                if (sessionResp.client.sessions != null && !sessionResp.client.sessions.isEmpty()) {
                    sessionId = sessionResp.client.sessions.get(0).id;
                }
            } else {
                // Session already created in sign_ins response
                sessionId = signIn.createdSessionId;
            }

            if (sessionId == null) {
                throw new SyncException("Could not obtain Clerk session ID");
            }

            // Step 5: POST /v1/client/sessions/{sessionId}/touch
            log.debug("Clerk step 5: POST /v1/client/sessions/{}/touch", sessionId);
            clerkPost(httpClient, "/v1/client/sessions/" + sessionId + "/touch", "active_organization_id=");

            // Step 6: POST /v1/client/sessions/{sessionId}/tokens
            log.debug("Clerk step 6: POST /v1/client/sessions/{}/tokens", sessionId);
            String tokenResponse = clerkPost(httpClient, "/v1/client/sessions/" + sessionId + "/tokens", "organization_id=");
            ClerkTokenResponse tokenResp = objectMapper.readValue(tokenResponse, ClerkTokenResponse.class);

            if (tokenResp.jwt == null || tokenResp.jwt.isBlank()) {
                throw new SyncException("Clerk did not return a JWT token");
            }

            log.info("Clerk authentication successful");
            return tokenResp.jwt;

        } catch (SyncException | TotpRequiredException e) {
            throw e;
        } catch (IOException e) {
            if (isNetworkError(e)) {
                throw new FinaryServiceUnavailableException("Unable to reach Finary. Please check your connection and try again.", e);
            }
            throw new SyncException("Clerk authentication failed: " + e.getMessage(), e);
        }
    }

    /**
     * Fetch organization context (orgId + membershipId) for the authenticated user
     */
    public OrgContext fetchOrganizationContext(String jwt) {
        try {
            String response = finaryGet(jwt, "/users/me/organizations");
            FinaryEnvelope<List<FinaryOrganization>> envelope =
                objectMapper.readValue(response, objectMapper.getTypeFactory()
                    .constructParametricType(FinaryEnvelope.class,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, FinaryOrganization.class)));

            if (envelope.result() == null || envelope.result().isEmpty()) {
                throw new SyncException("No organizations found for user");
            }

            FinaryOrganization org = envelope.result().get(0);

            // Find the user's membership ID (not necessarily owner)
            for (FinaryOrgMember member : org.members()) {
                if (member.user() != null && member.user().fullname() != null) {
                    log.info("Found organization (orgId: {}) - memberType: {}",
                            org.id(), member.memberType());
                    return new OrgContext(org.id(), member.id());
                }
            }

            throw new SyncException("User membership not found in organization");

        } catch (SyncException e) {
            throw e;
        } catch (IOException e) {
            if (isNetworkError(e)) {
                throw new FinaryServiceUnavailableException("Unable to reach Finary. Please check your connection and try again.", e);
            }
            throw new SyncException("Failed to fetch organization context: " + e.getMessage(), e);
        }
    }

    /**
     * Fetch all accounts for a specific category
     */
    public List<FinaryAccountDto> fetchCategoryAccounts(String jwt, OrgContext ctx, String category) {
        try {
            String path = String.format("/organizations/%s/memberships/%s/portfolio/%s/accounts",
                ctx.orgId(), ctx.membershipId(), category);
            String response = finaryGet(jwt, path);

            FinaryEnvelope<List<FinaryAccountDto>> envelope =
                objectMapper.readValue(response, objectMapper.getTypeFactory()
                    .constructParametricType(FinaryEnvelope.class,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, FinaryAccountDto.class)));

            return envelope.result() != null ? envelope.result() : List.of();

        } catch (IOException e) {
            if (isNetworkError(e)) {
                throw new FinaryServiceUnavailableException("Unable to reach Finary. Please check your connection and try again.", e);
            }
            throw new SyncException("Failed to fetch accounts for category " + category + ": " + e.getMessage(), e);
        }
    }

    /**
     * Fetch loan / mortgage accounts from Finary's dedicated {@code /loans} endpoint.
     *
     * <p>Loans are not part of the portfolio {@code credits} category, so they have to be
     * fetched separately (issue #11). The endpoint path is best-effort: it mirrors the
     * other {@code /users/me/*} resources (e.g. {@code /users/me/organizations}).
     */
    public List<FinaryLoanDto> fetchLoans(String jwt) {
        try {
            String response = finaryGet(jwt, "/users/me/loans");

            FinaryEnvelope<List<FinaryLoanDto>> envelope =
                objectMapper.readValue(response, objectMapper.getTypeFactory()
                    .constructParametricType(FinaryEnvelope.class,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, FinaryLoanDto.class)));

            return envelope.result() != null ? envelope.result() : List.of();

        } catch (IOException e) {
            if (isNetworkError(e)) {
                throw new FinaryServiceUnavailableException("Unable to reach Finary. Please check your connection and try again.", e);
            }
            throw new SyncException("Failed to fetch loans: " + e.getMessage(), e);
        }
    }

    /**
     * Fetch transactions for a specific category (paginated)
     */
    public List<FinaryTransactionDto> fetchCategoryTransactions(String jwt, OrgContext ctx, String category, int page, int perPage) {
        try {
            String path = String.format("/organizations/%s/memberships/%s/portfolio/%s/transactions?page=%d&per_page=%d",
                ctx.orgId(), ctx.membershipId(), category, page, perPage);
            String response = finaryGet(jwt, path);

            FinaryEnvelope<List<FinaryTransactionDto>> envelope =
                objectMapper.readValue(response, objectMapper.getTypeFactory()
                    .constructParametricType(FinaryEnvelope.class,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, FinaryTransactionDto.class)));

            return envelope.result() != null ? envelope.result() : List.of();

        } catch (IOException e) {
            if (isNetworkError(e)) {
                throw new FinaryServiceUnavailableException("Unable to reach Finary. Please check your connection and try again.", e);
            }
            throw new SyncException("Failed to fetch transactions for category " + category + ": " + e.getMessage(), e);
        }
    }

    /**
     * Clerk GET helper
     */
    private String clerkGet(HttpClient client, String path) throws IOException {
        String url = CLERK_BASE + path + CLERK_QUERY_PARAMS;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(TIMEOUT)
            .header("User-Agent", USER_AGENT)
            .header("Origin", "https://app.finary.com")
            .header("Referer", "https://app.finary.com/")
            .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
            .GET()
            .build();

        HttpResponse<String> response = sendWithRetry(client, request);
        if (response.statusCode() == 401) {
            throw new SyncException("Invalid Finary credentials. Please check your email and password.");
        }
        if (response.statusCode() >= 400) {
            // Bound the Clerk error body: it propagates into SyncException messages
            // (logs + user-facing 422), and Clerk responses can carry auth material.
            throw new IOException("HTTP " + response.statusCode() + ": " + truncate(response.body(), 200));
        }
        return response.body();
    }

    /**
     * Clerk POST helper
     */
    private String clerkPost(HttpClient client, String path, String formBody) throws IOException {
        String url = CLERK_BASE + path + CLERK_QUERY_PARAMS;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(TIMEOUT)
            .header("User-Agent", USER_AGENT)
            .header("Origin", "https://app.finary.com")
            .header("Referer", "https://app.finary.com/")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .build();

        HttpResponse<String> response = sendWithRetry(client, request);
        if (response.statusCode() == 401) {
            throw new SyncException("Invalid Finary credentials. Please check your email and password.");
        }
        if (response.statusCode() >= 400) {
            // Bound the Clerk error body: it propagates into SyncException messages
            // (logs + user-facing 422), and Clerk responses can carry auth material.
            throw new IOException("HTTP " + response.statusCode() + ": " + truncate(response.body(), 200));
        }
        return response.body();
    }

    /** Bounds a response body for safe inclusion in logs / error messages. */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…(" + s.length() + " chars)";
    }

    /**
     * Finary GET helper
     */
    private String finaryGet(String jwt, String path) throws IOException {
        String url = FINARY_BASE + path;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(TIMEOUT)
            .header("Authorization", "Bearer " + jwt)
            .header("Origin", "https://app.finary.com")
            .header("Referer", "https://app.finary.com/")
            .header("x-client-api-version", "2")
            .header("x-finary-client-id", "webapp")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
            .GET()
            .build();

        HttpResponse<String> response = sendWithRetry(finaryHttpClient, request);
        if (response.statusCode() >= 400) {
            // Bound the body: the full authenticated error payload should not be
            // dumped to logs, and it also flows into user-facing error messages
            // (FinaryApiSyncService wraps this IOException's message). 500 chars,
            // not 200: this is the Finary data API, whose error bodies carry the
            // actionable message — enough room not to cut it mid-sentence, still
            // bounded so a runaway payload can't flood logs.
            String bodySnippet = truncate(response.body(), 500);
            log.error("Finary API error {}: {}", response.statusCode(), bodySnippet);
            throw new IOException("HTTP " + response.statusCode() + ": " + bodySnippet);
        }
        return response.body();
    }

    private HttpResponse<String> sendWithRetry(HttpClient client, HttpRequest request) throws IOException {
        IOException lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 500) {
                    log.warn("Finary/Clerk returned HTTP {} (attempt {}/{})", response.statusCode(), attempt + 1, MAX_RETRIES);
                    if (attempt < MAX_RETRIES - 1) {
                        Thread.sleep((long) Math.pow(2, attempt) * 1000);
                        continue;
                    }
                    throw new IOException("HTTP " + response.statusCode() + ": " + truncate(response.body(), 200));
                }
                return response;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Request interrupted", e);
            } catch (IOException e) {
                lastException = e;
                if (attempt < MAX_RETRIES - 1) {
                    log.warn("Request failed (attempt {}/{}): {}", attempt + 1, MAX_RETRIES, e.getMessage());
                    try {
                        Thread.sleep((long) Math.pow(2, attempt) * 1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Retry sleep interrupted", ie);
                    }
                }
            }
        }
        throw lastException;
    }

    private boolean isNetworkError(IOException e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("timed out") || lower.contains("connection refused")
            || lower.contains("unreachable") || lower.contains("connect failed")
            || lower.contains("connection reset") || lower.contains("ssl")
            || lower.contains("unknownhost") || lower.contains("no route to host");
    }
}
