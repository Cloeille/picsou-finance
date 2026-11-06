package com.picsou.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** Wire-level provider: production discovery, JOSE validation and token exchange remain real. */
final class FakeOidcProvider implements AutoCloseable {
    private record Identity(String subject, Map<String, Object> claims, Instant authTime) {}
    private record Grant(Map<String, String> authorization, Identity identity) {}

    /** One way the next ID token is made invalid, for the callback's rejection scenarios. */
    enum IdTokenFault { BAD_SIGNATURE, WRONG_AUDIENCE, WRONG_ISSUER, EXPIRED }

    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final RSAKey key;
    private final RSAKey impostorKey;
    private final HttpClient browser = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Identity> pending = new ConcurrentHashMap<>();
    private final Map<String, Grant> codes = new ConcurrentHashMap<>();
    private final Map<String, Identity> accessTokens = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> authorizations = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> exchanges = new ConcurrentHashMap<>();
    final AtomicBoolean tamperNextVerifier = new AtomicBoolean();
    final AtomicBoolean tamperNextNonce = new AtomicBoolean();
    final AtomicReference<IdTokenFault> faultNextIdToken = new AtomicReference<>();

    FakeOidcProvider() throws IOException, JOSEException {
        key = new RSAKeyGenerator(2048).keyID("fake-oidc").generate();
        impostorKey = new RSAKeyGenerator(2048).keyID(key.getKeyID()).generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            counters.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
            try {
                switch (path) {
                    case "/.well-known/openid-configuration" -> respond(exchange, 200, Map.ofEntries(
                        Map.entry("issuer", issuer()), Map.entry("authorization_endpoint", issuer() + "/authorize"),
                        Map.entry("token_endpoint", issuer() + "/token"), Map.entry("userinfo_endpoint", issuer() + "/userinfo"),
                        Map.entry("jwks_uri", issuer() + "/jwks"), Map.entry("end_session_endpoint", issuer() + "/end_session"),
                        Map.entry("code_challenge_methods_supported", List.of("S256")),
                        Map.entry("response_types_supported", List.of("code")), Map.entry("subject_types_supported", List.of("public")),
                        Map.entry("id_token_signing_alg_values_supported", List.of("RS256")),
                        Map.entry("token_endpoint_auth_methods_supported", List.of("client_secret_basic"))));
                    case "/jwks" -> respond(exchange, 200, new JWKSet(key.toPublicJWK()).toJSONObject());
                    case "/authorize" -> authorize(exchange);
                    case "/token" -> token(exchange);
                    case "/userinfo" -> userinfo(exchange);
                    case "/end_session" -> respond(exchange, 200, Map.of());
                    default -> respond(exchange, 404, Map.of("error", "not_found"));
                }
            } catch (JOSEException e) {
                throw new IOException("Cannot sign fake ID token", e);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    String issuer() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    // Four arguments are the provider's required scenario contract, not unrelated options.
    String issueCode(Map<String, String> authorizeParams, String subject, Map<String, Object> claims,
                     Instant authTime) throws IOException, InterruptedException {
        pending.put(authorizeParams.get("state"), new Identity(subject, Map.copyOf(claims), authTime));
        String query = authorizeParams.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
            .collect(Collectors.joining("&"));
        var response = browser.send(HttpRequest.newBuilder(URI.create(issuer() + "/authorize?" + query))
            .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 302) throw new IOException("Fake authorize rejected request: " + response.statusCode());
        return params(URI.create(response.headers().firstValue("Location").orElseThrow()).getRawQuery()).get("code");
    }

    Map<String, String> authorization(String code) { return authorizations.get(code); }
    Map<String, String> exchange(String code) { return exchanges.get(code); }
    int calls(String path) { return counters.getOrDefault(path, new AtomicInteger()).get(); }
    Map<String, Integer> calls() {
        return counters.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }

    private void authorize(HttpExchange exchange) throws IOException {
        Map<String, String> parameters = params(exchange.getRequestURI().getRawQuery());
        Identity identity = pending.remove(parameters.get("state"));
        if (identity == null || !"S256".equals(parameters.get("code_challenge_method"))
                || !"picsou-test".equals(parameters.get("client_id")) || !"code".equals(parameters.get("response_type"))) {
            respond(exchange, 400, Map.of("error", "invalid_request"));
            return;
        }
        String code = UUID.randomUUID().toString();
        authorizations.put(code, Map.copyOf(parameters));
        codes.put(code, new Grant(Map.copyOf(parameters), identity));
        exchange.getResponseHeaders().add("Location", parameters.get("redirect_uri") + "?code=" + code
            + "&state=" + encode(parameters.get("state")));
        exchange.sendResponseHeaders(302, -1);
    }

    private void token(HttpExchange exchange) throws IOException, JOSEException {
        String basic = "Basic " + Base64.getEncoder().encodeToString("picsou-test:test-secret".getBytes(StandardCharsets.UTF_8));
        if (!basic.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            respond(exchange, 401, Map.of("error", "invalid_client"));
            return;
        }
        Map<String, String> parameters = params(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String code = parameters.get("code");
        exchanges.put(code, Map.copyOf(parameters));
        Grant grant = codes.remove(code);
        String verifier = parameters.get("code_verifier");
        if (tamperNextVerifier.getAndSet(false)) verifier = "tampered-" + verifier;
        if (grant == null || !"authorization_code".equals(parameters.get("grant_type")) || verifier == null
                || !grant.authorization().get("redirect_uri").equals(parameters.get("redirect_uri"))
                || !grant.authorization().get("code_challenge").equals(s256(verifier))) {
            respond(exchange, 400, Map.of("error", "invalid_grant"));
            return;
        }
        Identity identity = grant.identity();
        Instant now = Instant.now();
        String nonce = grant.authorization().get("nonce");
        if (tamperNextNonce.getAndSet(false)) nonce = "tampered-" + nonce;
        IdTokenFault fault = faultNextIdToken.getAndSet(null);
        var claims = new JWTClaimsSet.Builder()
            .issuer(fault == IdTokenFault.WRONG_ISSUER ? "https://other-idp.example" : issuer())
            .subject(identity.subject())
            .audience(fault == IdTokenFault.WRONG_AUDIENCE ? "another-client" : "picsou-test")
            .issueTime(Date.from(fault == IdTokenFault.EXPIRED ? now.minusSeconds(7200) : now))
            .expirationTime(Date.from(fault == IdTokenFault.EXPIRED ? now.minusSeconds(3600) : now.plusSeconds(300)))
            .claim("nonce", nonce)
            .claim("auth_time", identity.authTime().getEpochSecond()).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        // Same key id as the published key, so the verifier looks the right key up and the signature is what fails.
        jwt.sign(new RSASSASigner(fault == IdTokenFault.BAD_SIGNATURE ? impostorKey : key));
        String access = UUID.randomUUID().toString();
        accessTokens.put(access, identity);
        respond(exchange, 200, Map.of("access_token", access, "token_type", "Bearer", "expires_in", 300,
            "scope", "openid profile email", "id_token", jwt.serialize()));
    }

    private void userinfo(HttpExchange exchange) throws IOException {
        String bearer = exchange.getRequestHeaders().getFirst("Authorization");
        Identity identity = bearer != null && bearer.startsWith("Bearer ") ? accessTokens.get(bearer.substring(7)) : null;
        if (identity == null) {
            respond(exchange, 401, Map.of("error", "invalid_token"));
            return;
        }
        Map<String, Object> claims = new HashMap<>(identity.claims());
        claims.put("sub", identity.subject());
        respond(exchange, 200, claims);
    }

    static Map<String, String> params(String query) {
        Map<String, String> result = new HashMap<>();
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return Map.copyOf(result);
    }

    static String s256(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK must provide SHA-256", e);
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override public void close() { server.stop(0); browser.close(); }
}
