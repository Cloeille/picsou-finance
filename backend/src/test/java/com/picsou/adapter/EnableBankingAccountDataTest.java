package com.picsou.adapter;

import com.picsou.config.EnableBankingConfigProvider;
import com.picsou.exception.SyncException;
import com.picsou.port.BankConnectorPort.AccountData;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

@ExtendWith(MockitoExtension.class)
class EnableBankingAccountDataTest {

    @Mock EnableBankingConfigProvider configProvider;

    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final Map<String, Integer> responseStatuses = new ConcurrentHashMap<>();
    private HttpServer server;
    private EnableBankingBankConnector connector;

    @BeforeEach
    void startProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String response = responses.get(exchange.getRequestURI().getPath());
            byte[] body = (response != null ? response : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatuses.getOrDefault(exchange.getRequestURI().getPath(),
                response != null ? 200 : 404), body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        connector = spy(new EnableBankingBankConnector(configProvider,
            "http://127.0.0.1:" + server.getAddress().getPort()));
        doReturn("synthetic-jwt").when(connector).buildJwt();
        responses.put("/sessions/session", """
            {"accounts": ["uid-operations"]}
            """);
    }

    @AfterEach
    void stopProvider() {
        if (server != null) server.stop(0);
    }

    @Test
    void fetchBalances_organizationWideBalances_returnsEachAccountsOwnBalanceAndFlatDetails() {
        responses.put("/sessions/session", """
            {"accounts": ["uid-operations", "uid-reserve"]}
            """);
        String balances = """
            {"balances": [
              {"name": "Reserve wallet", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "41.75"}},
              {"name": "Operations wallet", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "903.20"}}
            ]}
            """;
        responses.put("/accounts/uid-operations/balances", balances);
        responses.put("/accounts/uid-reserve/balances", balances);
        responses.put("/accounts/uid-operations/details", """
            {"account_id": {"iban": "FR00SYNTHETICOPERATIONS"}, "name": "Synthetic company",
             "details": "Operations wallet", "product": null, "cash_account_type": "CACC",
             "uid": "uid-operations", "identification_hash": "synthetic-operations"}
            """);
        responses.put("/accounts/uid-reserve/details", """
            {"account_id": {"iban": "FR00SYNTHETICRESERVE"}, "name": "Synthetic company",
             "details": "Reserve wallet", "product": null, "cash_account_type": "CACC",
             "uid": "uid-reserve", "identification_hash": "synthetic-reserve"}
            """);

        var accounts = connector.fetchBalances("session");

        assertThat(accounts).hasSize(2);
        assertThat(accounts.get(0)).satisfies(account -> {
            assertThat(account.externalId()).isEqualTo("uid-operations");
            assertThat(account.name()).isEqualTo("Operations wallet");
            assertThat(account.iban()).isEqualTo("FR00SYNTHETICOPERATIONS");
            assertThat(account.currency()).isEqualTo("EUR");
            assertThat(account.balance()).isEqualByComparingTo("903.20");
        });
        assertThat(accounts.get(1)).satisfies(account -> {
            assertThat(account.externalId()).isEqualTo("uid-reserve");
            assertThat(account.name()).isEqualTo("Reserve wallet");
            assertThat(account.iban()).isEqualTo("FR00SYNTHETICRESERVE");
            assertThat(account.currency()).isEqualTo("EUR");
            assertThat(account.balance()).isEqualByComparingTo("41.75");
        });
    }

    @Test
    void fetchBalances_matchingAccountLabel_takesPriorityOverAnotherAccountsBalanceType() {
        responses.put("/accounts/uid-operations/details", """
            {"details": "  Operations wallet  ", "product": "Shared product", "name": "Shared holder"}
            """);
        responses.put("/accounts/uid-operations/balances", """
            {"balances": [
              {"name": "Reserve wallet", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "41.75"}},
              {"name": "  OPERATIONS WALLET ", "balance_type": "ITAV",
               "balance_amount": {"currency": "EUR", "amount": "902.20"}},
              {"name": "Operations wallet", "balance_type": "XPCD",
               "balance_amount": {"currency": "EUR", "amount": "903.20"}}
            ]}
            """);

        assertThat(connector.fetchBalances("session")).singleElement().satisfies(account -> {
            assertThat(account.name()).isEqualTo("Operations wallet");
            assertThat(account.balance()).isEqualByComparingTo("903.20");
        });
    }

    @ParameterizedTest
    @CsvSource({"CLBD,XPCD", "XPCD,ITBD", "ITBD,ITAV", "ITAV,CLAV"})
    void fetchBalances_singleAccountWithGenericBalanceLabels_usesIsoTypePriority(String preferred, String other) {
        responses.put("/accounts/uid-operations/details", """
            {"details": "Everyday account", "account_id": {"iban": "GB00SYNTHETIC"}}
            """);
        responses.put("/accounts/uid-operations/balances", """
            {"balances": [
              {"name": " Bank balance ", "balance_type": "%s",
               "balance_amount": {"currency": "GBP", "amount": "44.10"}},
              {"name": "BANK BALANCE", "balance_type": "%s",
               "balance_amount": {"currency": "GBP", "amount": "-12.34"}}
            ]}
            """.formatted(other, preferred));

        assertThat(connector.fetchBalances("session")).singleElement().satisfies(account -> {
            assertThat(account.name()).isEqualTo("Everyday account");
            assertThat(account.iban()).isEqualTo("GB00SYNTHETIC");
            assertThat(account.currency()).isEqualTo("GBP");
            assertThat(account.balance()).isEqualByComparingTo("-12.34");
        });
    }

    @Test
    void fetchBalances_unnamedAccountBalances_usesIsoTypePriority() {
        responses.put("/accounts/uid-operations/details", "{}");
        responses.put("/accounts/uid-operations/balances", """
            {"balances": [
              {"balance_type": "XPCD", "balance_amount": {"currency": "EUR", "amount": "44.10"}},
              {"name": "  ", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "-12.34"}}
            ]}
            """);

        assertThat(connector.fetchBalances("session")).singleElement()
            .satisfies(account -> assertThat(account.balance()).isEqualByComparingTo("-12.34"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"details", "product", "name"})
    void fetchBalances_oversizedDisplayName_fitsTheAccountNameColumn(String field) {
        responses.put("/accounts/uid-operations/details", "{\"%s\":\"%s\"}".formatted(field, "A".repeat(101)));
        responses.put("/accounts/uid-operations/balances", """
            {"balances": [{"balance_type": "CLBD", "balance_amount": {"currency": "EUR", "amount": "24.60"}}]}
            """);

        assertThat(connector.fetchBalances("session")).singleElement().satisfies(account -> {
            assertThat(account.name()).isEqualTo("A".repeat(100));
            assertThat(account.balance()).isEqualByComparingTo("24.60");
        });
    }

    @Test
    void fetchBalances_oversizedDescription_matchesTheFullLabelBeforeTruncatingTheDisplayName() {
        String prefix = "A".repeat(100);
        responses.put("/accounts/uid-operations/details", "{\"details\":\"%s Operations\"}".formatted(prefix));
        responses.put("/accounts/uid-operations/balances", """
            {"balances": [
              {"name": "%s Reserve", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "41.75"}},
              {"name": "%s Operations", "balance_type": "XPCD",
               "balance_amount": {"currency": "EUR", "amount": "903.20"}}
            ]}
            """.formatted(prefix, prefix));

        assertThat(connector.fetchBalances("session")).singleElement().satisfies(account -> {
            assertThat(account.name()).isEqualTo(prefix);
            assertThat(account.balance()).isEqualByComparingTo("903.20");
        });
    }

    @ParameterizedTest
    @CsvSource(value = {
        "{\"details\":\"  \",\"product\":\" Savings product \",\"name\":\"Synthetic holder\"}|Savings product",
        "{\"product\":\"  \",\"name\":\" Synthetic holder \"}|Synthetic holder",
        "{\"details\":\" \",\"product\":\" \",\"name\":\" \"}|Account",
        "{}|Account"
    }, delimiter = '|')
    void fetchBalances_flatDetails_usesFirstNonblankDisplayName(String details, String expectedName) {
        responses.put("/accounts/uid-operations/details", details);
        responses.put("/accounts/uid-operations/balances", """
            {"balances": [{"balance_type": "CLBD", "balance_amount": {"currency": "EUR", "amount": "0"}}]}
            """);

        assertThat(connector.fetchBalances("session")).singleElement().satisfies(account -> {
            assertThat(account.name()).isEqualTo(expectedName);
            assertThat(account.iban()).isNull();
            assertThat(account.balance()).isZero();
        });
    }

    @ParameterizedTest
    @CsvSource(value = {"{\"currency\":\"USD\"}|USD", "{}|EUR"}, delimiter = '|')
    void fetchBalances_missingBalanceCurrency_usesAccountCurrencyThenEur(String details, String expectedCurrency) {
        responses.put("/accounts/uid-operations/details", details);
        responses.put("/accounts/uid-operations/balances", """
            {"balances": [{"balance_type": "CLBD", "balance_amount": {"amount": "24.60"}}]}
            """);

        assertThat(connector.fetchBalances("session")).singleElement()
            .extracting(AccountData::currency).isEqualTo(expectedCurrency);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"OTHR\"", "null"})
    void fetchBalances_singleUnknownBalanceType_remainsUsable(String type) {
        responses.put("/accounts/uid-operations/details", "{}");
        responses.put("/accounts/uid-operations/balances", """
            {"balances": [{"name": "Bank balance", "balance_type": %s,
             "balance_amount": {"currency": "EUR", "amount": "17.05"}}]}
            """.formatted(type));

        assertThat(connector.fetchBalances("session")).singleElement()
            .satisfies(account -> assertThat(account.balance()).isEqualByComparingTo("17.05"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"details\":\"Unmatched wallet\"}", "{}"})
    void fetchBalances_ambiguousAccountBalances_failsInsteadOfPickingAnotherAccount(String details) {
        responses.put("/accounts/uid-operations/details", details);
        responses.put("/accounts/uid-operations/balances", """
            {"balances": [
              {"name": "Reserve wallet", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "41.75"}},
              {"name": "Operations wallet", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "903.20"}}
            ]}
            """);

        assertThatThrownBy(() -> connector.fetchBalances("session"))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("ambiguous");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"details\":\"Unmatched wallet\"}",
        "{}",
        "{\"details\":\"  \"}",
        "{\"product\":\"Operations wallet\",\"name\":\"Operations wallet\"}"
    })
    void fetchBalances_unmatchedSharedListWithDifferentTypes_failsBeforeRankingBalances(String details) {
        responses.put("/accounts/uid-operations/details", details);
        responses.put("/accounts/uid-operations/balances", """
            {"balances": [
              {"name": "Operations wallet", "balance_type": "XPCD",
               "balance_amount": {"currency": "EUR", "amount": "903.20"}},
              {"name": "Reserve wallet", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "41.75"}}
            ]}
            """);

        assertThatThrownBy(() -> connector.fetchBalances("session"))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("ambiguous");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"aspsp\":{\"name\":\"Qonto\"}"})
    void fetchBalances_sharedOrUnknownProviderWithDistinctLabels_failsInsteadOfAssumingAccountScope(String provider) {
        responses.put("/sessions/session", "{\"accounts\":[\"uid-operations\"]" + provider + "}");
        responses.put("/accounts/uid-operations/details", "{\"details\":\"Everyday account\"}");
        responses.put("/accounts/uid-operations/balances", """
            {"balances": [
              {"name": "Other balance", "balance_type": "XPCD",
               "balance_amount": {"currency": "GBP", "amount": "44.10"}},
              {"name": "Preferred balance", "balance_type": "CLBD",
               "balance_amount": {"currency": "GBP", "amount": "-12.34"}}
            ]}
            """);

        assertThatThrownBy(() -> connector.fetchBalances("session"))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("ambiguous");
    }

    @Test
    void fetchBalances_duplicateMatchingLabels_failsWhenTheBestBalanceIsStillAmbiguous() {
        responses.put("/accounts/uid-operations/details", "{\"details\":\"Operations wallet\"}");
        responses.put("/accounts/uid-operations/balances", """
            {"balances": [
              {"name": "Operations wallet", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "41.75"}},
              {"name": "Operations wallet", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "903.20"}}
            ]}
            """);

        assertThatThrownBy(() -> connector.fetchBalances("session"))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("ambiguous");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"details\":null}", "{\"details\":\"  \"}", "{\"details\":\"Unmatched wallet\"}"})
    void fetchBalances_unmatchedAccountWithOnlySiblingBalances_skipsThatAccount(String details) {
        responses.put("/sessions/session", "{\"accounts\":[\"uid-unmatched\",\"uid-operations\"]}");
        responses.put("/accounts/uid-unmatched/details", details);
        responses.put("/accounts/uid-operations/details", "{\"details\":\" Operations wallet \"}");
        String balances = """
            {"balances": [
              {"name": "OPERATIONS WALLET", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "100"}},
              {"name": "Operations wallet", "balance_type": "ITAV",
               "balance_amount": {"currency": "EUR", "amount": "90"}}
            ]}
            """;
        responses.put("/accounts/uid-unmatched/balances", balances);
        responses.put("/accounts/uid-operations/balances", balances);

        assertThat(connector.fetchBalances("session")).singleElement().satisfies(account -> {
            assertThat(account.externalId()).isEqualTo("uid-operations");
            assertThat(account.balance()).isEqualByComparingTo("100");
        });
    }

    @Test
    void fetchBalances_duplicateSessionDescriptions_skipTheirBalancesAndKeepUniquelyLabelledAccount() {
        responses.put("/sessions/session", "{\"accounts\":[\"uid-shared-a\",\"uid-reserve\",\"uid-shared-b\"]}");
        responses.put("/accounts/uid-shared-a/details", """
            {"details":"Shared wallet", "account_id":{"iban":"FR00SYNTHETICSHAREDA"}}
            """);
        responses.put("/accounts/uid-shared-b/details", """
            {"details":" SHARED WALLET ", "account_id":{"iban":"FR00SYNTHETICSHAREDB"}}
            """);
        responses.put("/accounts/uid-reserve/details", """
            {"details":"Reserve wallet", "account_id":{"iban":"FR00SYNTHETICRESERVE"}}
            """);
        String balances = """
            {"balances": [
              {"name": "Shared wallet", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "100"}},
              {"name": "Shared wallet", "balance_type": "ITAV",
               "balance_amount": {"currency": "EUR", "amount": "200"}},
              {"name": "Reserve wallet", "balance_type": "XPCD",
               "balance_amount": {"currency": "EUR", "amount": "41.75"}}
            ]}
            """;
        responses.put("/accounts/uid-shared-a/balances", balances);
        responses.put("/accounts/uid-shared-b/balances", balances);
        responses.put("/accounts/uid-reserve/balances", balances);

        assertThat(connector.fetchBalances("session")).singleElement().satisfies(account -> {
            assertThat(account.externalId()).isEqualTo("uid-reserve");
            assertThat(account.iban()).isEqualTo("FR00SYNTHETICRESERVE");
            assertThat(account.balance()).isEqualByComparingTo("41.75");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[\"uid-shared-a\",\"uid-unlabelled\",\"uid-shared-b\"]",
        "[\"uid-unlabelled\",\"uid-shared-b\",\"uid-shared-a\"]"
    })
    void fetchBalances_competingFallbacks_rejectsEveryContenderRegardlessOfSessionOrder(String accounts) {
        responses.put("/sessions/session", "{\"accounts\":" + accounts + "}");
        responses.put("/accounts/uid-shared-a/details", "{\"details\":\"Shared wallet\"}");
        responses.put("/accounts/uid-shared-b/details", "{\"details\":\" SHARED WALLET \"}");
        responses.put("/accounts/uid-unlabelled/details", "{}");
        String balances = """
            {"balances": [
              {"name":"Shared wallet", "balance_type":"CLBD",
               "balance_amount":{"currency":"EUR", "amount":"100"}},
              {"name":"Shared wallet", "balance_type":"ITAV",
               "balance_amount":{"currency":"EUR", "amount":"200"}},
              {"name":"Reserve wallet", "balance_type":"XPCD",
               "balance_amount":{"currency":"EUR", "amount":"300"}}
            ]}
            """;
        responses.put("/accounts/uid-shared-a/balances", balances);
        responses.put("/accounts/uid-shared-b/balances", balances);
        responses.put("/accounts/uid-unlabelled/balances", balances);

        assertThatThrownBy(() -> connector.fetchBalances("session"))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("ambiguous");
    }

    @Test
    void fetchBalances_sharedListWithChangingAmounts_rejectsEveryFallbackContender() {
        responses.put("/sessions/session", "{\"accounts\":[\"uid-shared-a\",\"uid-shared-b\",\"uid-unlabelled\"]}");
        responses.put("/accounts/uid-shared-a/details", "{\"details\":\"Shared wallet\"}");
        responses.put("/accounts/uid-shared-b/details", "{\"details\":\" SHARED WALLET \"}");
        responses.put("/accounts/uid-unlabelled/details", "{}");
        String balances = """
            {"balances": [
              {"name":"Shared wallet", "balance_type":"CLBD",
               "balance_amount":{"currency":"EUR", "amount":"100"}},
              {"name":"Shared wallet", "balance_type":"ITAV",
               "balance_amount":{"currency":"EUR", "amount":"200"}},
              {"name":"Reserve wallet", "balance_type":"XPCD",
               "balance_amount":{"currency":"EUR", "amount":"%s"}}
            ]}
            """;
        responses.put("/accounts/uid-shared-a/balances", balances.formatted("300"));
        responses.put("/accounts/uid-shared-b/balances", balances.formatted("301"));
        responses.put("/accounts/uid-unlabelled/balances", balances.formatted("302"));

        assertThatThrownBy(() -> connector.fetchBalances("session"))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("ambiguous");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"name\":\" RESERVE WALLET \",\"balance_type\":\"CLBD\",\"balance_amount\":{\"amount\":\"unreadable\"}}",
        "{\"name\":\" RESERVE WALLET \",\"balance_type\":\"CLBD\"}",
        "{\"name\":\" RESERVE WALLET \",\"balance_type\":\"CLBD\",\"balance_amount\":{\"amount\":\"200\"}},"
            + "{\"name\":\"Another wallet\",\"balance_type\":\"ITAV\",\"balance_amount\":{\"amount\":\"90\"}}"
    })
    void fetchBalances_invalidFallbackContender_doesNotGrantOwnershipToSibling(String invalidCandidates) {
        responses.put("/sessions/session", "{\"accounts\":[\"uid-invalid\",\"uid-reserve\"]}");
        responses.put("/accounts/uid-invalid/details", "{}");
        responses.put("/accounts/uid-reserve/details", "{}");
        responses.put("/accounts/uid-invalid/balances", "{\"balances\":[" + invalidCandidates + "]}");
        responses.put("/accounts/uid-reserve/balances", """
            {"balances": [{"name":"Reserve wallet", "balance_type":"XPCD",
              "balance_amount":{"currency":"EUR", "amount":"300"}}]}
            """);

        assertThatThrownBy(() -> connector.fetchBalances("session"))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("ambiguous");
    }

    @Test
    void fetchBalances_distinctFallbackScopes_keepsBothAccountsSyncable() {
        responses.put("/sessions/session", "{\"accounts\":[\"uid-operations\",\"uid-reserve\"]}");
        responses.put("/accounts/uid-operations/details", "{}");
        responses.put("/accounts/uid-reserve/details", "{}");
        responses.put("/accounts/uid-operations/balances", """
            {"balances": [
              {"name":"Booked balance", "balance_type":"ITAV",
               "balance_amount":{"currency":"EUR", "amount":"90"}},
              {"name":" BOOKED BALANCE ", "balance_type":"CLBD",
               "balance_amount":{"currency":"EUR", "amount":"100"}}
            ]}
            """);
        responses.put("/accounts/uid-reserve/balances", """
            {"balances": [{"balance_type":"CLBD",
              "balance_amount":{"currency":"EUR", "amount":"300"}}]}
            """);

        var accounts = connector.fetchBalances("session");

        assertThat(accounts).extracting(AccountData::externalId).containsExactly("uid-operations", "uid-reserve");
        assertThat(accounts.get(0).balance()).isEqualByComparingTo("100");
        assertThat(accounts.get(1).balance()).isEqualByComparingTo("300");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"Booked balance\"", "\" BOOKED BALANCE \"", "null", "\"  \""})
    void fetchBalances_accountScopedProviderWithGenericLabels_keepsBothAccountsSyncable(String label) {
        responses.put("/sessions/session", """
            {"accounts":["uid-current","uid-savings"], "aspsp":{"name":"Synthetic Bank", "country":"FR"}}
            """);
        responses.put("/accounts/uid-current/details", "{\"details\":\"Current account\"}");
        responses.put("/accounts/uid-savings/details", "{\"details\":\"Savings account\"}");
        responses.put("/accounts/uid-current/balances", """
            {"balances": [{"name":%s, "balance_type":"CLBD",
              "balance_amount":{"currency":"EUR", "amount":"100"}}]}
            """.formatted(label));
        responses.put("/accounts/uid-savings/balances", """
            {"balances": [{"name":%s, "balance_type":"CLBD",
              "balance_amount":{"currency":"EUR", "amount":"200"}}]}
            """.formatted(label));

        var accounts = connector.fetchBalances("session");

        assertThat(accounts).extracting(AccountData::externalId).containsExactly("uid-current", "uid-savings");
        assertThat(accounts.get(0).balance()).isEqualByComparingTo("100");
        assertThat(accounts.get(1).balance()).isEqualByComparingTo("200");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"aspsp\":{\"name\":\" QONTO \",\"country\":\"DE\"}"})
    void fetchBalances_sharedListWithSingleGenericLabel_rejectsEveryFallbackContender(String provider) {
        responses.put("/sessions/session", "{\"accounts\":[\"uid-current\",\"uid-savings\"]" + provider + "}");
        responses.put("/accounts/uid-current/details", "{\"details\":\"Current account\"}");
        responses.put("/accounts/uid-savings/details", "{\"details\":\"Savings account\"}");
        String balances = """
            {"balances": [
              {"name":"Bank balance", "balance_type":"CLBD",
               "balance_amount":{"currency":"EUR", "amount":"100"}},
              {"name":"Bank balance", "balance_type":"ITAV",
               "balance_amount":{"currency":"EUR", "amount":"200"}}
            ]}
            """;
        responses.put("/accounts/uid-current/balances", balances);
        responses.put("/accounts/uid-savings/balances", balances);

        assertThatThrownBy(() -> connector.fetchBalances("session"))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("ambiguous");
    }

    @Test
    void fetchBalances_accountScopedProviderWithGenericZeroBalances_keepsBothAccountsSyncable() {
        responses.put("/sessions/session", """
            {"accounts":["uid-current","uid-savings"], "aspsp":{"name":"Synthetic Bank", "country":"FR"}}
            """);
        responses.put("/accounts/uid-current/details", "{\"details\":\"Current account\"}");
        responses.put("/accounts/uid-savings/details", "{\"details\":\"Savings account\"}");
        responses.put("/accounts/uid-current/balances", """
            {"balances": [{"name":"Booked balance", "balance_type":"CLBD",
              "balance_amount":{"currency":"EUR", "amount":"0"}}]}
            """);
        responses.put("/accounts/uid-savings/balances", """
            {"balances": [{"name":"Booked balance", "balance_type":"CLBD",
              "balance_amount":{"currency":"EUR", "amount":"0.00"}}]}
            """);

        var accounts = connector.fetchBalances("session");

        assertThat(accounts).extracting(AccountData::externalId).containsExactly("uid-current", "uid-savings");
        assertThat(accounts).allSatisfy(account -> {
            assertThat(account.currency()).isEqualTo("EUR");
            assertThat(account.balance()).isZero();
        });
    }

    @Test
    void fetchBalances_accountScopedProviderWithGenericTypes_ranksEachEndpointIndependently() {
        responses.put("/sessions/session", """
            {"accounts":["uid-current","uid-savings"], "aspsp":{"name":"Synthetic Bank", "country":"FR"}}
            """);
        responses.put("/accounts/uid-current/details", "{\"details\":\"Current account\"}");
        responses.put("/accounts/uid-savings/details", "{\"details\":\"Savings account\"}");
        String balances = """
            {"balances": [
              {"name":"Booked balance", "balance_type":"ITAV",
               "balance_amount":{"currency":"EUR", "amount":"%s"}},
              {"name":" BOOKED BALANCE ", "balance_type":"CLBD",
               "balance_amount":{"currency":"EUR", "amount":"%s"}}
            ]}
            """;
        responses.put("/accounts/uid-current/balances", balances.formatted("90", "100"));
        responses.put("/accounts/uid-savings/balances", balances.formatted("190", "200"));

        var accounts = connector.fetchBalances("session");

        assertThat(accounts).extracting(AccountData::externalId).containsExactly("uid-current", "uid-savings");
        assertThat(accounts.get(0).balance()).isEqualByComparingTo("100");
        assertThat(accounts.get(1).balance()).isEqualByComparingTo("200");
    }

    @Test
    void fetchBalances_namedBankWithDistinctBalanceTypeLabels_selectsClosingBookedBalance() {
        responses.put("/sessions/session", """
            {"accounts":["uid-operations"], "aspsp":{"name":"Synthetic Bank"}}
            """);
        responses.put("/accounts/uid-operations/details", "{\"details\":\"Compte courant\"}");
        responses.put("/accounts/uid-operations/balances", """
            {"balances": [
              {"name":"Solde instantane", "balance_type":"ITAV",
               "balance_amount":{"currency":"EUR", "amount":"90.00"}},
              {"name":"Solde comptable", "balance_type":"CLBD",
               "balance_amount":{"currency":"EUR", "amount":"100.00"}}
            ]}
            """);

        assertThat(connector.fetchBalances("session")).singleElement().satisfies(account -> {
            assertThat(account.externalId()).isEqualTo("uid-operations");
            assertThat(account.name()).isEqualTo("Compte courant");
            assertThat(account.currency()).isEqualTo("EUR");
            assertThat(account.balance()).isEqualByComparingTo("100.00");
        });
    }

    @Test
    void fetchBalances_namedBankWithDistinctBalanceTypeLabels_ranksEachEndpointIndependently() {
        responses.put("/sessions/session", """
            {"accounts":["uid-current","uid-savings"], "aspsp":{"name":"Synthetic Bank"}}
            """);
        responses.put("/accounts/uid-current/details", "{\"details\":\"Current account\"}");
        responses.put("/accounts/uid-savings/details", "{\"details\":\"Savings account\"}");
        String balances = """
            {"balances": [
              {"name":"Solde instantane", "balance_type":"ITAV",
               "balance_amount":{"currency":"EUR", "amount":"%s"}},
              {"name":"Solde comptable", "balance_type":"CLBD",
               "balance_amount":{"currency":"EUR", "amount":"%s"}}
            ]}
            """;
        responses.put("/accounts/uid-current/balances", balances.formatted("90", "100"));
        responses.put("/accounts/uid-savings/balances", balances.formatted("190", "200"));

        var accounts = connector.fetchBalances("session");

        assertThat(accounts).extracting(AccountData::externalId).containsExactly("uid-current", "uid-savings");
        assertThat(accounts.get(0).balance()).isEqualByComparingTo("100");
        assertThat(accounts.get(1).balance()).isEqualByComparingTo("200");
    }

    @Test
    void fetchBalances_namedBankWithSharedAccountDescriptions_ranksEachEndpointIndependently() {
        responses.put("/sessions/session", """
            {"accounts":["uid-savings-a","uid-savings-b"], "aspsp":{"name":"Synthetic Bank"}}
            """);
        responses.put("/accounts/uid-savings-a/details", "{\"details\":\"LIVRET A\"}");
        responses.put("/accounts/uid-savings-b/details", "{\"details\":\" livret a \"}");
        responses.put("/accounts/uid-savings-a/balances", """
            {"balances": [{"name":" livret a ", "balance_type":"CLBD",
              "balance_amount":{"currency":"EUR", "amount":"100"}}]}
            """);
        responses.put("/accounts/uid-savings-b/balances", """
            {"balances": [{"name":"LIVRET A", "balance_type":"CLBD",
              "balance_amount":{"currency":"EUR", "amount":"200"}}]}
            """);

        var accounts = connector.fetchBalances("session");

        assertThat(accounts).extracting(AccountData::externalId)
            .containsExactly("uid-savings-a", "uid-savings-b");
        assertThat(accounts.get(0).balance()).isEqualByComparingTo("100");
        assertThat(accounts.get(1).balance()).isEqualByComparingTo("200");
    }

    @ParameterizedTest
    @CsvSource({"CLBD,CLBD", "OPBD,OTHR"})
    void fetchBalances_namedBankWithTiedPreferredTypes_rejectsArbitrarySelection(
        String firstType,
        String secondType
    ) {
        responses.put("/sessions/session", """
            {"accounts":["uid-operations"], "aspsp":{"name":"Synthetic Bank"}}
            """);
        responses.put("/accounts/uid-operations/details", "{\"details\":\"Operations account\"}");
        responses.put("/accounts/uid-operations/balances", """
            {"balances": [
              {"name":"Operations account", "balance_type":"%s",
               "balance_amount":{"currency":"EUR", "amount":"100"}},
              {"name":"Operations account", "balance_type":"%s",
               "balance_amount":{"currency":"EUR", "amount":"200"}}
            ]}
            """.formatted(firstType, secondType));

        assertThatThrownBy(() -> connector.fetchBalances("session"))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("ambiguous");
    }

    @Test
    void fetchBalances_namedBankWithSiblingClaim_excludesSiblingBeforeRanking() {
        responses.put("/sessions/session", """
            {"accounts":["uid-current","uid-savings"], "aspsp":{"name":"Synthetic Bank"}}
            """);
        responses.put("/accounts/uid-current/details", "{\"details\":\"Current account\"}");
        responses.put("/accounts/uid-savings/details", "{\"details\":\"Savings account\"}");
        String balances = """
            {"balances": [
              {"name":"Solde instantane", "balance_type":"ITAV",
               "balance_amount":{"currency":"EUR", "amount":"100"}},
              {"name":"Savings account", "balance_type":"CLBD",
               "balance_amount":{"currency":"EUR", "amount":"200"}}
            ]}
            """;
        responses.put("/accounts/uid-current/balances", balances);
        responses.put("/accounts/uid-savings/balances", balances);

        var accounts = connector.fetchBalances("session");

        assertThat(accounts).extracting(AccountData::externalId).containsExactly("uid-current", "uid-savings");
        assertThat(accounts.get(0).balance()).isEqualByComparingTo("100");
        assertThat(accounts.get(1).balance()).isEqualByComparingTo("200");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        ",\"aspsp\":null",
        ",\"aspsp\":{}",
        ",\"aspsp\":{\"name\":\"  \"}",
        ",\"aspsp\":{\"name\":\"Qonto\",\"country\":\"FR\"}"
    })
    void fetchBalances_sharedOrUnknownProvider_cannotUseOverlappingGenericZeros(String provider) {
        responses.put("/sessions/session", "{\"accounts\":[\"uid-current\",\"uid-savings\"]" + provider + "}");
        responses.put("/accounts/uid-current/details", "{\"details\":\"Current account\"}");
        responses.put("/accounts/uid-savings/details", "{\"details\":\"Savings account\"}");
        String balances = """
            {"balances": [{"name":"Booked balance", "balance_type":"CLBD",
              "balance_amount":{"currency":"EUR", "amount":"%s"}}]}
            """;
        responses.put("/accounts/uid-current/balances", balances.formatted("0"));
        responses.put("/accounts/uid-savings/balances", balances.formatted("0.00"));

        assertThatThrownBy(() -> connector.fetchBalances("session"))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("ambiguous");
    }

    @Test
    void fetchBalances_independentEmptyAccountsWithUniqueDescriptions_keepsBothAccountsSyncable() {
        responses.put("/sessions/session", "{\"accounts\":[\"uid-current\",\"uid-savings\"]}");
        responses.put("/accounts/uid-current/details", "{\"details\":\"Current account\"}");
        responses.put("/accounts/uid-savings/details", "{\"details\":\"Savings account\"}");
        responses.put("/accounts/uid-current/balances", """
            {"balances": [{"name":"Current account", "balance_type":"CLBD",
              "balance_amount":{"currency":"EUR", "amount":"0"}}]}
            """);
        responses.put("/accounts/uid-savings/balances", """
            {"balances": [{"name":"Savings account", "balance_type":"CLBD",
              "balance_amount":{"currency":"EUR", "amount":"0.00"}}]}
            """);

        var accounts = connector.fetchBalances("session");

        assertThat(accounts).extracting(AccountData::externalId).containsExactly("uid-current", "uid-savings");
        assertThat(accounts).allSatisfy(account -> {
            assertThat(account.currency()).isEqualTo("EUR");
            assertThat(account.balance()).isZero();
        });
    }

    @Test
    void fetchBalances_overlappingGenericLabels_rejectsBothBeforeTypeRanking() {
        responses.put("/sessions/session", "{\"accounts\":[\"uid-operations\",\"uid-reserve\"]}");
        responses.put("/accounts/uid-operations/details", "{}");
        responses.put("/accounts/uid-reserve/details", "{}");
        responses.put("/accounts/uid-operations/balances", """
            {"balances": [
              {"name":"Booked balance", "balance_type":"ITAV",
               "balance_amount":{"currency":"EUR", "amount":"90"}},
              {"name":" BOOKED BALANCE ", "balance_type":"CLBD",
               "balance_amount":{"currency":"EUR", "amount":"100"}}
            ]}
            """);
        responses.put("/accounts/uid-reserve/balances", """
            {"balances": [
              {"name":"Booked balance", "balance_type":"CLBD",
               "balance_amount":{"currency":"EUR", "amount":"200"}},
              {"name":"Booked balance", "balance_type":"ITAV",
               "balance_amount":{"currency":"EUR", "amount":"190"}}
            ]}
            """);

        assertThatThrownBy(() -> connector.fetchBalances("session"))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("ambiguous");
    }

    @Test
    void fetchBalances_uniqueDescriptions_ranksBalancesWithinEachResponse() {
        responses.put("/sessions/session", "{\"accounts\":[\"uid-operations\",\"uid-reserve\"]}");
        responses.put("/accounts/uid-operations/details", "{\"details\":\"Operations wallet\"}");
        responses.put("/accounts/uid-reserve/details", "{\"details\":\"Reserve wallet\"}");
        responses.put("/accounts/uid-operations/balances", """
            {"balances": [
              {"name":"Operations wallet", "balance_type":"ITAV",
               "balance_amount":{"currency":"EUR", "amount":"90"}},
              {"name":" OPERATIONS WALLET ", "balance_type":"CLBD",
               "balance_amount":{"currency":"EUR", "amount":"100"}}
            ]}
            """);
        responses.put("/accounts/uid-reserve/balances", """
            {"balances": [
              {"name":"Reserve wallet", "balance_type":"CLBD",
               "balance_amount":{"currency":"EUR", "amount":"200"}},
              {"name":"Reserve wallet", "balance_type":"ITAV",
               "balance_amount":{"currency":"EUR", "amount":"190"}}
            ]}
            """);

        var accounts = connector.fetchBalances("session");

        assertThat(accounts).extracting(AccountData::externalId).containsExactly("uid-operations", "uid-reserve");
        assertThat(accounts.get(0)).satisfies(account -> {
            assertThat(account.name()).isEqualTo("Operations wallet");
            assertThat(account.currency()).isEqualTo("EUR");
            assertThat(account.balance()).isEqualByComparingTo("100");
        });
        assertThat(accounts.get(1)).satisfies(account -> {
            assertThat(account.name()).isEqualTo("Reserve wallet");
            assertThat(account.currency()).isEqualTo("EUR");
            assertThat(account.balance()).isEqualByComparingTo("200");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"90", "90.00", "9E+1", "91"})
    void fetchBalances_partiallySharedGenericScopes_rejectsBothBeforeRanking(String sharedAmount) {
        responses.put("/sessions/session", """
            {"accounts":["uid-operations","uid-reserve"], "aspsp":{"name":"Synthetic Bank", "country":"FR"}}
            """);
        responses.put("/accounts/uid-operations/details", "{\"details\":\"Shared wallet\"}");
        responses.put("/accounts/uid-reserve/details", "{\"details\":\" SHARED WALLET \"}");
        responses.put("/accounts/uid-operations/balances", """
            {"balances": [
              {"name":"Shared wallet", "balance_type":"CLBD",
               "balance_amount":{"currency":"EUR", "amount":"300"}},
              {"name":"Booked balance", "balance_type":"CLBD",
               "balance_amount":{"currency":"EUR", "amount":"100"}},
              {"name":"Booked balance", "balance_type":"ITAV",
               "balance_amount":{"currency":"EUR", "amount":"90"}}
            ]}
            """);
        responses.put("/accounts/uid-reserve/balances", """
            {"balances": [
              {"name":"Shared wallet", "balance_type":"CLBD",
               "balance_amount":{"currency":"EUR", "amount":"301"}},
              {"name":"Booked balance", "balance_type":"CLBD",
               "balance_amount":{"currency":"EUR", "amount":"200"}},
              {"name":" BOOKED BALANCE ", "balance_type":"ITAV",
               "balance_amount":{"currency":"EUR", "amount":"%s"}}
            ]}
            """.formatted(sharedAmount));

        assertThatThrownBy(() -> connector.fetchBalances("session"))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("ambiguous");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[\"uid-missing\",\"uid-unlabelled\"]",
        "[\"uid-unlabelled\",\"uid-missing\"]"
    })
    void fetchBalances_siblingDetailsUnavailable_rejectsUnprovenFallbackAndRethrowsFailure(String accounts) {
        responses.put("/sessions/session", "{\"accounts\":" + accounts + "}");
        responseStatuses.put("/accounts/uid-missing/details", 503);
        responses.put("/accounts/uid-unlabelled/details", "{}");
        responses.put("/accounts/uid-unlabelled/balances", """
            {"balances": [
              {"name":"Operations", "balance_type":"CLBD",
               "balance_amount":{"currency":"EUR", "amount":"100"}},
              {"name":"Operations", "balance_type":"ITAV",
               "balance_amount":{"currency":"EUR", "amount":"90"}}
            ]}
            """);

        assertThatThrownBy(() -> connector.fetchBalances("session"))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("Failed to fetch account details");
    }

    @Test
    void fetchBalances_siblingDetailsUnavailable_rejectsUnprovenDescriptionMatches() {
        responses.put("/sessions/session", "{\"accounts\":[\"uid-missing\",\"uid-unlabelled\",\"uid-reserve\"]}");
        responseStatuses.put("/accounts/uid-missing/details", 503);
        responses.put("/accounts/uid-unlabelled/details", "{}");
        responses.put("/accounts/uid-reserve/details", "{\"details\":\"Reserve wallet\"}");
        String balances = """
            {"balances": [
              {"name":"Operations", "balance_type":"CLBD",
               "balance_amount":{"currency":"EUR", "amount":"100"}},
              {"name":"Reserve wallet", "balance_type":"ITAV",
               "balance_amount":{"currency":"EUR", "amount":"300"}}
            ]}
            """;
        responses.put("/accounts/uid-unlabelled/balances", balances);
        responses.put("/accounts/uid-reserve/balances", balances);

        assertThatThrownBy(() -> connector.fetchBalances("session"))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("Failed to fetch account details");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"aspsp\":{\"name\":\"Qonto\",\"country\":\"FR\"}"})
    void fetchBalances_failedSiblingDetails_cannotMakeSharedDescriptionUnique(String provider) {
        responses.put("/sessions/session", "{\"accounts\":[\"uid-shared-a\",\"uid-shared-b\"]" + provider + "}");
        responseStatuses.put("/accounts/uid-shared-a/details", 503);
        responses.put("/accounts/uid-shared-b/details", "{\"details\":\"Shared wallet\"}");
        responses.put("/accounts/uid-shared-b/balances", """
            {"balances": [
              {"name":"Shared wallet", "balance_type":"CLBD",
               "balance_amount":{"currency":"EUR", "amount":"100"}},
              {"name":"Shared wallet", "balance_type":"ITAV",
               "balance_amount":{"currency":"EUR", "amount":"200"}}
            ]}
            """);

        assertThatThrownBy(() -> connector.fetchBalances("session"))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("Failed to fetch account details");
    }

    @Test
    void fetchBalances_nullSiblingDetails_cannotMakeSharedDescriptionUnique() {
        responses.put("/sessions/session", """
            {"accounts":["uid-shared-a","uid-shared-b"], "aspsp":{"name":"Qonto", "country":"FR"}}
            """);
        responses.put("/accounts/uid-shared-a/details", "null");
        responses.put("/accounts/uid-shared-b/details", "{\"details\":\"Shared wallet\"}");
        String balances = """
            {"balances": [
              {"name":"Shared wallet", "balance_type":"CLBD",
               "balance_amount":{"currency":"EUR", "amount":"100"}},
              {"name":"Shared wallet", "balance_type":"ITAV",
               "balance_amount":{"currency":"EUR", "amount":"200"}}
            ]}
            """;
        responses.put("/accounts/uid-shared-a/balances", balances);
        responses.put("/accounts/uid-shared-b/balances", balances);

        assertThatThrownBy(() -> connector.fetchBalances("session"))
            .isInstanceOf(SyncException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"balances\":[]}",
        "{\"balances\":[null]}",
        "{\"balances\":[{\"balance_type\":\"CLBD\"}]}",
        "{\"balances\":[{\"balance_type\":\"CLBD\",\"balance_amount\":{\"amount\":null}}]}"
    })
    void fetchBalances_missingBalanceOrAmount_failsInsteadOfInventingZero(String balances) {
        responses.put("/accounts/uid-operations/details", "{}");
        responses.put("/accounts/uid-operations/balances", balances);

        assertThatThrownBy(() -> connector.fetchBalances("session")).isInstanceOf(SyncException.class);
    }

    @Test
    void fetchBalances_malformedMatchingBalance_doesNotFallBackToAnotherAccount() {
        responses.put("/accounts/uid-operations/details", "{\"details\":\"Operations wallet\"}");
        responses.put("/accounts/uid-operations/balances", """
            {"balances": [
              {"name": "Reserve wallet", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "41.75"}},
              {"name": "Operations wallet", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "unreadable"}}
            ]}
            """);

        assertThatThrownBy(() -> connector.fetchBalances("session")).isInstanceOf(NumberFormatException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"details\":null}", "{\"details\":\"  \"}", "{\"details\":\"Unmatched wallet\"}"})
    void fetchBalances_unmatchedAccount_usesOnlyBalancesUnclaimedBySessionSiblings(String details) {
        responses.put("/sessions/session", "{\"accounts\":[\"uid-unmatched\",\"uid-operations\"]}");
        responses.put("/accounts/uid-unmatched/details", details);
        responses.put("/accounts/uid-operations/details", "{\"details\":\"Operations wallet\"}");
        String balances = """
            {"balances": [
              {"name": "Reserve wallet", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "41.75"}},
              {"name": "Operations wallet", "balance_type": "XPCD",
               "balance_amount": {"currency": "EUR", "amount": "903.20"}}
            ]}
            """;
        responses.put("/accounts/uid-unmatched/balances", balances);
        responses.put("/accounts/uid-operations/balances", balances);

        var accounts = connector.fetchBalances("session");

        assertThat(accounts).extracting(AccountData::externalId).containsExactly("uid-unmatched", "uid-operations");
        assertThat(accounts.get(0).balance()).isEqualByComparingTo("41.75");
        assertThat(accounts.get(1).balance()).isEqualByComparingTo("903.20");
    }

    @Test
    void fetchBalances_oneAmbiguousAccount_keepsTheOtherAccountSyncable() {
        responses.put("/sessions/session", "{\"accounts\":[\"uid-ambiguous\",\"uid-operations\"]}");
        responses.put("/accounts/uid-ambiguous/details", "{}");
        responses.put("/accounts/uid-operations/details", "{\"details\":\"Operations wallet\"}");
        String balances = """
            {"balances": [
              {"name": "Reserve wallet", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "41.75"}},
              {"name": "Unclaimed wallet", "balance_type": "ITAV",
               "balance_amount": {"currency": "EUR", "amount": "60"}},
              {"name": "Operations wallet", "balance_type": "XPCD",
               "balance_amount": {"currency": "EUR", "amount": "903.20"}}
            ]}
            """;
        responses.put("/accounts/uid-ambiguous/balances", balances);
        responses.put("/accounts/uid-operations/balances", balances);

        assertThat(connector.fetchBalances("session")).singleElement().satisfies(account -> {
            assertThat(account.externalId()).isEqualTo("uid-operations");
            assertThat(account.balance()).isEqualByComparingTo("903.20");
        });
    }

    @Test
    void fetchBalances_siblingBalanceRequestFails_stillExcludesItsLabelFromFallback() {
        responses.put("/sessions/session", """
            {"accounts":["uid-unmatched","uid-operations","uid-reserve"],
             "aspsp":{"name":"Synthetic Bank", "country":"FR"}}
            """);
        responses.put("/accounts/uid-unmatched/details", "{}");
        responses.put("/accounts/uid-operations/details", "{\"details\":\"Operations wallet\"}");
        responses.put("/accounts/uid-reserve/details", "{\"details\":\"Reserve wallet\"}");
        responses.put("/accounts/uid-unmatched/balances", """
            {"balances": [{"name":"Operations wallet", "balance_type":"CLBD",
              "balance_amount":{"currency":"EUR", "amount":"100"}}]}
            """);
        responses.put("/accounts/uid-reserve/balances", """
            {"balances": [{"name":"Reserve wallet", "balance_type":"CLBD",
              "balance_amount":{"currency":"EUR", "amount":"41.75"}}]}
            """);

        assertThat(connector.fetchBalances("session")).singleElement().satisfies(account -> {
            assertThat(account.externalId()).isEqualTo("uid-reserve");
            assertThat(account.balance()).isEqualByComparingTo("41.75");
        });
    }

    @Test
    void fetchBalances_oneDetailsRequestFails_keepsTheOtherAccountSyncable() {
        responses.put("/sessions/session", """
            {"accounts":["uid-missing","uid-operations"], "aspsp":{"name":"Synthetic Bank", "country":"FR"}}
            """);
        responses.put("/accounts/uid-operations/details", "{\"details\":\"Operations wallet\"}");
        responses.put("/accounts/uid-operations/balances", """
            {"balances": [{"name":"Operations wallet", "balance_type":"CLBD",
              "balance_amount":{"currency":"EUR", "amount":"903.20"}}]}
            """);

        assertThat(connector.fetchBalances("session")).singleElement().satisfies(account -> {
            assertThat(account.externalId()).isEqualTo("uid-operations");
            assertThat(account.balance()).isEqualByComparingTo("903.20");
        });
    }
}
