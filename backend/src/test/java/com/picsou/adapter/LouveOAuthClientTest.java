package com.picsou.adapter;

import com.picsou.exception.SyncException;
import com.picsou.port.SidecarErrorCode;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Synthetic OAuth answers; no real client id or token. */
class LouveOAuthClientTest {

    private final List<LouveTransport.Request> sent = new ArrayList<>();
    private LouveTransport.Response answer;
    private final LouveOAuthClient client = new LouveOAuthClient(request -> {
        sent.add(request);
        return answer;
    });

    private static LouveTransport.Response json(int status, String body) {
        return new LouveTransport.Response(status, Map.of("Content-Type", List.of("application/json")), body);
    }

    private static Map<String, String> form(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        Arrays.stream(body.split("&")).forEach(pair -> {
            String[] kv = pair.split("=", 2);
            values.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        });
        return values;
    }

    @Test
    void theChallengeIsTheRfc7636S256Example() {
        assertThat(LouveOAuthClient.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
            .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
        assertThat(LouveOAuthClient.newVerifier()).hasSize(43).matches("[A-Za-z0-9_-]+")
            .isNotEqualTo(LouveOAuthClient.newVerifier());
        assertThat(LouveOAuthClient.newState()).hasSize(43);
    }

    @Test
    void theAuthorizeUrlAsksForThePortfolioOnlyWithPkceAndTheResource() {
        String url = client.authorizeUri("client12chr", "https://picsou.example/sync/louve/callback", "st", "ch")
            .toString();

        assertThat(url).startsWith("https://app.louveinvest.com/mcp/oauth/authorize?");
        Map<String, String> query = form(url.substring(url.indexOf('?') + 1));
        assertThat(query).containsExactly(
            Map.entry("response_type", "code"),
            Map.entry("client_id", "client12chr"),
            Map.entry("redirect_uri", "https://picsou.example/sync/louve/callback"),
            Map.entry("scope", "portfolio:read"),
            Map.entry("state", "st"),
            Map.entry("code_challenge", "ch"),
            Map.entry("code_challenge_method", "S256"),
            Map.entry("resource", "https://app.louveinvest.com/mcp"));
    }

    @Test
    void registrationIsAPublicClientForTheRedirectUri() {
        answer = json(201, "{\"client_id\":\"abcDEF123456\",\"client_id_issued_at\":1,"
            + "\"scope\":\"profile:read portfolio:read simulations:write\"}");

        assertThat(client.register("https://picsou.example/sync/louve/callback")).isEqualTo("abcDEF123456");
        LouveTransport.Request request = sent.get(0);
        assertThat(request.uri().toString()).isEqualTo("https://app.louveinvest.com/mcp/oauth/register");
        assertThat(request.body()).contains("\"token_endpoint_auth_method\":\"none\"")
            .contains("\"redirect_uris\":[\"https://picsou.example/sync/louve/callback\"]")
            .contains("\"scope\":\"portfolio:read\"");
    }

    @Test
    void aClientIdThatCouldInjectAQueryIsRefused() {
        answer = json(201, "{\"client_id\":\"abc&scope=all\"}");

        assertCode(() -> client.register("https://p/sync/louve/callback"), SidecarErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    @Test
    void theCodeExchangeSendsTheVerifierAndTheResource() {
        answer = json(200, "{\"access_token\":\"access-xyz\",\"token_type\":\"Bearer\",\"expires_in\":3600,"
            + "\"refresh_token\":\"refresh-xyz\",\"scope\":\"portfolio:read\"}");

        LouveOAuthClient.Tokens tokens = client.exchangeCode("cid", "https://p/sync/louve/callback", "code-1", "verif");

        assertThat(tokens.accessToken()).isEqualTo("access-xyz");
        assertThat(tokens.refreshToken()).isEqualTo("refresh-xyz");
        assertThat(tokens.expiresInSeconds()).isEqualTo(3600);
        assertThat(tokens.toString()).doesNotContain("access-xyz").doesNotContain("refresh-xyz");
        assertThat(sent.get(0).uri().toString()).isEqualTo("https://app.louveinvest.com/mcp/oauth/token");
        assertThat(form(sent.get(0).body())).containsEntry("grant_type", "authorization_code")
            .containsEntry("code", "code-1").containsEntry("code_verifier", "verif").containsEntry("client_id", "cid")
            .containsEntry("redirect_uri", "https://p/sync/louve/callback")
            .containsEntry("resource", "https://app.louveinvest.com/mcp");
    }

    @Test
    void aRefusedRefreshTokenEndsTheSession() {
        answer = json(400, "{\"error\":\"invalid_grant\",\"error_description\":\"secret detail\"}");

        assertCode(() -> client.refresh("cid", "rt"), SidecarErrorCode.SESSION_EXPIRED);
        assertThat(form(sent.get(0).body())).containsEntry("grant_type", "refresh_token")
            .containsEntry("refresh_token", "rt").containsEntry("resource", "https://app.louveinvest.com/mcp");
    }

    @Test
    void aRefusedCodeIsAnExpiredAttempt() {
        answer = json(400, "{\"error\":\"invalid_grant\"}");

        assertCode(() -> client.exchangeCode("cid", "r", "c", "v"), SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
    }

    @Test
    void anUnknownClientIsReportedSoTheRegistrationIsDropped() {
        answer = json(401, "{\"error\":\"invalid_client\"}");

        assertThatThrownBy(() -> client.refresh("cid", "rt"))
            .isInstanceOfSatisfying(LouveOAuthClient.InvalidClientException.class,
                ex -> assertThat(ex.clientId()).isEqualTo("cid"))
            .extracting(ex -> ((SyncException) ex).getCode()).isEqualTo(SidecarErrorCode.SESSION_EXPIRED.name());
    }

    @Test
    void aTokenAnswerThatIsNotBearerIsRefused() {
        answer = json(200, "{\"access_token\":\"at\",\"token_type\":\"mac\"}");

        assertCode(() -> client.refresh("cid", "rt"), SidecarErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    @Test
    void serverErrorsAndThrottlingAreTransient() {
        answer = json(503, "");
        assertCode(() -> client.refresh("cid", "rt"), SidecarErrorCode.UPSTREAM_UNAVAILABLE);
        answer = json(429, "");
        assertCode(() -> client.refresh("cid", "rt"), SidecarErrorCode.RATE_LIMITED);
    }

    @Test
    void revocationNeverThrows() {
        LouveOAuthClient unreachable = new LouveOAuthClient(request -> {
            throw new LouveTransport.Unreachable("down", null);
        });

        unreachable.revoke("cid", "rt", "refresh_token");
        answer = json(500, "");
        client.revoke("cid", "rt", "refresh_token");

        assertThat(form(sent.get(0).body())).containsEntry("token", "rt").containsEntry("token_type_hint", "refresh_token");
        assertThat(sent.get(0).uri().toString()).isEqualTo("https://app.louveinvest.com/mcp/oauth/revoke");
    }

    private static void assertCode(Runnable call, SidecarErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SyncException.class,
            ex -> assertThat(ex.getCode()).isEqualTo(code.name()));
    }
}
