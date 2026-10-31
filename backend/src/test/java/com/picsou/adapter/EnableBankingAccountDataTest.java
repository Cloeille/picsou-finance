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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

@ExtendWith(MockitoExtension.class)
class EnableBankingAccountDataTest {

    @Mock EnableBankingConfigProvider configProvider;

    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final List<String> requestedPaths = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private EnableBankingBankConnector connector;

    @BeforeEach
    void startProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedPaths.add(exchange.getRequestURI().getPath());
            String response = responses.get(exchange.getRequestURI().getPath());
            byte[] body = (response != null ? response : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response != null ? 200 : 404, body.length);
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
              {"name": "Other balance", "balance_type": "%s",
               "balance_amount": {"currency": "GBP", "amount": "44.10"}},
              {"name": "Preferred balance", "balance_type": "%s",
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

    @Test
    void fetchBalances_oneAmbiguousAccount_keepsTheOtherAccountSyncable() {
        responses.put("/sessions/session", "{\"accounts\":[\"uid-ambiguous\",\"uid-operations\"]}");
        responses.put("/accounts/uid-ambiguous/details", "{}");
        responses.put("/accounts/uid-operations/details", "{\"details\":\"Operations wallet\"}");
        // Two entries remain unattributed for the unlabelled account and tie at the best type.
        String balances = """
            {"balances": [
              {"name": "Reserve wallet", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "41.75"}},
              {"name": "Savings wallet", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "12.00"}},
              {"name": "Operations wallet", "balance_type": "CLBD",
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

    // ─── Organization-wide balance lists: attribution through sibling labels ───

    private void putOrganizationWideSession(String sharedBalances, String... uidsAndDetails) {
        StringBuilder uids = new StringBuilder();
        for (int i = 0; i < uidsAndDetails.length; i += 2) {
            if (i > 0) uids.append(',');
            uids.append('"').append(uidsAndDetails[i]).append('"');
            responses.put("/accounts/" + uidsAndDetails[i] + "/details", uidsAndDetails[i + 1]);
            responses.put("/accounts/" + uidsAndDetails[i] + "/balances", sharedBalances);
        }
        responses.put("/sessions/session", "{\"accounts\":[" + uids + "]}");
    }

    @Test
    void fetchBalances_unlabelledAccountInOrganizationWideList_takesOnlyTheUnattributedEntry() {
        putOrganizationWideSession("""
            {"balances": [
              {"name": "Operations wallet", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "903.20"}},
              {"name": "Reserve wallet", "balance_type": "XPCD",
               "balance_amount": {"currency": "EUR", "amount": "41.75"}},
              {"name": "Petty cash", "balance_type": "ITAV",
               "balance_amount": {"currency": "EUR", "amount": "7.50"}}
            ]}
            """,
            "uid-operations", "{\"details\":\"Operations wallet\"}",
            "uid-reserve", "{\"details\":\"Reserve wallet\"}",
            "uid-unlabelled", "{}");

        var accounts = connector.fetchBalances("session");

        assertThat(accounts).extracting(AccountData::externalId, a -> a.balance().toPlainString())
            .containsExactly(
                tuple("uid-operations", "903.20"),
                tuple("uid-reserve", "41.75"),
                tuple("uid-unlabelled", "7.50"));
        assertThat(requestedPaths).containsExactlyInAnyOrder(
            "/sessions/session",
            "/accounts/uid-operations/balances", "/accounts/uid-operations/details",
            "/accounts/uid-reserve/balances", "/accounts/uid-reserve/details",
            "/accounts/uid-unlabelled/balances", "/accounts/uid-unlabelled/details");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"details\":\"Unmatched wallet\"}"})
    void fetchBalances_noUnattributedEntryInOrganizationWideList_skipsOnlyThatAccount(String details) {
        putOrganizationWideSession("""
            {"balances": [
              {"name": "Operations wallet", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "903.20"}},
              {"name": "Reserve wallet", "balance_type": "ITAV",
               "balance_amount": {"currency": "EUR", "amount": "41.75"}}
            ]}
            """,
            "uid-operations", "{\"details\":\"Operations wallet\"}",
            "uid-reserve", "{\"details\":\"Reserve wallet\"}",
            "uid-unattributed", details);

        assertThat(connector.fetchBalances("session")).extracting(AccountData::externalId)
            .containsExactly("uid-operations", "uid-reserve");
    }

    @Test
    void fetchBalances_accountsSharingALabelInOrganizationWideList_failClosedWithoutBlockingOthers() {
        putOrganizationWideSession("""
            {"balances": [
              {"name": "Joint wallet", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "11.11"}},
              {"name": " JOINT wallet ", "balance_type": "XPCD",
               "balance_amount": {"currency": "EUR", "amount": "22.22"}},
              {"name": "Operations wallet", "balance_type": "ITAV",
               "balance_amount": {"currency": "EUR", "amount": "903.20"}}
            ]}
            """,
            "uid-joint-a", "{\"details\":\"Joint wallet\"}",
            "uid-joint-b", "{\"details\":\" joint WALLET\"}",
            "uid-operations", "{\"details\":\"Operations wallet\"}");

        assertThat(connector.fetchBalances("session")).singleElement().satisfies(account -> {
            assertThat(account.externalId()).isEqualTo("uid-operations");
            assertThat(account.balance()).isEqualByComparingTo("903.20");
        });
    }

    @Test
    void fetchBalances_onlyAccountsSharingALabelAndNothingElseToUse_rethrowsTheAttributionFailure() {
        putOrganizationWideSession("""
            {"balances": [
              {"name": "Joint wallet", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "11.11"}},
              {"name": "Joint wallet", "balance_type": "XPCD",
               "balance_amount": {"currency": "EUR", "amount": "22.22"}}
            ]}
            """,
            "uid-joint-a", "{\"details\":\"Joint wallet\"}",
            "uid-joint-b", "{\"details\":\"Joint wallet\"}");

        assertThatThrownBy(() -> connector.fetchBalances("session"))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("ambiguous");
    }

    @Test
    void fetchBalances_multipleAccountsWithBalancesLabelledByKind_keepsEachAccountsOwnBalance() {
        responses.put("/sessions/session", "{\"accounts\":[\"uid-current\",\"uid-savings\"]}");
        responses.put("/accounts/uid-current/details", "{\"details\":\"Everyday account\"}");
        responses.put("/accounts/uid-savings/details", "{\"details\":\"Rainy day savings\"}");
        responses.put("/accounts/uid-current/balances", """
            {"balances": [
              {"name": "Booked balance", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "120.00"}},
              {"name": "Available balance", "balance_type": "ITAV",
               "balance_amount": {"currency": "EUR", "amount": "150.00"}}
            ]}
            """);
        responses.put("/accounts/uid-savings/balances", """
            {"balances": [
              {"name": "Booked balance", "balance_type": "CLBD",
               "balance_amount": {"currency": "EUR", "amount": "5000.00"}},
              {"name": "Available balance", "balance_type": "ITAV",
               "balance_amount": {"currency": "EUR", "amount": "5000.00"}}
            ]}
            """);

        assertThat(connector.fetchBalances("session"))
            .extracting(AccountData::externalId, a -> a.balance().toPlainString())
            .containsExactly(
                tuple("uid-current", "120.00"),
                tuple("uid-savings", "5000.00"));
    }
}
