package com.picsou.adapter;

import com.picsou.exception.SyncException;
import com.picsou.port.WisePort.WiseJar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The client against a fake transport keyed by request URI. */
class WiseClientTest {

    private static final String TOKEN = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
    private static final String PROFILES = WiseClient.API_BASE + "/v2/profiles";
    private static final String PERSONAL_BALANCES = WiseClient.API_BASE + "/v4/profiles/11/balances?types=SAVINGS";
    private static final String BUSINESS_BALANCES = WiseClient.API_BASE + "/v4/profiles/22/balances?types=SAVINGS";

    /** Shape copied from a live response of {@code GET /v4/profiles/{id}/balances}, values changed. */
    private static final String PERSONAL_JARS = """
        [
          {"id": 101, "currency": "EUR", "type": "STANDARD", "name": null, "investmentState": "NOT_INVESTED",
           "amount": {"value": 3399.46, "currency": "EUR"}, "totalWorth": {"value": 3399.46, "currency": "EUR"},
           "visible": true, "primary": true},
          {"id": 102, "currency": "EUR", "type": "SAVINGS", "name": "Coup dur", "investmentState": "NOT_INVESTED",
           "amount": {"value": 2002.08, "currency": "EUR"}, "cashAmount": {"value": 2002.08, "currency": "EUR"},
           "totalWorth": {"value": 2002.08, "currency": "EUR"}, "visible": true, "primary": false},
          {"id": 103, "currency": "usd", "type": "SAVINGS", "name": "Interest", "investmentState": "INVESTED",
           "amount": {"value": 10.00, "currency": "USD"}, "cashAmount": {"value": 0, "currency": "USD"},
           "totalWorth": {"value": 512.34, "currency": "USD"}, "visible": true, "primary": false}
        ]
        """;

    private final FakeTransport transport = new FakeTransport();
    private final WiseClient client = new WiseClient(transport);

    @Test
    void fetchJars_readsEveryProfileAndKeepsOnlySavingsBalances() {
        transport.answer(PROFILES, 200, "[{\"id\": 11, \"type\": \"PERSONAL\"}, {\"id\": 22, \"type\": \"BUSINESS\"}]");
        transport.answer(PERSONAL_BALANCES, 200, PERSONAL_JARS);
        transport.answer(BUSINESS_BALANCES, 200,
            "[{\"id\": 201, \"currency\": \"GBP\", \"type\": \"SAVINGS\", \"name\": \"Tax\","
                + " \"totalWorth\": {\"value\": 7, \"currency\": \"GBP\"}}]");

        List<WiseJar> jars = client.fetchJars(TOKEN);

        assertThat(jars).containsExactly(
            new WiseJar(102, false, "Coup dur", "EUR", new BigDecimal("2002.08")),
            new WiseJar(103, false, "Interest", "USD", new BigDecimal("512.34")),
            new WiseJar(201, true, "Tax", "GBP", new BigDecimal("7")));
        assertThat(transport.requested).containsExactly(
            URI.create(PROFILES), URI.create(PERSONAL_BALANCES), URI.create(BUSINESS_BALANCES));
    }

    @Test
    void fetchJars_sendsTheTokenAsABearerHeaderOnEveryRequest() {
        transport.answer(PROFILES, 200, "[{\"id\": 11, \"type\": \"PERSONAL\"}]");
        transport.answer(PERSONAL_BALANCES, 200, "[]");

        client.fetchJars("  " + TOKEN + "\n");

        assertThat(transport.authorizations).containsOnly("Bearer " + TOKEN).hasSize(2);
        assertThat(transport.requested).allSatisfy(uri -> assertThat(uri.toString()).doesNotContain(TOKEN));
    }

    @Test
    void fetchJars_jarWithoutTotalWorth_fallsBackToItsAmount() {
        transport.answer(PROFILES, 200, "[{\"id\": 11, \"type\": \"PERSONAL\"}]");
        transport.answer(PERSONAL_BALANCES, 200,
            "[{\"id\": 5, \"currency\": \"EUR\", \"type\": \"SAVINGS\", \"name\": \"Voyage\","
                + " \"amount\": {\"value\": 1.11, \"currency\": \"EUR\"}}]");

        assertThat(client.fetchJars(TOKEN)).singleElement()
            .satisfies(jar -> assertThat(jar.totalWorth()).isEqualByComparingTo("1.11"));
    }

    @Test
    void fetchJars_unreadableJarRow_isSkippedAndTheOthersImport() {
        transport.answer(PROFILES, 200, "[{\"id\": 11, \"type\": \"PERSONAL\"}]");
        transport.answer(PERSONAL_BALANCES, 200, """
            [
              {"id": 6, "type": "SAVINGS", "name": "No currency", "totalWorth": {"value": 1, "currency": "EUR"}},
              {"id": 7, "currency": "EUR", "type": "SAVINGS", "name": "Text amount", "totalWorth": {"value": "1"}},
              {"currency": "EUR", "type": "SAVINGS", "name": "No id", "totalWorth": {"value": 1}},
              {"id": 8, "currency": "EUR", "type": "SAVINGS", "name": "Kept", "totalWorth": {"value": 4}}
            ]
            """);

        assertThat(client.fetchJars(TOKEN)).extracting(WiseJar::name).containsExactly("Kept");
    }

    @Test
    void fetchJars_401_asksForANewToken() {
        transport.answer(PROFILES, 401, "{\"error\": \"invalid_token\"}");

        assertThatThrownBy(() -> client.fetchJars(TOKEN))
            .isInstanceOfSatisfying(SyncException.class, ex -> assertThat(ex.getCode()).isEqualTo("SESSION_EXPIRED"))
            .hasMessageContaining("revoked");
    }

    @Test
    void fetchJars_403OnBalances_asksForANewToken() {
        transport.answer(PROFILES, 200, "[{\"id\": 11, \"type\": \"PERSONAL\"}]");
        transport.answer(PERSONAL_BALANCES, 403, "");

        assertThatThrownBy(() -> client.fetchJars(TOKEN))
            .isInstanceOfSatisfying(SyncException.class, ex -> assertThat(ex.getCode()).isEqualTo("SESSION_EXPIRED"));
    }

    @Test
    void fetchJars_429_isAPlainFailureToRetryLater() {
        transport.answer(PROFILES, 429, "");

        assertThatThrownBy(() -> client.fetchJars(TOKEN))
            .isInstanceOfSatisfying(SyncException.class, ex -> assertThat(ex.getCode()).isEqualTo("RATE_LIMITED"))
            .hasMessageContaining("Try again");
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 500, 503})
    void fetchJars_otherStatus_failsWithoutReadingFurther(int status) {
        transport.answer(PROFILES, status, "<html>oops</html>");

        assertThatThrownBy(() -> client.fetchJars(TOKEN)).isInstanceOf(SyncException.class);

        assertThat(transport.requested).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "not json", "{\"profiles\": []}"})
    void fetchJars_profilesNotAnArray_isRefusedWithoutQuotingTheBody(String body) {
        transport.answer(PROFILES, 200, body);

        assertThatThrownBy(() -> client.fetchJars(TOKEN))
            .isInstanceOf(SyncException.class)
            .hasMessage("Wise returned a response Picsou could not read.");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "Bearer abc", "abc\r\nX-Injected: 1", "jeton avec espaces"})
    void fetchJars_malformedToken_sendsNothing(String token) {
        assertThatThrownBy(() -> client.fetchJars(token))
            .isInstanceOfSatisfying(SyncException.class, ex -> assertThat(ex.getCode()).isEqualTo("INVALID_TOKEN"));

        assertThat(transport.requested).isEmpty();
    }

    @Test
    void fetchJars_tokenLongerThanTheLimit_sendsNothing() {
        assertThatThrownBy(() -> client.fetchJars("a".repeat(513))).isInstanceOf(SyncException.class);

        assertThat(transport.requested).isEmpty();
    }

    private static final class FakeTransport implements WiseTransport {
        private final Map<URI, Response> answers = new HashMap<>();
        final List<URI> requested = new ArrayList<>();
        final List<String> authorizations = new ArrayList<>();

        void answer(String uri, int status, String body) {
            answers.put(URI.create(uri), new Response(status, body));
        }

        @Override
        public Response get(URI uri, String authorization, int maxBody) {
            requested.add(uri);
            authorizations.add(authorization);
            Response response = answers.get(uri);
            if (response == null) throw new AssertionError("unexpected request " + uri);
            return response;
        }
    }
}
