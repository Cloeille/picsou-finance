package com.picsou.adapter;

import com.picsou.exception.SyncException;
import com.picsou.port.SimplefinPort.SimplefinAccount;
import com.picsou.port.SimplefinPort.SimplefinTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing edge cases of the SimpleFIN account-set. {@link SimplefinJson} is package-private,
 * so these tests sit beside it and feed it raw JSON, with no transport involved.
 */
class SimplefinJsonTest {

    // ---------------------------------------------------------------- helpers

    private static List<SimplefinAccount> accounts(String accountsJson) {
        return SimplefinJson.parse("{\"accounts\":[" + accountsJson + "]}").accounts();
    }

    private static SimplefinAccount only(String accountJson) {
        List<SimplefinAccount> parsed = accounts(accountJson);
        assertThat(parsed).hasSize(1);
        return parsed.get(0);
    }

    private static SimplefinAccount withBalance(String balanceJson) {
        return only("{\"id\":\"a\",\"name\":\"N\",\"currency\":\"USD\",\"balance\":" + balanceJson + "}");
    }

    private static List<SimplefinAccount> withBalanceDropped(String balanceJson) {
        return accounts("{\"id\":\"a\",\"name\":\"N\",\"currency\":\"USD\",\"balance\":" + balanceJson + "}");
    }

    private static List<SimplefinTransaction> transactions(String txJson) {
        return only("{\"id\":\"a\",\"name\":\"N\",\"currency\":\"USD\",\"balance\":\"1.00\",\"transactions\":["
            + txJson + "]}").transactions();
    }

    private static String tx(String posted) {
        return "{\"id\":\"t\",\"posted\":" + posted + ",\"amount\":\"-1.00\",\"description\":\"d\"}";
    }

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    // --------------------------------------------------------------- currency

    @Test
    void currency_lowercaseIsPassedThroughForTheServiceToNormalise() {
        SimplefinAccount account = only("{\"id\":\"a\",\"currency\":\"usd\",\"balance\":\"1\"}");
        assertThat(account.currency()).isEqualTo("usd");
    }

    @Test
    void currency_surroundingWhitespaceIsTrimmed() {
        assertThat(only("{\"id\":\"a\",\"currency\":\" USD \",\"balance\":\"1\"}").currency()).isEqualTo("USD");
    }

    @Test
    void currency_missingOrBlankBecomesNull() {
        assertThat(only("{\"id\":\"a\",\"balance\":\"1\"}").currency()).isNull();
        assertThat(only("{\"id\":\"a\",\"currency\":\"  \",\"balance\":\"1\"}").currency()).isNull();
        assertThat(only("{\"id\":\"a\",\"currency\":null,\"balance\":\"1\"}").currency()).isNull();
    }

    @Test
    void currency_urlIsKeptVerbatimSoTheServiceCanRefuseIt() {
        SimplefinAccount account = only(
            "{\"id\":\"a\",\"currency\":\"https://example.com/miles\",\"balance\":\"1\"}");
        assertThat(account.currency()).isEqualTo("https://example.com/miles");
    }

    @Test
    void currency_parserDoesNotValidateSoXxxSurvivesParsing() {
        assertThat(only("{\"id\":\"a\",\"currency\":\"XXX\",\"balance\":\"1\"}").currency()).isEqualTo("XXX");
    }

    // ---------------------------------------------------------------- balance

    @Test
    void balance_negativeZeroIsAccepted() {
        assertThat(withBalance("\"-0.00\"").balance()).isEqualByComparingTo("0");
    }

    @Test
    void balance_scientificNotationIsRead() {
        assertThat(withBalance("\"1e3\"").balance()).isEqualByComparingTo("1000");
    }

    @Test
    void balance_leadingPlusAndSurroundingSpacesAreRead() {
        assertThat(withBalance("\" +5.25 \"").balance()).isEqualByComparingTo("5.25");
    }

    @Test
    void balance_numericJsonValueIsReadLikeAString() {
        assertThat(withBalance("12.5").balance()).isEqualByComparingTo("12.5");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"1,234.56\"", "\"\"", "\"   \"", "null", "\"abc\"", "\"NaN\"", "\"Infinity\"",
        "\"1e99999999999\"", "\"$5.00\"", "\"1 234.56\""})
    void balance_aValueThatIsNotADecimalDropsTheAccount(String balanceJson) {
        assertThat(withBalanceDropped(balanceJson)).isEmpty();
    }

    @Test
    void balance_missingDropsTheAccount() {
        assertThat(accounts("{\"id\":\"a\",\"currency\":\"USD\"}")).isEmpty();
    }

    @Test
    void balance_beyondNumeric20_8ParsesAndIsLeftToTheServiceToSkip() {
        SimplefinAccount account = withBalance("\"1e30\"");
        assertThat(account.balance()).isEqualByComparingTo("1000000000000000000000000000000");
    }

    @Test
    void balance_manyDecimalsAreKeptAtFullScale() {
        SimplefinAccount account = withBalance("\"0.1234567890123456789\"");
        assertThat(account.balance().toPlainString()).isEqualTo("0.1234567890123456789");
    }

    // ----------------------------------------------- missing names and fields

    @Test
    void name_missingFallsBackToAccount() {
        assertThat(only("{\"id\":\"a\",\"currency\":\"USD\",\"balance\":\"1\"}").name()).isEqualTo("Account");
        assertThat(only("{\"id\":\"a\",\"name\":\"  \",\"currency\":\"USD\",\"balance\":\"1\"}").name())
            .isEqualTo("Account");
    }

    @Test
    void id_missingOrBlankDropsTheAccount() {
        assertThat(accounts("{\"name\":\"N\",\"currency\":\"USD\",\"balance\":\"1\"}")).isEmpty();
        assertThat(accounts("{\"id\":\" \",\"name\":\"N\",\"currency\":\"USD\",\"balance\":\"1\"}")).isEmpty();
    }

    @Test
    void connection_orgNameIsPreferredOverName() {
        SimplefinAccount account = SimplefinJson.parse("""
            {"connections":[{"conn_id":"C1","name":"Chase Bank Tom","org_name":"Chase"}],
             "accounts":[{"id":"a","conn_id":"C1","name":"N","currency":"USD","balance":"1"}]}
            """).accounts().get(0);
        assertThat(account.connectionName()).isEqualTo("Chase");
    }

    @Test
    void connection_nameIsUsedWhenOrgNameIsAbsent() {
        SimplefinAccount account = SimplefinJson.parse("""
            {"connections":[{"conn_id":"C1","name":"Chase Bank Tom"}],
             "accounts":[{"id":"a","conn_id":"C1","name":"N","currency":"USD","balance":"1"}]}
            """).accounts().get(0);
        assertThat(account.connectionName()).isEqualTo("Chase Bank Tom");
    }

    @Test
    void connection_noOrgNameAtAllLeavesTheNameNull() {
        SimplefinAccount account = SimplefinJson.parse("""
            {"connections":[{"conn_id":"C1"}],
             "accounts":[{"id":"a","conn_id":"C1","name":"N","currency":"USD","balance":"1"}]}
            """).accounts().get(0);
        assertThat(account.connectionName()).isNull();
        assertThat(account.externalId()).isEqualTo("sfin_C1_a");
    }

    @Test
    void connection_aConnIdThatIsNotListedLeavesTheNameNull() {
        SimplefinAccount account = SimplefinJson.parse("""
            {"connections":[{"conn_id":"OTHER","org_name":"Chase"}],
             "accounts":[{"id":"a","conn_id":"C1","name":"N","currency":"USD","balance":"1"}]}
            """).accounts().get(0);
        assertThat(account.connectionName()).isNull();
    }

    @Test
    void connection_aV1StyleOrgObjectOnTheAccountIsIgnored() {
        SimplefinAccount account = only("""
            {"id":"a","conn_id":"C1","name":"N","currency":"USD","balance":"1",
             "org":{"name":"Chase","domain":"chase.com"}}
            """);
        assertThat(account.connectionName()).isNull();
    }

    @Test
    void connId_missingUsesTheAccountPlaceholder() {
        assertThat(only("{\"id\":\"a\",\"currency\":\"USD\",\"balance\":\"1\"}").externalId())
            .isEqualTo("sfin_account_a");
    }

    @Test
    void connId_numericJsonValueIsReadAsText() {
        assertThat(only("{\"id\":\"a\",\"conn_id\":123,\"currency\":\"USD\",\"balance\":\"1\"}").externalId())
            .isEqualTo("sfin_123_a");
    }

    @Test
    void unknownExtraFieldsAreIgnored() {
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
    void accountsThatAreNotAnArrayYieldNoAccounts() {
        assertThat(SimplefinJson.parse("{\"accounts\":{\"id\":\"a\"}}").accounts()).isEmpty();
        assertThat(SimplefinJson.parse("{}").accounts()).isEmpty();
    }

    @Test
    void aBodyThatIsNotAnObjectIsRefused() {
        assertThatThrownBy(() -> SimplefinJson.parse("[]")).isInstanceOf(SyncException.class);
        assertThatThrownBy(() -> SimplefinJson.parse("not json")).isInstanceOf(SyncException.class);
        assertThatThrownBy(() -> SimplefinJson.parse("  ")).isInstanceOf(SyncException.class);
        assertThatThrownBy(() -> SimplefinJson.parse(null)).isInstanceOf(SyncException.class);
    }

    @Test
    void oneMalformedAccountDoesNotCostTheOthers() {
        List<SimplefinAccount> parsed = accounts("""
            {"id":"bad","currency":"USD","balance":"1,00"},
            {"id":"good","currency":"USD","balance":"1.00"}
            """);
        assertThat(parsed).extracting(SimplefinAccount::externalId).containsExactly("sfin_account_good");
    }

    // ------------------------------------------------------------ external ids

    @Test
    void externalId_atTheColumnLimitIsKeptVerbatim() {
        // "sfin_" + "C" + "_" is 7 characters.
        assertThat(SimplefinJson.externalAccountId("C", "a".repeat(247))).hasSize(254).startsWith("sfin_C_a");
        assertThat(SimplefinJson.externalAccountId("C", "a".repeat(248))).hasSize(255).startsWith("sfin_C_a");
    }

    @Test
    void externalId_oneCharacterOverTheLimitIsHashedButKeepsThePrefix() throws Exception {
        String raw = "sfin_C_" + "a".repeat(249);
        assertThat(raw).hasSize(256);

        String hashed = SimplefinJson.externalAccountId("C", "a".repeat(249));

        assertThat(hashed).isEqualTo("sfin_" + sha256Hex(raw)).hasSize(69).matches("sfin_[0-9a-f]{64}");
    }

    @Test
    void externalId_hashIsStableAndDistinguishesNeighbours() {
        String first = SimplefinJson.externalAccountId("C", "a".repeat(300));
        String again = SimplefinJson.externalAccountId("C", "a".repeat(300));
        String other = SimplefinJson.externalAccountId("C", "a".repeat(299) + "b");

        assertThat(first).isEqualTo(again).isNotEqualTo(other);
        assertThat(other).startsWith("sfin_");
    }

    @Test
    void externalId_multibyteIdsAreHashedOnUtf8NotOnThePlatformCharset() throws Exception {
        String accountId = "é".repeat(300);
        String expected = "sfin_" + sha256Hex("sfin_C_" + accountId);
        assertThat(SimplefinJson.externalAccountId("C", accountId)).isEqualTo(expected);
    }

    @Test
    void externalId_shortMultibyteIdsAreKept() {
        assertThat(SimplefinJson.externalAccountId("C", "compte-é-\uD83D\uDE00")).isEqualTo("sfin_C_compte-é-\uD83D\uDE00");
    }

    @Test
    void externalId_lengthIsCountedInUtf16UnitsSoEmojiIdsHashEarlierThanNecessary() {
        // 124 emoji = 248 units; with the 7-unit prefix that is exactly 255, 125 emoji is 257.
        String atLimit = SimplefinJson.externalAccountId("C", "\uD83D\uDE00".repeat(124));
        String over = SimplefinJson.externalAccountId("C", "\uD83D\uDE00".repeat(125));

        assertThat(atLimit).hasSize(255).startsWith("sfin_C_");
        assertThat(over).hasSize(69).startsWith("sfin_");
    }

    @Test
    void externalId_dependsOnlyOnThePayloadSoAReconnectFindsTheSameAccounts() {
        String body = """
            {"connections":[{"conn_id":"CON-1","org_name":"Chase"}],
             "accounts":[{"id":"chk","conn_id":"CON-1","name":"Checking","currency":"USD","balance":"1"}]}
            """;

        String beforeReconnect = SimplefinJson.parse(body).accounts().get(0).externalId();
        String afterReconnect = SimplefinJson.parse(body).accounts().get(0).externalId();

        assertThat(beforeReconnect).isEqualTo("sfin_CON-1_chk").isEqualTo(afterReconnect);
    }

    @Test
    void externalId_sameAccountIdUnderTwoConnectionsStaysDistinct() {
        List<SimplefinAccount> parsed = accounts("""
            {"id":"1234","conn_id":"CON-1","currency":"USD","balance":"1"},
            {"id":"1234","conn_id":"CON-2","currency":"USD","balance":"1"}
            """);
        assertThat(parsed).extracting(SimplefinAccount::externalId)
            .containsExactly("sfin_CON-1_1234", "sfin_CON-2_1234");
    }

    @Test
    void sameAccountIdTwiceInOneResponseIsReturnedTwiceForTheServiceToMerge() {
        List<SimplefinAccount> parsed = accounts("""
            {"id":"chk","conn_id":"CON-1","currency":"USD","balance":"1.00"},
            {"id":"chk","conn_id":"CON-1","currency":"USD","balance":"2.00"}
            """);
        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).externalId()).isEqualTo(parsed.get(1).externalId());
    }

    // ----------------------------------------------------------- transaction ids

    @Test
    void transactionId_at255IsKeptAnd256IsHashedToAStableDigest() throws Exception {
        String atLimit = "t".repeat(255);
        String over = "t".repeat(256);

        List<SimplefinTransaction> parsed = transactions(
            "{\"id\":\"" + atLimit + "\",\"posted\":1767225600,\"amount\":\"1\",\"description\":\"d\"},"
            + "{\"id\":\"" + over + "\",\"posted\":1767225600,\"amount\":\"1\",\"description\":\"d\"}");

        assertThat(parsed.get(0).externalId()).isEqualTo(atLimit);
        assertThat(parsed.get(1).externalId()).isEqualTo(sha256Hex(over));
    }

    @Test
    void transactionId_missingOrBlankIsNullSoTheImporterFingerprintsTheRow() {
        List<SimplefinTransaction> parsed = transactions(
            "{\"posted\":1767225600,\"amount\":\"1\",\"description\":\"d\"},"
            + "{\"id\":\"  \",\"posted\":1767225600,\"amount\":\"1\",\"description\":\"d\"}");
        assertThat(parsed).extracting(SimplefinTransaction::externalId).containsOnlyNulls();
    }

    @Test
    void transactionId_sameIdOnTwoAccountsIsKeptOnBoth() {
        List<SimplefinAccount> parsed = accounts("""
            {"id":"chk","conn_id":"C","currency":"USD","balance":"1","transactions":[
              {"id":"shared","posted":1767225600,"amount":"-5","description":"Transfer out"}]},
            {"id":"sav","conn_id":"C","currency":"USD","balance":"1","transactions":[
              {"id":"shared","posted":1767225600,"amount":"5","description":"Transfer in"}]}
            """);
        assertThat(parsed.get(0).transactions()).extracting(SimplefinTransaction::externalId).containsExactly("shared");
        assertThat(parsed.get(1).transactions()).extracting(SimplefinTransaction::externalId).containsExactly("shared");
    }

    // ------------------------------------------------------------ transactions

    @Test
    void description_missingOrBlankFallsBackToTransaction() {
        List<SimplefinTransaction> parsed = transactions(
            "{\"id\":\"1\",\"posted\":1767225600,\"amount\":\"1\"},"
            + "{\"id\":\"2\",\"posted\":1767225600,\"amount\":\"1\",\"description\":\"   \"}");
        assertThat(parsed).extracting(SimplefinTransaction::description).containsExactly("Transaction", "Transaction");
    }

    @Test
    void description_isNotClippedByTheParserSoTheImporterOwnsTheLimit() {
        List<SimplefinTransaction> parsed = transactions(
            "{\"id\":\"1\",\"posted\":1767225600,\"amount\":\"1\",\"description\":\"" + "x".repeat(400) + "\"}");
        assertThat(parsed.get(0).description()).hasSize(400);
    }

    @Test
    void amount_missingOrNotADecimalDropsTheTransaction() {
        assertThat(transactions(
            "{\"id\":\"1\",\"posted\":1767225600,\"description\":\"d\"},"
            + "{\"id\":\"2\",\"posted\":1767225600,\"amount\":\"1,50\",\"description\":\"d\"},"
            + "{\"id\":\"3\",\"posted\":1767225600,\"amount\":\"\",\"description\":\"d\"},"
            + "{\"id\":\"4\",\"posted\":1767225600,\"amount\":null,\"description\":\"d\"}")).isEmpty();
    }

    @Test
    void amount_negativeZeroAndScientificNotationAreRead() {
        List<SimplefinTransaction> parsed = transactions(
            "{\"id\":\"1\",\"posted\":1767225600,\"amount\":\"-0.00\",\"description\":\"d\"},"
            + "{\"id\":\"2\",\"posted\":1767225600,\"amount\":\"2.5e1\",\"description\":\"d\"}");
        assertThat(parsed.get(0).amount()).isEqualByComparingTo("0");
        assertThat(parsed.get(1).amount()).isEqualByComparingTo("25");
    }

    @Test
    void pending_stringTrueAndNumericOneAreBothDropped() {
        assertThat(transactions(
            "{\"id\":\"1\",\"posted\":1767225600,\"amount\":\"1\",\"description\":\"d\",\"pending\":\"true\"},"
            + "{\"id\":\"2\",\"posted\":1767225600,\"amount\":\"1\",\"description\":\"d\",\"pending\":1}")).isEmpty();
    }

    @Test
    void pending_falseOrMissingIsKept() {
        assertThat(transactions(
            "{\"id\":\"1\",\"posted\":1767225600,\"amount\":\"1\",\"description\":\"d\",\"pending\":false},"
            + "{\"id\":\"2\",\"posted\":1767225600,\"amount\":\"1\",\"description\":\"d\"}")).hasSize(2);
    }

    // ------------------------------------------------------------------- dates

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-86400", "\"abc\"", "null", "\"\""})
    void posted_zeroNegativeOrUnreadableIsDropped(String posted) {
        assertThat(transactions(tx(posted))).isEmpty();
    }

    @Test
    void posted_missingIsDropped() {
        assertThat(transactions("{\"id\":\"t\",\"amount\":\"-1.00\",\"description\":\"d\"}")).isEmpty();
    }

    @Test
    void posted_lastSecondOfYear2200IsKeptAndTheNextSecondIsDropped() {
        // 2200-12-31T23:59:59Z and 2201-01-01T00:00:00Z
        List<SimplefinTransaction> parsed = transactions(tx("7289654399") + "," + tx("7289654400"));

        assertThat(parsed).singleElement()
            .satisfies(row -> assertThat(row.date()).isEqualTo(LocalDate.of(2200, 12, 31)));
    }

    @Test
    void posted_dateIsTheUtcDateWhateverTheServerZone() {
        // 2025-12-31T23:59:59Z is already 2026-01-01 in Paris.
        List<SimplefinTransaction> parsed = transactions(tx("1767225599") + "," + tx("1767225600"));

        assertThat(parsed).extracting(SimplefinTransaction::date)
            .containsExactly(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1));
    }

    @Test
    void posted_millisecondsAreDividedDown() {
        List<SimplefinTransaction> parsed = transactions(tx("1767225600000"));

        assertThat(parsed).singleElement()
            .satisfies(row -> assertThat(row.date()).isEqualTo(LocalDate.of(2026, 1, 1)));
    }

    @Test
    void posted_millisecondsWrittenAsAJsonDoubleAreDividedDown() {
        // 1.7e12 ms is 2023-11-14T22:13:20Z
        List<SimplefinTransaction> parsed = transactions(tx("1.7e12"));

        assertThat(parsed).singleElement()
            .satisfies(row -> assertThat(row.date()).isEqualTo(LocalDate.of(2023, 11, 14)));
    }

    @Test
    void posted_secondsWrittenAsAStringAreRead() {
        List<SimplefinTransaction> parsed = transactions(tx("\"1767225600\""));

        assertThat(parsed).singleElement()
            .satisfies(row -> assertThat(row.date()).isEqualTo(LocalDate.of(2026, 1, 1)));
    }

    @Test
    void posted_theSecondsMillisecondsCutoffSitsFarBeyondAnyRealBankDate() {
        // 9_999_999_999 s is 2286: read as seconds, so dropped by the year-2200 guard.
        // 10_000_000_000 ms is 1970-04-26: read as milliseconds, so kept with the right date.
        assertThat(transactions(tx("9999999999"))).isEmpty();
        assertThat(transactions(tx("10000000001")))
            .singleElement()
            .satisfies(row -> assertThat(row.date()).isEqualTo(LocalDate.of(1970, 4, 26)));
    }

    @Test
    void posted_millisecondsOfAnYear2200PlusDateAreDropped() {
        // 2201-01-01T00:00:00Z in ms
        assertThat(transactions(tx("7289654400000"))).isEmpty();
    }

    @Test
    void posted_longMaxValueIsDroppedWithoutThrowing() {
        assertThat(transactions(tx("9223372036854775807"))).isEmpty();
    }
}
