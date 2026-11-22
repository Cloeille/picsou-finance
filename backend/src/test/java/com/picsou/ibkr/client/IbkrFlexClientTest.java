package com.picsou.ibkr.client;

import com.picsou.exception.SyncException;
import com.picsou.ibkr.client.IbkrFlexClient.IbkrAccountData;
import com.picsou.ibkr.client.IbkrFlexClient.IbkrPosition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parser tests for {@link IbkrFlexClient}. The fixture uses the exact IBKR Flex
 * attribute names (validated against the reference parser github.com/csingley/ibflex),
 * so this guards the single riskiest part of the integration: a renamed attribute would
 * silently make holdings vanish or mis-value.
 */
class IbkrFlexClientTest {

    private final IbkrFlexClient client = new IbkrFlexClient();

    /** A realistic Open Positions FlexQueryResponse: two SUMMARY rows + one LOT duplicate. */
    private static final String FIXTURE = """
        <FlexQueryResponse queryName="OpenPositions" type="AF">
          <FlexStatements count="1">
            <FlexStatement accountId="U1234567" fromDate="20260101" toDate="20260718"
                           period="LastBusinessDay" whenGenerated="20260719;050000">
              <OpenPositions>
                <OpenPosition accountId="U1234567" currency="USD" fxRateToBase="0.92"
                    assetCategory="STK" symbol="AAPL" description="APPLE INC" conid="265598"
                    isin="US0378331005" position="10" markPrice="200.00"
                    costBasisPrice="150.00" costBasisMoney="1500.00" levelOfDetail="SUMMARY" />
                <OpenPosition accountId="U1234567" currency="EUR" fxRateToBase="1"
                    assetCategory="STK" symbol="IWDA" description="ISHARES CORE MSCI WORLD"
                    conid="100292928" isin="IE00B4L5Y983" position="5" markPrice="90.00"
                    costBasisPrice="80.00" costBasisMoney="400.00" levelOfDetail="SUMMARY" />
                <OpenPosition accountId="U1234567" currency="USD" fxRateToBase="0.92"
                    assetCategory="STK" symbol="AAPL" description="APPLE INC" conid="265598"
                    isin="US0378331005" position="10" markPrice="200.00"
                    costBasisPrice="150.00" levelOfDetail="LOT" />
              </OpenPositions>
            </FlexStatement>
          </FlexStatements>
        </FlexQueryResponse>
        """;

    @Test
    void parse_groupsPositionsByAccount() {
        List<IbkrAccountData> accounts = client.parseOpenPositions(FIXTURE);

        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).accountId()).isEqualTo("U1234567");
        // The parser is intentionally non-filtering — SUMMARY + LOT are all returned here;
        // dropping LOT rows is the sync service's job.
        assertThat(accounts.get(0).positions()).hasSize(3);
    }

    @Test
    void parse_readsAllAttributesOfAPosition() {
        IbkrPosition aapl = client.parseOpenPositions(FIXTURE).get(0).positions().get(0);

        assertThat(aapl.accountId()).isEqualTo("U1234567");
        assertThat(aapl.isin()).isEqualTo("US0378331005");
        assertThat(aapl.symbol()).isEqualTo("AAPL");
        assertThat(aapl.description()).isEqualTo("APPLE INC");
        assertThat(aapl.currency()).isEqualTo("USD");
        assertThat(aapl.assetCategory()).isEqualTo("STK");
        assertThat(aapl.levelOfDetail()).isEqualTo("SUMMARY");
        assertThat(aapl.position()).isEqualByComparingTo("10");
        assertThat(aapl.markPrice()).isEqualByComparingTo("200.00");
        assertThat(aapl.costBasisPrice()).isEqualByComparingTo("150.00");
        assertThat(aapl.fxRateToBase()).isEqualByComparingTo("0.92");
    }

    @Test
    void parse_distinguishesLotRowsFromSummary() {
        List<IbkrPosition> positions = client.parseOpenPositions(FIXTURE).get(0).positions();

        assertThat(positions).filteredOn(p -> "LOT".equals(p.levelOfDetail())).hasSize(1);
        assertThat(positions).filteredOn(p -> "SUMMARY".equals(p.levelOfDetail())).hasSize(2);
    }

    /**
     * A fully liquidated portfolio is a valid FlexQueryResponse whose statement carries
     * an empty {@code <OpenPositions/>}. The account MUST still be returned (with zero
     * positions) so the sync purges its stale holdings — deriving accounts from
     * {@code <OpenPosition>} rows alone made an emptied account vanish from the result
     * and its old holdings were kept (and valued) forever.
     */
    @Test
    void parse_emptyPositionsYieldsTheAccountWithNoPositions() {
        String empty = """
            <FlexQueryResponse queryName="OpenPositions" type="AF">
              <FlexStatements count="1">
                <FlexStatement accountId="U1234567"><OpenPositions/></FlexStatement>
              </FlexStatements>
            </FlexQueryResponse>
            """;

        List<IbkrAccountData> accounts = client.parseOpenPositions(empty);

        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).accountId()).isEqualTo("U1234567");
        assertThat(accounts.get(0).positions()).isEmpty();
    }

    /**
     * A multi-day query ("Breakout by Day" / date-ranged period) emits one FlexStatement
     * PER DAY for the same account, each with a full positions snapshot — merging them
     * would multiply every quantity by the number of days. The parser must refuse with
     * an actionable message instead of silently inflating the portfolio.
     */
    @Test
    void parse_multipleStatementsForSameAccount_throwsActionable() {
        String breakout = """
            <FlexQueryResponse queryName="OpenPositions" type="AF">
              <FlexStatements count="2">
                <FlexStatement accountId="U1234567"><OpenPositions/></FlexStatement>
                <FlexStatement accountId="U1234567"><OpenPositions/></FlexStatement>
              </FlexStatements>
            </FlexQueryResponse>
            """;

        assertThatThrownBy(() -> client.parseOpenPositions(breakout))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("U1234567")
            .hasMessageContaining("Last Business Day");
    }

    /**
     * The parser now also receives files the user uploads, so it must refuse a DOCTYPE outright:
     * an external entity (file read, SSRF) and an internal one (billion-laughs) are both rejected
     * before any entity is expanded, and nothing of the target file reaches the result.
     */
    @Test
    void parse_rejectsAnyDoctype_soNoEntityIsEverExpanded() {
        String external = """
            <?xml version="1.0"?>
            <!DOCTYPE FlexQueryResponse [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <FlexQueryResponse><FlexStatements><FlexStatement accountId="&xxe;"/></FlexStatements></FlexQueryResponse>
            """;
        String internal = """
            <?xml version="1.0"?>
            <!DOCTYPE FlexQueryResponse [<!ENTITY a "AAAA"><!ENTITY b "&a;&a;&a;&a;">]>
            <FlexQueryResponse><FlexStatements><FlexStatement accountId="&b;"/></FlexStatements></FlexQueryResponse>
            """;

        assertThatThrownBy(() -> client.parseOpenPositions(external)).isInstanceOf(SyncException.class);
        assertThatThrownBy(() -> client.parseOpenPositions(internal)).isInstanceOf(SyncException.class);
    }

    /** The statement's {@code toDate} is what tells a fresh report from a forgotten download. */
    @Test
    void parse_readsTheStatementDate_andToleratesItsAbsence() {
        String xml = """
            <FlexQueryResponse><FlexStatements count="3">
              <FlexStatement accountId="U1" toDate="20261120"><OpenPositions/></FlexStatement>
              <FlexStatement accountId="U2"><OpenPositions/></FlexStatement>
              <FlexStatement accountId="U3" toDate="not-a-date"><OpenPositions/></FlexStatement>
            </FlexStatements></FlexQueryResponse>
            """;

        List<IbkrAccountData> accounts = client.parseOpenPositions(xml);

        assertThat(accounts).extracting(IbkrAccountData::statementDate)
            .containsExactly(java.time.LocalDate.of(2026, 11, 20), null, null);
    }

    /** The XML parser, not a UTF-8 decode, must work out the encoding of an uploaded file. */
    @Test
    void parseUploaded_detectsTheEncodingOfTheFile() {
        String doc = """
            <?xml version="1.0" encoding="%s"?>
            <FlexQueryResponse><FlexStatements count="1"><FlexStatement accountId="U1">
              <OpenPositions><OpenPosition accountId="U1" symbol="CAC" description="AMUNDI CAC 40 ÉTF"
                position="3" levelOfDetail="SUMMARY"/></OpenPositions>
            </FlexStatement></FlexStatements></FlexQueryResponse>
            """;
        java.io.ByteArrayOutputStream withBom = new java.io.ByteArrayOutputStream();
        withBom.writeBytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        withBom.writeBytes(doc.formatted("UTF-8").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        for (byte[] file : List.of(
            withBom.toByteArray(),
            doc.formatted("UTF-16").getBytes(java.nio.charset.StandardCharsets.UTF_16),
            doc.formatted("ISO-8859-1").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))) {
            List<IbkrAccountData> accounts = client.parseUploadedStatement(file);

            assertThat(accounts).singleElement().satisfies(account ->
                assertThat(account.positions()).singleElement().satisfies(position ->
                    assertThat(position.description()).isEqualTo("AMUNDI CAC 40 ÉTF")));
        }
    }

    /**
     * A wrong file says it is a wrong file (not that Interactive Brokers failed), and nothing of
     * its content reaches the logs: it may hold account ids, positions or another document.
     */
    @Test
    void parseUploaded_aFileThatIsNotXml_isReportedAsSuch_withoutLoggingItsContent() {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(IbkrFlexClient.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            byte[] csv = "account,secret-marker-U9999999,quantity\n1,2,3"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

            assertThatThrownBy(() -> client.parseUploadedStatement(csv))
                .isInstanceOf(SyncException.class)
                .hasMessage("The file is not a valid XML Flex report.");

            assertThat(appender.list).isNotEmpty()
                .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("secret-marker"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** A Trades / NAV / Cash report of a known account must not parse to "no position" and empty it. */
    @Test
    void parseUploaded_aReportWithoutOpenPositions_isRefused_butAnEmptyOneIsAccepted() {
        byte[] tradesOnly = """
            <FlexQueryResponse><FlexStatements count="1">
              <FlexStatement accountId="U1" toDate="20261120"><Trades/></FlexStatement>
            </FlexStatements></FlexQueryResponse>
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] liquidated = """
            <FlexQueryResponse><FlexStatements count="1">
              <FlexStatement accountId="U1" toDate="20261120"><OpenPositions/></FlexStatement>
            </FlexStatements></FlexQueryResponse>
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThatThrownBy(() -> client.parseUploadedStatement(tradesOnly))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("Open Positions section")
            .hasMessageContaining("U1");
        assertThat(client.parseUploadedStatement(liquidated)).singleElement()
            .satisfies(account -> assertThat(account.positions()).isEmpty());
    }

    /** A statement with no usable toDate still dates itself by the day it was generated. */
    @Test
    void parse_fallsBackToTheGenerationDate() {
        String xml = """
            <FlexQueryResponse><FlexStatements count="1">
              <FlexStatement accountId="U1" whenGenerated="20261122;110616"><OpenPositions/></FlexStatement>
            </FlexStatements></FlexQueryResponse>
            """;

        assertThat(client.parseOpenPositions(xml)).singleElement()
            .satisfies(account -> assertThat(account.statementDate()).isEqualTo(java.time.LocalDate.of(2026, 11, 22)));
    }

    /** A statement without an account id is skipped by the parser, so it cannot empty anything. */
    @Test
    void parseUploaded_aStatementWithoutAnAccountIdNeedsNoOpenPositions() {
        byte[] xml = """
            <FlexQueryResponse><FlexStatements count="2">
              <FlexStatement toDate="20261120"><Trades/></FlexStatement>
              <FlexStatement accountId="U1" toDate="20261120"><OpenPositions/></FlexStatement>
            </FlexStatements></FlexQueryResponse>
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(client.parseUploadedStatement(xml)).singleElement()
            .satisfies(account -> assertThat(account.accountId()).isEqualTo("U1"));
    }

    /** Deep nesting must never end as a stack overflow (a 500): it parses to "no account", which the service refuses. */
    @Test
    void parseUploaded_aHugelyNestedFileDoesNotOverflowTheStack() {
        byte[] nested = ("<a>".repeat(20_000) + "</a>".repeat(20_000)).getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(client.parseUploadedStatement(nested)).isEmpty();
    }

    @Test
    void parse_readsAnIsoStatementDateToo() {
        String xml = """
            <FlexQueryResponse><FlexStatements count="1">
              <FlexStatement accountId="U1" toDate="2026-11-20"><OpenPositions/></FlexStatement>
            </FlexStatements></FlexQueryResponse>
            """;

        assertThat(client.parseOpenPositions(xml)).singleElement()
            .satisfies(account -> assertThat(account.statementDate()).isEqualTo(java.time.LocalDate.of(2026, 11, 20)));
    }

    @Test
    void parseUploaded_rejectsAnyDoctype() {
        byte[] external = """
            <?xml version="1.0"?>
            <!DOCTYPE FlexQueryResponse [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <FlexQueryResponse><FlexStatements><FlexStatement accountId="&xxe;"/></FlexStatements></FlexQueryResponse>
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThatThrownBy(() -> client.parseUploadedStatement(external)).isInstanceOf(SyncException.class);
    }

    @Test
    void parse_groupsMultipleAccountsAndReadsShortAndSparsePositions() {
        // Two account ids in one statement; a short (negative) position; and a row
        // missing the optional isin / costBasisPrice / fxRateToBase attributes.
        String xml = """
            <FlexQueryResponse queryName="OpenPositions" type="AF">
              <FlexStatements count="1">
                <FlexStatement accountId="U1">
                  <OpenPositions>
                    <OpenPosition accountId="U1" currency="USD" fxRateToBase="0.90" assetCategory="STK"
                        symbol="AAPL" isin="US0378331005" position="10" markPrice="200"
                        costBasisPrice="150" levelOfDetail="SUMMARY" />
                    <OpenPosition accountId="U2" currency="EUR" assetCategory="STK"
                        symbol="SHORTY" position="-5" markPrice="12" levelOfDetail="SUMMARY" />
                  </OpenPositions>
                </FlexStatement>
              </FlexStatements>
            </FlexQueryResponse>
            """;

        List<IbkrAccountData> accounts = client.parseOpenPositions(xml);

        assertThat(accounts).hasSize(2);
        assertThat(accounts).extracting(IbkrAccountData::accountId).containsExactly("U1", "U2");

        IbkrPosition shorty = accounts.get(1).positions().get(0);
        assertThat(shorty.position()).isEqualByComparingTo("-5");   // short parsed as negative
        assertThat(shorty.isin()).isNull();                          // missing → null
        assertThat(shorty.costBasisPrice()).isNull();
        assertThat(shorty.fxRateToBase()).isNull();
    }

    /**
     * AccountInformation is an optional Flex Query section (Account ID, Account Alias,
     * Currency) that can be enabled alongside Open Positions in the same Activity Flex
     * Query; when present, its {@code currency} attribute is the account's IBKR base
     * currency (plan 003 / issue B).
     */
    @Test
    void parse_readsAccountInformationBaseCurrency() {
        String xml = """
            <FlexQueryResponse queryName="OpenPositions" type="AF">
              <FlexStatements count="1">
                <FlexStatement accountId="U1234567" fromDate="20260101" toDate="20260718"
                               period="LastBusinessDay" whenGenerated="20260719;050000">
                  <AccountInformation accountId="U1234567" currency="USD" />
                  <OpenPositions>
                    <OpenPosition accountId="U1234567" currency="USD" fxRateToBase="1"
                        assetCategory="STK" symbol="AAPL" description="APPLE INC" conid="265598"
                        isin="US0378331005" position="10" markPrice="200.00"
                        costBasisPrice="150.00" levelOfDetail="SUMMARY" />
                  </OpenPositions>
                </FlexStatement>
              </FlexStatements>
            </FlexQueryResponse>
            """;

        List<IbkrAccountData> accounts = client.parseOpenPositions(xml);

        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).baseCurrency()).isEqualTo("USD");
    }

    @Test
    void parse_noAccountInformationSection_yieldsNullBaseCurrency() {
        // The shared FIXTURE above has no AccountInformation element at all -- the
        // common case today, since the parse must not assume the section is enabled.
        List<IbkrAccountData> accounts = client.parseOpenPositions(FIXTURE);

        assertThat(accounts.get(0).baseCurrency()).isNull();
    }
}
