package com.picsou.ibkr.client;

import com.picsou.exception.SyncException;
import com.picsou.port.IbkrFlexPort.IbkrAccountData;
import com.picsou.port.IbkrFlexPort.IbkrPosition;
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
