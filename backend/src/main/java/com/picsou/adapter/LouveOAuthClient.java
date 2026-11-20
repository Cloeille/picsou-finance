package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.picsou.exception.SyncException;
import com.picsou.port.SidecarErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The OAuth 2.1 side of Louve Invest's official MCP server: dynamic client registration
 * (RFC 7591), the authorization URL with PKCE S256, the code exchange, the refresh and the
 * revocation. Louve registers public clients ({@code token_endpoint_auth_method: none}), so
 * there is no client secret anywhere.
 *
 * <p>The host is fixed: nothing a member or a response supplies can point this client
 * elsewhere. Only {@value #SCOPE} is ever requested, whatever scopes the registration echoes.
 *
 * <p>Nothing here logs a token, a code, a verifier or a response body; failures are logged by
 * HTTP status and OAuth error name only.
 */
@Component
public class LouveOAuthClient {

    public static final String ISSUER = "https://app.louveinvest.com";
    public static final String RESOURCE = ISSUER + "/mcp";
    public static final String SCOPE = "portfolio:read";

    static final URI REGISTRATION_ENDPOINT = URI.create(ISSUER + "/mcp/oauth/register");
    static final URI AUTHORIZATION_ENDPOINT = URI.create(ISSUER + "/mcp/oauth/authorize");
    static final URI TOKEN_ENDPOINT = URI.create(ISSUER + "/mcp/oauth/token");
    static final URI REVOCATION_ENDPOINT = URI.create(ISSUER + "/mcp/oauth/revoke");

    private static final Logger log = LoggerFactory.getLogger(LouveOAuthClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CLIENT_NAME = "Picsou";
    /** A client id is put in a URL and a form; anything outside this set is refused. */
    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9._~-]{1,255}");
    private static final long DEFAULT_EXPIRES_IN = 3600;

    private final LouveTransport transport;

    public LouveOAuthClient() {
        this(new LouveTransport.Http());
    }

    /** Visible for tests. */
    LouveOAuthClient(LouveTransport transport) {
        this.transport = transport;
    }

    /** What the token endpoint granted. {@code refreshToken} may be null when Louve keeps the old one. */
    public record Tokens(String accessToken, String refreshToken, long expiresInSeconds, String scope) {
        @Override
        public String toString() {
            // Never print a token, even by accident in a debugger or a log line.
            return "Tokens[expiresInSeconds=" + expiresInSeconds + ", scope=" + scope + "]";
        }
    }

    /**
     * Louve no longer knows the client id (or never did). The caller drops the stored
     * registration of exactly {@link #clientId()} so the next connection registers again.
     */
    public static final class InvalidClientException extends SyncException {
        private final String clientId;
        private final String redirectUri;

        public InvalidClientException(String message, SidecarErrorCode code, String clientId, String redirectUri) {
            super(message, null, code.name());
            this.clientId = clientId;
            this.redirectUri = redirectUri;
        }

        /** The same refusal, for the redirect URI the client was registered for. */
        public InvalidClientException withRedirectUri(String redirectUri) {
            return new InvalidClientException(getMessage(), SidecarErrorCode.valueOf(getCode()), clientId, redirectUri);
        }

        /** The client id Louve refused; not a secret (a public client's id). */
        public String clientId() {
            return clientId;
        }

        /** The redirect URI that client was registered for (set by the adapter for a refresh). */
        public String redirectUri() {
            return redirectUri;
        }
    }

    /** A random PKCE verifier: 32 bytes, base64url without padding (43 characters, RFC 7636). */
    public static String newVerifier() {
        return randomToken();
    }

    /** A random OAuth {@code state}, unguessable and single-use. */
    public static String newState() {
        return randomToken();
    }

    /** {@code BASE64URL(SHA-256(verifier))}, the S256 challenge. */
    public static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /** Registers Picsou as a public client for {@code redirectUri} and returns the client id. */
    public String register(String redirectUri) {
        ObjectNode body = JSON.createObjectNode();
        body.put("client_name", CLIENT_NAME);
        body.putArray("redirect_uris").add(redirectUri);
        body.putArray("grant_types").add("authorization_code").add("refresh_token");
        body.putArray("response_types").add("code");
        body.put("token_endpoint_auth_method", "none");
        body.put("scope", SCOPE);
        LouveTransport.Response response = send(new LouveTransport.Request("POST", REGISTRATION_ENDPOINT,
            Map.of("Content-Type", "application/json", "Accept", "application/json"), body.toString()));
        if (response.status() == 429) {
            throw error(SidecarErrorCode.RATE_LIMITED, "Louve Invest is limiting requests, try again later");
        }
        if (response.status() >= 500) {
            throw error(SidecarErrorCode.UPSTREAM_UNAVAILABLE, "Louve Invest is temporarily unavailable");
        }
        if (response.status() != 200 && response.status() != 201) {
            log.warn("Louve Invest refused the client registration (status={}; error={})",
                response.status(), oauthError(response.body()));
            throw error(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED,
                "Louve Invest refused to register this Picsou address. Check the address Picsou is reached at.");
        }
        String clientId = text(parse(response.body()), "client_id");
        if (clientId == null || !CLIENT_ID.matcher(clientId).matches()) {
            throw error(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED, "Louve Invest returned no usable client id");
        }
        return clientId;
    }

    /** The consent page the member's browser is sent to. Only {@value #SCOPE} is asked for. */
    public URI authorizeUri(String clientId, String redirectUri, String state, String codeChallenge) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", clientId);
        query.put("redirect_uri", redirectUri);
        query.put("scope", SCOPE);
        query.put("state", state);
        query.put("code_challenge", codeChallenge);
        query.put("code_challenge_method", "S256");
        query.put("resource", RESOURCE);
        return URI.create(AUTHORIZATION_ENDPOINT + "?" + form(query));
    }

    /**
     * Exchanges the authorization code. A refused code ({@code invalid_grant}: used, expired,
     * or issued for another verifier) is {@code AUTH_ATTEMPT_EXPIRED}: the member starts again.
     */
    public Tokens exchangeCode(String clientId, String redirectUri, String code, String verifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", clientId);
        form.put("code_verifier", verifier);
        form.put("resource", RESOURCE);
        return token(form, SidecarErrorCode.AUTH_ATTEMPT_EXPIRED, "code exchange");
    }

    /**
     * Refreshes the access token. A refused refresh token is {@code SESSION_EXPIRED}: the
     * member revoked Picsou on Louve, or the grant lapsed, and only a new consent helps.
     */
    public Tokens refresh(String clientId, String refreshToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", clientId);
        form.put("resource", RESOURCE);
        return token(form, SidecarErrorCode.SESSION_EXPIRED, "refresh");
    }

    /** Revokes a token (RFC 7009). Best effort: a failure is logged by status and never thrown. */
    public void revoke(String clientId, String token, String tokenTypeHint) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("token", token);
        form.put("token_type_hint", tokenTypeHint);
        form.put("client_id", clientId);
        try {
            LouveTransport.Response response = send(formRequest(REVOCATION_ENDPOINT, form));
            if (response.status() != 200) {
                log.info("Louve Invest did not confirm the token revocation (status={})", response.status());
            }
        } catch (RuntimeException ex) {
            log.info("Could not revoke the Louve Invest token (type={})", ex.getClass().getSimpleName());
        }
    }

    private Tokens token(Map<String, String> form, SidecarErrorCode refused, String call) {
        LouveTransport.Response response = send(formRequest(TOKEN_ENDPOINT, form));
        int status = response.status();
        if (status == 429) {
            throw error(SidecarErrorCode.RATE_LIMITED, "Louve Invest is limiting requests, try again later");
        }
        if (status >= 500) {
            throw error(SidecarErrorCode.UPSTREAM_UNAVAILABLE, "Louve Invest is temporarily unavailable");
        }
        if (status != 200) {
            String oauthError = oauthError(response.body());
            log.warn("Louve Invest refused the token {} (status={}; error={})", call, status, oauthError);
            if ("invalid_client".equals(oauthError) || "unauthorized_client".equals(oauthError)) {
                throw new InvalidClientException("Louve Invest no longer recognises Picsou. Connect again.", refused,
                    form.get("client_id"), form.get("redirect_uri"));
            }
            if ("invalid_grant".equals(oauthError) || status == 401) {
                throw error(refused, refused == SidecarErrorCode.SESSION_EXPIRED
                    ? "The Louve Invest access was revoked or expired. Connect again."
                    : "Louve Invest refused this connection attempt. Start again.");
            }
            throw error(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED, "Louve Invest refused the token request");
        }
        JsonNode body = parse(response.body());
        String accessToken = text(body, "access_token");
        String tokenType = text(body, "token_type");
        if (accessToken == null || tokenType == null || !"bearer".equalsIgnoreCase(tokenType)) {
            throw error(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED, "Louve Invest returned no usable access token");
        }
        JsonNode expires = body.get("expires_in");
        long expiresIn = expires != null && expires.canConvertToLong() && expires.asLong() > 0
            ? expires.asLong() : DEFAULT_EXPIRES_IN;
        String scope = text(body, "scope");
        if (scope != null && !scope.equals(SCOPE)) {
            // Scope names are not secrets. Picsou only ever calls read tools, but a wider grant
            // than asked for is worth knowing about.
            log.warn("Louve Invest granted a different scope than requested (scope={})", scope);
        }
        return new Tokens(accessToken, text(body, "refresh_token"), expiresIn, scope);
    }

    private LouveTransport.Response send(LouveTransport.Request request) {
        try {
            return transport.send(request);
        } catch (LouveTransport.Unreachable ex) {
            throw new SyncException("Louve Invest is temporarily unavailable", ex,
                SidecarErrorCode.UPSTREAM_UNAVAILABLE.name());
        }
    }

    private static LouveTransport.Request formRequest(URI uri, Map<String, String> form) {
        return new LouveTransport.Request("POST", uri, Map.of(
            "Content-Type", "application/x-www-form-urlencoded",
            "Accept", "application/json"), form(form));
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream()
            .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
            .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static JsonNode parse(String body) {
        try {
            JsonNode node = body == null ? null : JSON.readTree(body);
            if (node == null || !node.isObject()) {
                throw error(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED, "Louve Invest returned an unreadable answer");
            }
            return node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw error(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED, "Louve Invest returned an unreadable answer");
        }
    }

    /** The OAuth {@code error} name of an error body, or {@code "unknown"}. Never the description. */
    private static String oauthError(String body) {
        try {
            JsonNode node = body == null ? null : JSON.readTree(body);
            String name = node == null ? null : text(node, "error");
            return name != null && name.matches("[a-z_]{1,40}") ? name : "unknown";
        } catch (Exception ex) {
            return "unknown";
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static SyncException error(SidecarErrorCode code, String message) {
        return new SyncException(message, null, code.name());
    }
}
