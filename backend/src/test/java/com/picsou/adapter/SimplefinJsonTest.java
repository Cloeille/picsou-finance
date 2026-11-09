package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.SimplefinPort.SimplefinAccount;
import com.picsou.port.SimplefinPort.SimplefinTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing edge cases of the SimpleFIN account-set. {@link SimplefinJson} is package-private,
 * so these tests sit beside it and feed it raw JSON, with no transport involved.
 */
class SimplefinJsonTest {

    private static final String ACCOUNT = "\"id\":\"a\",\"name\":\"N\",\"currency\":\"USD\",\"balance\":\"1\"";
    private static final String EMOJI = "\uD83D\uDE00";

    private static List<SimplefinAccount> accounts(String accountsJson) {
        return SimplefinJson.parse("{\"accounts\":[" + accountsJson + "]}").accounts();
    }

    private static SimplefinAccount only(String accountJson) {
        List<SimplefinAccount> parsed = accounts(accountJson);
        assertThat(parsed).hasSize(1);
        return parsed.get(0);
    }

    private static List<SimplefinAccount> withBalance(String balanceJson) {
        return accounts("{\"id\":\"a\",\"currency\":\"USD\",\"balance\":" + balanceJson + "}");
    }

    private static List<SimplefinTransaction> transactions(String txJson) {
        return only("{\"id\":\"a\",\"currency\":\"USD\",\"balance\":\"1\",\"transactions\":[" + txJson + "]}")
            .transactions();
    }

    private static String tx(String fields) {
        return "{\"id\":\"t\",\"posted\":1767225600,\"amount\":\"-1\",\"description\":\"d\"" + fields + "}";
    }

    private static String txPosted(String posted) {
        return "{\"id\":\"t\",\"posted\":" + posted + ",\"amount\":\"-1\",\"description\":\"d\"}";
    }

    private static List<String> errors(String message) {
        String json = new ObjectMapper().valueToTree(Map.of("errlist", List.of(Map.of("msg", message)))).toString();
        return SimplefinJson.parse(json).errors();
    }

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void errors_errlistAndLegacyErrors_areCollectedInOrderAndEmptyOnesSkipped() {
        List<String> errors = SimplefinJson.parse("""
            {"errlist": [{"code":"gen.auth","msg":"Reauthenticate at the bank."}, "A plain string.",
                         {"code":"x"}, {"msg":"   "}],
             "errors": ["Legacy error."], "accounts": []}
            """).errors();

        assertThat(errors).containsExactly("Reauthenticate at the bank.", "A plain string.", "Legacy error.");
    }

    @Test
    void errors_controlCharactersAndWhitespaceRuns_collapseToOneLine() {
        assertThat(errors("  Bank\r\nsaid:\t\tno\u2028way\u0007\u0085 now  ")).containsExactly("Bank said: no way now");
    }

    @Test
    void errors_overTheCap_areCutAt300CharactersWithAnEllipsis() {
        List<String> errors = errors("x".repeat(150) + "\r\n\t" + "y".repeat(400));

        assertThat(errors).singleElement().satisfies(message -> {
            assertThat(message).hasSize(301).endsWith("…").doesNotContain("\n", "\r", "\t");
            assertThat(message).isEqualTo("x".repeat(150) + " " + "y".repeat(149) + "…");
        });
    }

    @Test
    void errors_exactlyAtTheCap_areKeptWhole() {
        assertThat(errors("x".repeat(300))).containsExactly("x".repeat(300));
    }

    @Test
    void errors_highSurrogateAtTheCut_isNotSplit() {
        // The emoji starts at position 300 (index 299): keeping its first half would leave a lone surrogate.
        assertThat(errors("x".repeat(299) + EMOJI + "tail")).containsExactly("x".repeat(299) + "…");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "[]", "not json"})
    void parse_bodyThatIsNotAnObject_isRefused(String body) {
        assertThatThrownBy(() -> SimplefinJson.parse(body)).isInstanceOf(SyncException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"accounts\":{\"id\":\"a\"}}"})
    void parse_accountsThatAreNotAnArray_yieldNoAccounts(String body) {
        assertThat(SimplefinJson.parse(body).accounts()).isEmpty();
    }

    @Test
    void parse_unknownExtraFields_areIgnored() {
        SimplefinAccount account = only("""
            {"id":"a","name":"N","currency":"USD","balance":"1.00","available-balance":"0.00",
             "balance-date":1767225600,"extensions":{"x":[1,2,{"y":null}]},"future-field":true,
             "transactions":[{"id":"t","posted":1767225600,"amount":"-1.00","description":"d",
                              "transacted_at":1767225000,"extra":{"k":"v"}}]}
            """);

        assertThat(account.balance()).isEqualByComparingTo("1.00");
        assertThat(account.transactions()).hasSize(1);
    }

    @Test
    void parse_oneMalformedAccount_doesNotCostTheOthers() {
        List<SimplefinAccount> parsed = accounts("""
            {"id":"bad","currency":"USD","balance":"1,00"},
            {"id":"good","currency":"USD","balance":"1.00"}
            """);

        assertThat(parsed).extracting(SimplefinAccount::externalId).containsExactly("sfin_account_good");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "usd|usd",                                    // case is left to the service
        "' USD '|USD",
        "https://example.com/miles|https://example.com/miles",   // kept so the service can refuse it
        "XXX|XXX",
    })
    void currency_isTrimmedAndOtherwisePassedThrough(String currency, String expected) {
        assertThat(only("{\"id\":\"a\",\"balance\":\"1\",\"currency\":\"" + currency + "\"}").currency())
            .isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"currency\":\"  \"", ",\"currency\":null"})
    void currency_missingOrBlank_isNull(String currencyField) {
        assertThat(only("{\"id\":\"a\",\"balance\":\"1\"" + currencyField + "}").currency()).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "\"-0.00\"|0",
        "\"1e3\"|1000",
        "\" +5.25 \"|5.25",
        "12.5|12.5",                                  // a JSON number reads like a string
        "\"1e30\"|1000000000000000000000000000000",   // beyond NUMERIC(20,8): the service skips it
        "\"0.1234567890123456789\"|0.1234567890123456789",
    })
    void balance_readableForms_areParsed(String balanceJson, String expected) {
        assertThat(withBalance(balanceJson)).singleElement()
            .satisfies(account -> assertThat(account.balance()).isEqualByComparingTo(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"1,234.56\"", "\"\"", "\"   \"", "null", "\"abc\"", "\"NaN\"", "\"Infinity\"",
        "\"1e99999999999\"", "\"$5.00\"", "\"1 234.56\""})
    void balance_notADecimal_dropsTheAccount(String balanceJson) {
        assertThat(withBalance(balanceJson)).isEmpty();
    }

    @Test
    void balance_missing_dropsTheAccount() {
        assertThat(accounts("{\"id\":\"a\",\"currency\":\"USD\"}")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"id\":\"a\",\"currency\":\"USD\",\"balance\":\"1\"}",
        "{\"id\":\"a\",\"name\":\"  \",\"currency\":\"USD\",\"balance\":\"1\"}"})
    void name_missingOrBlank_fallsBackToAccount(String accountJson) {
        assertThat(only(accountJson).name()).isEqualTo("Account");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"name\":\"N\",\"currency\":\"USD\",\"balance\":\"1\"}",
        "{\"id\":\" \",\"name\":\"N\",\"currency\":\"USD\",\"balance\":\"1\"}"})
    void id_missingOrBlank_dropsTheAccount(String accountJson) {
        assertThat(accounts(accountJson)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"conn_id\":\"C1\",\"name\":\"Chase Bank Tom\",\"org_name\":\"Chase\"}|Chase",   // org_name wins
        "{\"conn_id\":\"C1\",\"name\":\"Chase Bank Tom\"}|Chase Bank Tom",
        "{\"conn_id\":\"C1\"}|",
        "{\"conn_id\":\"OTHER\",\"org_name\":\"Chase\"}|",                                  // not this account's
    })
    void connection_name_comesFromTheListedConnection(String connection, String expected) {
        String body = "{\"connections\":[" + connection + "],\"accounts\":[{\"id\":\"a\",\"conn_id\":\"C1\","
            + "\"currency\":\"USD\",\"balance\":\"1\"}]}";

        assertThat(SimplefinJson.parse(body).accounts().get(0).connectionName()).isEqualTo(expected);
    }

    @Test
    void connection_v1StyleOrgObjectOnTheAccount_isIgnored() {
        SimplefinAccount account = only("{" + ACCOUNT + ",\"conn_id\":\"C1\",\"org\":{\"name\":\"Chase\"}}");

        assertThat(account.connectionName()).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"''|sfin_account_a", "',\"conn_id\":123'|sfin_123_a",
        "',\"conn_id\":\"CON-1\"'|sfin_CON-1_a"})
    void externalId_isBuiltFromTheConnectionAndAccountIds(String connField, String expected) {
        assertThat(only("{\"id\":\"a\",\"currency\":\"USD\",\"balance\":\"1\"" + connField + "}").externalId())
            .isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {247, 248})
    void externalId_atTheColumnLimit_isKeptVerbatim(int idLength) {
        // "sfin_" + "C" + "_" is 7 characters: 254 and 255 in total.
        assertThat(SimplefinJson.externalAccountId("C", "a".repeat(idLength)))
            .hasSize(7 + idLength).startsWith("sfin_C_a");
    }

    @Test
    void externalId_oneCharacterOverTheLimit_isHashedButKeepsThePrefix() throws Exception {
        String raw = "sfin_C_" + "a".repeat(249);

        assertThat(SimplefinJson.externalAccountId("C", "a".repeat(249)))
            .isEqualTo("sfin_" + sha256Hex(raw)).hasSize(69);
    }

    @Test
    void externalId_hash_isStableAndDistinguishesNeighbours() {
        String first = SimplefinJson.externalAccountId("C", "a".repeat(300));

        assertThat(first)
            .isEqualTo(SimplefinJson.externalAccountId("C", "a".repeat(300)))
            .isNotEqualTo(SimplefinJson.externalAccountId("C", "a".repeat(299) + "b"));
    }

    @Test
    void externalId_multibyteId_isHashedOnUtf8() throws Exception {
        String accountId = "é".repeat(300);

        assertThat(SimplefinJson.externalAccountId("C", accountId))
            .isEqualTo("sfin_" + sha256Hex("sfin_C_" + accountId));
    }

    @Test
    void externalId_length_isCountedInUtf16Units() {
        // 124 emoji are 248 units, so 255 with the prefix; 125 emoji are 257.
        assertThat(SimplefinJson.externalAccountId("C", EMOJI.repeat(124))).hasSize(255).startsWith("sfin_C_");
        assertThat(SimplefinJson.externalAccountId("C", EMOJI.repeat(125))).hasSize(69).startsWith("sfin_");
    }

    @Test
    void externalId_sameAccountIdUnderTwoConnections_staysDistinct() {
        List<SimplefinAccount> parsed = accounts("""
            {"id":"1234","conn_id":"CON-1","currency":"USD","balance":"1"},
            {"id":"1234","conn_id":"CON-2","currency":"USD","balance":"1"}
            """);

        assertThat(parsed).extracting(SimplefinAccount::externalId)
            .containsExactly("sfin_CON-1_1234", "sfin_CON-2_1234");
    }

    @Test
    void transactionId_at255IsKept_and256IsHashedToAStableDigest() throws Exception {
        String over = "t".repeat(256);

        List<SimplefinTransaction> parsed = transactions(
            tx("").replace("\"t\"", "\"" + "t".repeat(255) + "\"") + "," + tx("").replace("\"t\"", "\"" + over + "\""));

        assertThat(parsed).extracting(SimplefinTransaction::externalId)
            .containsExactly("t".repeat(255), sha256Hex(over));
    }

    @Test
    void transactionId_missingOrBlank_isNullSoTheImporterFingerprintsTheRow() {
        List<SimplefinTransaction> parsed = transactions(
            tx("").replace("\"id\":\"t\",", "") + "," + tx("").replace("\"t\"", "\"  \""));

        assertThat(parsed).extracting(SimplefinTransaction::externalId).containsOnlyNulls();
    }

    @Test
    void description_missingOrBlank_fallsBackToTransaction() {
        List<SimplefinTransaction> parsed = transactions(
            tx("").replace(",\"description\":\"d\"", "") + "," + tx("").replace("\"d\"", "\"   \""));

        assertThat(parsed).extracting(SimplefinTransaction::description).containsExactly("Transaction", "Transaction");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"\"-0.00\"|0", "\"2.5e1\"|25", "\"+7\"|7"})
    void amount_readableForms_areParsed(String amountJson, String expected) {
        assertThat(transactions(tx("").replace("\"-1\"", amountJson)))
            .singleElement().satisfies(row -> assertThat(row.amount()).isEqualByComparingTo(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"1,50\"", "\"\"", "null", "\"abc\""})
    void amount_notADecimal_dropsTheTransaction(String amountJson) {
        assertThat(transactions(tx("").replace("\"-1\"", amountJson))).isEmpty();
    }

    @Test
    void amount_missing_dropsTheTransaction() {
        assertThat(transactions(tx("").replace(",\"amount\":\"-1\"", ""))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "\"true\"", "1"})
    void pending_truthy_isDropped(String pending) {
        assertThat(transactions(tx(",\"pending\":" + pending))).isEmpty();
    }

    @Test
    void pending_falseOrMissing_isKept() {
        assertThat(transactions(tx(",\"pending\":false") + "," + tx(""))).hasSize(2);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "1767225599|2025-12-31",         // 23:59:59Z is already 2026-01-01 in Paris: the date is UTC
        "1767225600|2026-01-01",
        "'\"1767225600\"'|2026-01-01",   // seconds written as a string
        "1767225600000|2026-01-01",      // milliseconds are divided down
        "1.7e12|2023-11-14",             // milliseconds written as a JSON double
        "10000000001|1970-04-26",        // just above the seconds/milliseconds cutoff: read as milliseconds
        "7289654399|2200-12-31",         // last second of 2200
    })
    void posted_readableForms_becomeTheUtcDate(String posted, LocalDate expected) {
        assertThat(transactions(txPosted(posted)))
            .singleElement().satisfies(row -> assertThat(row.date()).isEqualTo(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "0", "-1", "-86400", "\"abc\"", "null", "\"\"",
        "9999999999",            // year 2286 read as seconds
        "7289654400",            // 2201-01-01T00:00:00Z, the first second past the guard
        "7289654400000",         // the same instant in milliseconds
        "9223372036854775807",   // Long.MAX_VALUE
    })
    void posted_unusable_dropsTheTransaction(String posted) {
        assertThat(transactions(txPosted(posted))).isEmpty();
    }

    @Test
    void posted_missing_dropsTheTransaction() {
        assertThat(transactions(tx("").replace("\"posted\":1767225600,", ""))).isEmpty();
    }

    // ---- institution identity ----------------------------------------------------------

    private static SimplefinAccount withConnection(String connectionJson) {
        return SimplefinJson.parse("{\"connections\":[" + connectionJson + "],\"accounts\":[{"
            + ACCOUNT + ",\"conn_id\":\"C1\"}]}").accounts().get(0);
    }

    @Test
    void connection_orgNameAndUrl_becomeTheInstitution() {
        SimplefinAccount account = withConnection(
            "{\"conn_id\":\"C1\",\"org_name\":\"Fidelity Investments\",\"org_url\":\"https://www.fidelity.com\"}");

        assertThat(account.connectionName()).isEqualTo("Fidelity Investments");
        assertThat(account.institutionDomain()).isEqualTo("fidelity.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://user:pw@fidelity.com", "https://localhost",
        "https://127.0.0.1", "https://192.168.0.1", "https://10.0.0.1:8080", "ftp://fidelity.com", "https://exa mple.com", "javascript:alert(1)", "https://a..com",
        "https://-bad.com"})
    void connection_unsafeUrl_givesNoDomain(String url) {
        SimplefinAccount account = withConnection(
            "{\"conn_id\":\"C1\",\"org_name\":\"X\",\"org_url\":\"" + url + "\"}");

        assertThat(account.institutionDomain()).isNull();
        assertThat(account.connectionName()).isEqualTo("X");
    }

    @ParameterizedTest
    @CsvSource({
        "http://fidelity.com,fidelity.com",
        "https://fidelity.com:8443/login,fidelity.com",
        "fidelity.com/login,fidelity.com",
        "fidelity.com?x=1,fidelity.com",
        "https://www.chase.com./,chase.com",
        "https://bank.xn--e1afmkfd.xn--p1ai,bank.xn--e1afmkfd.xn--p1ai"})
    void hostOf_keepsOnlyTheHost_whateverTheSchemePortOrPath(String raw, String host) {
        assertThat(SimplefinJson.hostOf(raw)).isEqualTo(host);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.2.3.44", "https://1.2.3.44", "fidelity", "-x.com", ".com", "a b.com"})
    void hostOf_refusesAnAddressOrAHostWithoutAPublicName(String raw) {
        assertThat(SimplefinJson.hostOf(raw)).isNull();
    }

    @Test
    void hostOf_acceptsABareHostAndStripsWww() {
        assertThat(SimplefinJson.hostOf("WWW.Chase.com")).isEqualTo("chase.com");
        assertThat(SimplefinJson.hostOf("https://www.chase.com/path?q=1")).isEqualTo("chase.com");
    }

    // ---- holdings -----------------------------------------------------------------------

    @Test
    void holdings_stringFigures_areParsed() {
        SimplefinAccount account = only("{" + ACCOUNT + ",\"holdings\":[{\"id\":\"h1\",\"symbol\":\"FXAIX\","
            + "\"description\":\"Fidelity 500 Index Fund\",\"currency\":\"USD\",\"shares\":\"10.500\","
            + "\"market_value\":\"2100.00\",\"cost_basis\":\"0.00\",\"purchase_price\":\"180.40\"}]}");

        assertThat(account.holdings()).singleElement().satisfies(h -> {
            assertThat(h.symbol()).isEqualTo("FXAIX");
            assertThat(h.shares()).isEqualByComparingTo("10.500");
            assertThat(h.marketValue()).isEqualByComparingTo("2100.00");
            assertThat(h.costBasis()).isEqualByComparingTo("0");
            assertThat(h.purchasePrice()).isEqualByComparingTo("180.40");
        });
    }

    @Test
    void holdings_numericFigures_areParsedToo() {
        SimplefinAccount account = only("{" + ACCOUNT
            + ",\"holdings\":[{\"symbol\":\"A\",\"shares\":2.5,\"market_value\":10}]}");

        assertThat(account.holdings()).singleElement()
            .satisfies(h -> assertThat(h.shares()).isEqualByComparingTo("2.5"));
    }

    @Test
    void holdings_absentOrEmpty_isAnEmptyList() {
        assertThat(only("{" + ACCOUNT + "}").holdings()).isEmpty();
        assertThat(only("{" + ACCOUNT + ",\"holdings\":[]}").holdings()).isEmpty();
    }

    @Test
    void holdings_rowsThatCannotBeValued_areDroppedAndMarkTheListIncomplete() {
        SimplefinAccount account = only("{" + ACCOUNT + ",\"holdings\":["
            + "{\"symbol\":\"NOSHARES\"},"
            + "{\"symbol\":\"BAD\",\"shares\":\"abc\"},"
            + "{\"shares\":\"1\"},"
            + "{\"symbol\":\"OK\",\"shares\":\"1\"}]}");

        assertThat(account.holdings()).extracting(h -> h.symbol()).containsExactly("OK");
        assertThat(account.holdingsComplete()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1E-999999999", "1E+999999999", "0E-999999999"})
    void holdings_shareCountWithAnAbsurdExponent_isDroppedNotLeftToThrowLater(String shares) {
        SimplefinAccount account = only("{" + ACCOUNT + ",\"holdings\":[{\"symbol\":\"A\",\"shares\":\""
            + shares + "\",\"market_value\":\"10\"},{\"symbol\":\"OK\",\"shares\":\"1\"}]}");

        assertThat(account.holdings()).extracting(h -> h.symbol()).containsExactly("OK");
        assertThat(account.holdingsComplete()).isFalse();
    }

    @Test
    void holdings_everyRowReadable_isComplete() {
        SimplefinAccount account = only("{" + ACCOUNT + ",\"holdings\":["
            + "{\"symbol\":\"A\",\"shares\":\"1\"},{\"description\":\"No ticker\",\"shares\":\"2\"}]}");

        assertThat(account.holdings()).hasSize(2);
        assertThat(account.holdingsComplete()).isTrue();
        assertThat(only("{" + ACCOUNT + "}").holdingsComplete()).isTrue();
    }

    @Test
    void holdings_pastTheRowCap_keepTheFirstRowsButAreIncomplete() {
        String rows = IntStream.rangeClosed(1, 501)
            .mapToObj(i -> "{\"symbol\":\"S" + i + "\",\"shares\":\"1\"}")
            .collect(Collectors.joining(","));

        SimplefinAccount account = only("{" + ACCOUNT + ",\"holdings\":[" + rows + "]}");

        assertThat(account.holdings()).hasSize(500);
        assertThat(account.holdingsComplete()).isFalse();
    }

    @Test
    void holdings_exactlyTheRowCap_isStillComplete() {
        String rows = IntStream.rangeClosed(1, 500)
            .mapToObj(i -> "{\"symbol\":\"S" + i + "\",\"shares\":\"1\"}")
            .collect(Collectors.joining(","));

        SimplefinAccount account = only("{" + ACCOUNT + ",\"holdings\":[" + rows + "]}");

        assertThat(account.holdings()).hasSize(500);
        assertThat(account.holdingsComplete()).isTrue();
    }

    @Test
    void holdings_symbolWithAnEmojiStraddlingTheLimit_isCutBeforeTheEmoji() {
        String symbol = "A".repeat(99) + EMOJI;

        SimplefinAccount account = only("{" + ACCOUNT + ",\"holdings\":[{\"symbol\":\"" + symbol
            + "\",\"shares\":\"1\"}]}");

        assertThat(account.holdings()).singleElement()
            .satisfies(h -> assertThat(h.symbol()).isEqualTo("A".repeat(99)));
    }

    @Test
    void holdings_descriptionWithAnEmojiEndingOnTheLimit_isKept() {
        String description = "D".repeat(253) + EMOJI;

        SimplefinAccount account = only("{" + ACCOUNT + ",\"holdings\":[{\"symbol\":\"A\",\"description\":\""
            + description + "\",\"shares\":\"1\"}]}");

        assertThat(account.holdings()).singleElement()
            .satisfies(h -> assertThat(h.description()).isEqualTo(description));
    }
}
