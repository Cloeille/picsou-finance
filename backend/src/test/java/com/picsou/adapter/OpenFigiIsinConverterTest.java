package com.picsou.adapter;

import com.picsou.port.SymbolCatalogPort;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenFigiIsinConverterTest {

    private static Map<String, Object> entry(String exchCode, String ticker, String name) {
        return Map.of("exchCode", exchCode, "ticker", ticker, "name", name);
    }

    /**
     * A symbol catalog that quotes nothing and finds nothing. The tests that only exercise the
     * offline paths (ISIN detection, TR crypto, pickBest) never reach it.
     */
    private static SymbolCatalogPort silentCatalog() {
        return Mockito.mock(SymbolCatalogPort.class);
    }

    private static OpenFigiIsinConverter converterWith(SymbolCatalogPort catalog) {
        return new OpenFigiIsinConverter(new CoinGeckoPriceProvider(), catalog);
    }

    @Test
    void isIsin_recognizesValidIsinCodes() {
        // 2-letter country prefix + 9 alphanumerics + 1 check digit = 12 chars
        assertThat(OpenFigiIsinConverter.isIsin("IE00B4L5Y983")).isTrue(); // iShares Core MSCI World
        assertThat(OpenFigiIsinConverter.isIsin("US0378331005")).isTrue(); // Apple
        assertThat(OpenFigiIsinConverter.isIsin("DE0007100000")).isTrue(); // Mercedes-Benz
        assertThat(OpenFigiIsinConverter.isIsin("KYG9830T1067")).isTrue(); // Xiaomi
    }

    @Test
    void isIsin_normalizesCaseAndWhitespace() {
        assertThat(OpenFigiIsinConverter.isIsin("ie00b4l5y983")).isTrue();
        assertThat(OpenFigiIsinConverter.isIsin("  IE00B4L5Y983  ")).isTrue();
    }

    @Test
    void isIsin_rejectsTickersAndNonIsinStrings() {
        assertThat(OpenFigiIsinConverter.isIsin("IWDA.AS")).isFalse(); // Yahoo ticker (has a dot)
        assertThat(OpenFigiIsinConverter.isIsin("AAPL")).isFalse();
        assertThat(OpenFigiIsinConverter.isIsin("BTC")).isFalse();
        assertThat(OpenFigiIsinConverter.isIsin("IE00B4L5Y98")).isFalse();  // 11 chars
        assertThat(OpenFigiIsinConverter.isIsin("IE00B4L5Y9833")).isFalse(); // 13 chars
        assertThat(OpenFigiIsinConverter.isIsin("12345678901X")).isFalse();  // digits in country position
    }

    @Test
    void isIsin_rejectsNullAndBlank() {
        assertThat(OpenFigiIsinConverter.isIsin(null)).isFalse();
        assertThat(OpenFigiIsinConverter.isIsin("")).isFalse();
        assertThat(OpenFigiIsinConverter.isIsin("   ")).isFalse();
    }

    @Test
    void isTrCryptoIsin_detectsXf000PrefixCaseAndWhitespaceInsensitively() {
        // Shared with TradeRepublicAdapter's exchange choice; must tolerate the same
        // case/whitespace variants resolve()'s normalization does (unlike a raw startsWith).
        assertThat(OpenFigiIsinConverter.isTrCryptoIsin("XF000BTC0017")).isTrue();
        assertThat(OpenFigiIsinConverter.isTrCryptoIsin(" xf000btc0017 ")).isTrue();
        assertThat(OpenFigiIsinConverter.isTrCryptoIsin("IE00B4L5Y983")).isFalse(); // real ISIN
        assertThat(OpenFigiIsinConverter.isTrCryptoIsin("BTC")).isFalse();
        assertThat(OpenFigiIsinConverter.isTrCryptoIsin(null)).isFalse();
    }

    @Test
    void resolve_parsesTickerAndNameForTradeRepublicCryptoIsins() {
        OpenFigiIsinConverter converter = converterWith(silentCatalog());

        // Ticker is now the parsed symbol (not the fake ISIN), so the holding becomes
        // price-resolvable via CoinGeckoPriceProvider instead of staying stuck on averageBuyIn.
        OpenFigiIsinConverter.TickerResult btc = converter.resolve("XF000BTC0017");
        assertThat(btc.ticker()).isEqualTo("BTC");
        assertThat(btc.name()).isEqualTo("Bitcoin");

        OpenFigiIsinConverter.TickerResult eth = converter.resolve("XF000ETH0017");
        assertThat(eth.ticker()).isEqualTo("ETH");
        assertThat(eth.name()).isEqualTo("Ethereum");
    }

    @Test
    void resolve_parsesAnyKnownCryptoSymbolNotJustBtcAndEth() {
        OpenFigiIsinConverter converter = converterWith(silentCatalog());

        // The symbol is parsed generically from the "XF000<SYMBOL><digits>" pattern and
        // validated against CoinGeckoPriceProvider's known tickers -- SOL isn't hardcoded
        // anywhere in OpenFigiIsinConverter (GH issue #22). The display name is derived
        // from the provider's coin registry too, so every known coin gets a real name,
        // including multi-word ids ("matic-network" -> "Matic Network").
        OpenFigiIsinConverter.TickerResult sol = converter.resolve("XF000SOL0042");
        assertThat(sol.ticker()).isEqualTo("SOL");
        assertThat(sol.name()).isEqualTo("Solana");

        OpenFigiIsinConverter.TickerResult matic = converter.resolve("XF000MATIC0099");
        assertThat(matic.ticker()).isEqualTo("MATIC");
        assertThat(matic.name()).isEqualTo("Matic Network");
    }

    @Test
    void resolve_normalizesCaseAndWhitespaceConsistently() {
        OpenFigiIsinConverter converter = converterWith(silentCatalog());

        OpenFigiIsinConverter.TickerResult padded = converter.resolve(" xf000btc0017 ");
        assertThat(padded.ticker()).isEqualTo("BTC");
        assertThat(padded.name()).isEqualTo("Bitcoin");
    }

    // ─── resolveIsinOrSymbol ────────────────────────────────────────────────────

    @Test
    void resolveIsinOrSymbol_fallsBackToBrokerSymbolAndLabelWithoutAnIsin() {
        // No ISIN means no OpenFIGI call at all: the broker's own symbol becomes the
        // ticker (DEGIRO/BoursoBank positions on venues that ship no ISIN).
        OpenFigiIsinConverter converter = converterWith(silentCatalog());

        assertThat(converter.resolveIsinOrSymbol(null, "AAPL", "Apple Inc"))
            .isEqualTo(new OpenFigiIsinConverter.TickerResult("AAPL", "Apple Inc"));
        assertThat(converter.resolveIsinOrSymbol("  ", "AAPL", "Apple Inc"))
            .isEqualTo(new OpenFigiIsinConverter.TickerResult("AAPL", "Apple Inc"));
    }

    // ─── pickBest exchange priority ─────────────────────────────────────────────
    // A 2026-08-05 attempt to prefer EU exchanges over US OTC/ADR for Irish/
    // Luxembourg-domiciled ISINs (no HOME_EXCHANGE entry for either) was tried
    // and reverted: it fixed IE000BI8OT95 ("MWRD") but broke two other real
    // holdings on the same live portfolio (IE00BGSF1X88, IE00BD6FTQ80) whose EU
    // tickers have no live Yahoo quote while their US OTC ones do. See the
    // class-level Javadoc on pickBest() for the full account. These tests lock
    // in the reverted (original) US-OTC-first behavior — which stays the
    // preference order; what a dead pick costs is now handled by priceable()
    // below rather than by re-guessing here.

    private final OpenFigiIsinConverter converter = converterWith(silentCatalog());

    @Test
    void pickBest_prefersUsOtcOverEuExchangeWhenNoHomeExchangeMatches() {
        // IE: no HOME_EXCHANGE entry — mirrors IE000BI8OT95 exactly (FP + US both present).
        List<Map<String, Object>> entries = List.of(
            entry("US", "MWRDF", "AMUNDI MSCI WORLD USD ACC"),
            entry("FP", "MWRD", "AM CORE MSCI WORLD U ETF ACC")
        );

        OpenFigiIsinConverter.TickerResult result = converter.pickBest("IE000BI8OT95", entries);

        assertThat(result.ticker()).isEqualTo("MWRDF");
        assertThat(result.name()).isEqualTo("AMUNDI MSCI WORLD USD ACC");
    }

    @Test
    void pickBest_fallsBackToEuExchangeWhenNoUsOtcAvailable() {
        List<Map<String, Object>> entries = List.of(
            entry("FP", "SOMEF", "SOME FUND WITH NO US OTC LISTING")
        );

        OpenFigiIsinConverter.TickerResult result = converter.pickBest("IE00XXXXXXXX", entries);

        assertThat(result.ticker()).isEqualTo("SOMEF.PA");
    }

    @Test
    void pickBest_homeExchangeStillWinsOverUsAndEu() {
        // FR has a HOME_EXCHANGE entry (FP) — must still short-circuit before the
        // US/EU fallback ordering below it.
        List<Map<String, Object>> entries = List.of(
            entry("US", "BNPQY", "BNP PARIBAS ADR"),
            entry("FP", "BNP", "BNP PARIBAS SA"),
            entry("GR", "BNP", "BNP PARIBAS SA")
        );

        OpenFigiIsinConverter.TickerResult result = converter.pickBest("FR0000131104", entries);

        assertThat(result.ticker()).isEqualTo("BNP.PA");
    }

    @Test
    void pickBest_fallsBackToAnyKnownExchangeWhenNoHomeUsOrEuMatch() {
        List<Map<String, Object>> entries = List.of(
            entry("HK", "1234", "SOME HONG KONG LISTING")
        );

        OpenFigiIsinConverter.TickerResult result = converter.pickBest("KYG000000000", entries);

        assertThat(result.ticker()).isEqualTo("1234.HK");
    }

    @Test
    void pickBest_rejectsBondDescriptionsThatAreNotSymbols() {
        OpenFigiIsinConverter converter = converterWith(silentCatalog());

        List<Map<String, Object>> entries = List.of(Map.of(
            "ticker", "AIRBAL 14.5 08/14/29 REGS",
            "exchCode", "EURONEXT-DUBLIN",
            "name", "AIR BALTIC CORPORATION"));

        assertThat(converter.pickBest("XS2657412201", entries)).isNull();
    }

    @Test
    void pickBest_stillResolvesRealSymbolsOnKnownExchanges() {
        OpenFigiIsinConverter converter = converterWith(silentCatalog());

        List<Map<String, Object>> entries = List.of(Map.of(
            "ticker", "MBG",
            "exchCode", "GY",
            "name", "MERCEDES-BENZ GROUP AG"));

        OpenFigiIsinConverter.TickerResult equity = converter.pickBest("DE0007100000", entries);

        assertThat(equity).isNotNull();
        assertThat(equity.ticker()).isEqualTo("MBG.DE");
    }

    @Test
    void pickBest_returnsTheNormalizedSymbol_notTheRawOpenFigiValue() {
        OpenFigiIsinConverter converter = converterWith(silentCatalog());

        List<Map<String, Object>> padded = List.of(Map.of(
            "ticker", " mbg ",
            "exchCode", "GY",
            "name", "MERCEDES-BENZ GROUP AG"));

        assertThat(converter.pickBest("DE0007100000", padded).ticker()).isEqualTo("MBG.DE");

        List<Map<String, Object>> unknownExchange = List.of(Map.of(
            "ticker", " aapl ",
            "exchCode", "NOT LISTED",
            "name", "APPLE INC"));

        assertThat(converter.pickBest("US0378331005", unknownExchange).ticker()).isEqualTo("AAPL");
    }

    // ─── priceable: the OpenFIGI pick verified against Yahoo ────────────────────
    // pickBest() answers "which listing do we prefer", which is not the same question as
    // "which listing does Yahoo quote" — and only the second one decides whether a holding
    // gets a value at all. GH issues #74 (a PEA of ETFs reading 0 €) and #78.

    private static final OpenFigiIsinConverter.TickerResult MWRDF =
        new OpenFigiIsinConverter.TickerResult("MWRDF", "AMUNDI MSCI WORLD USD ACC");

    @Test
    void priceable_keepsTheOpenFigiPickWhenYahooQuotesIt() {
        SymbolCatalogPort catalog = silentCatalog();
        when(catalog.hasQuote("IWDA.AS")).thenReturn(true);
        var figi = new OpenFigiIsinConverter.TickerResult("IWDA.AS", "ISHARES CORE MSCI WORLD");

        var result = converterWith(catalog).priceable("IE00B4L5Y983", figi);

        assertThat(result).isEqualTo(figi);
        // A portfolio that prices correctly today must not pay for a search it does not need,
        // nor see its tickers churn to a different listing (and a different quote currency).
        verify(catalog, never()).searchSymbols(anyString());
    }

    @Test
    void priceable_fallsBackToTheSymbolYahooSearchQuotes_whenThePickIsDelisted() {
        // IE000BI8OT95: OpenFIGI's US OTC pick is delisted on Yahoo, its Paris listing is live.
        SymbolCatalogPort catalog = silentCatalog();
        when(catalog.hasQuote("MWRDF")).thenReturn(false);
        when(catalog.searchSymbols("IE000BI8OT95")).thenReturn(List.of(
            new SymbolCatalogPort.SymbolMatch("MWRD.PA", "Amundi Core MSCI World UCITS ET")));
        when(catalog.hasQuote("MWRD.PA")).thenReturn(true);

        var result = converterWith(catalog).priceable("IE000BI8OT95", MWRDF);

        assertThat(result.ticker()).isEqualTo("MWRD.PA");
        // OpenFIGI's name is the official one; Yahoo's is a truncated display label.
        assertThat(result.name()).isEqualTo("AMUNDI MSCI WORLD USD ACC");
    }

    @Test
    void priceable_skipsSearchSymbolsYahooDoesNotQuoteEither() {
        SymbolCatalogPort catalog = silentCatalog();
        when(catalog.hasQuote("MWRDF")).thenReturn(false);
        when(catalog.searchSymbols("IE000BI8OT95")).thenReturn(List.of(
            new SymbolCatalogPort.SymbolMatch("MWRD.XX", "delisted too"),
            new SymbolCatalogPort.SymbolMatch("MWRD.PA", "Amundi Core MSCI World UCITS ET")));
        when(catalog.hasQuote("MWRD.XX")).thenReturn(false);
        when(catalog.hasQuote("MWRD.PA")).thenReturn(true);

        assertThat(converterWith(catalog).priceable("IE000BI8OT95", MWRDF).ticker()).isEqualTo("MWRD.PA");
    }

    @Test
    void priceable_probesAtMostThreeCandidates() {
        // resolve() runs inside the transaction of a user saving a transaction or importing a CSV,
        // so the fan-out is bounded: Yahoo answers in relevance order, and a listing outside the
        // first few is not the one being looked for. Probing all six would turn a miss into eight
        // requests on a write path.
        SymbolCatalogPort catalog = silentCatalog();
        when(catalog.hasQuote(anyString())).thenReturn(false);
        when(catalog.searchSymbols("IE000BI8OT95")).thenReturn(List.of(
            new SymbolCatalogPort.SymbolMatch("A.PA", "a"),
            new SymbolCatalogPort.SymbolMatch("B.PA", "b"),
            new SymbolCatalogPort.SymbolMatch("C.PA", "c"),
            new SymbolCatalogPort.SymbolMatch("D.PA", "d"),
            new SymbolCatalogPort.SymbolMatch("E.PA", "e")));

        assertThat(converterWith(catalog).priceable("IE000BI8OT95", MWRDF)).isEqualTo(MWRDF);

        verify(catalog).hasQuote("A.PA");
        verify(catalog).hasQuote("B.PA");
        verify(catalog).hasQuote("C.PA");
        verify(catalog, never()).hasQuote("D.PA");
        verify(catalog, never()).hasQuote("E.PA");
    }

    @Test
    void priceable_keepsTheOpenFigiPickWhenNothingElseQuotesEither() {
        // Yahoo down, rate-limited, or an instrument it simply does not carry: the result must be
        // exactly what it was before this validation existed, never a downgrade.
        SymbolCatalogPort catalog = silentCatalog();
        when(catalog.hasQuote(anyString())).thenReturn(false);
        when(catalog.searchSymbols("IE000BI8OT95")).thenReturn(List.of(
            new SymbolCatalogPort.SymbolMatch("MWRD.PA", "Amundi Core MSCI World UCITS ET")));

        assertThat(converterWith(catalog).priceable("IE000BI8OT95", MWRDF)).isEqualTo(MWRDF);
    }

    @Test
    void priceable_doesNotProbeTheSamePickTwice() {
        // Yahoo's search legitimately returns the symbol OpenFIGI picked; it was already probed.
        SymbolCatalogPort catalog = silentCatalog();
        when(catalog.hasQuote("MWRDF")).thenReturn(false);
        when(catalog.searchSymbols("IE000BI8OT95")).thenReturn(List.of(
            new SymbolCatalogPort.SymbolMatch("MWRDF", "AMUNDI MSCI WORLD USD ACC")));

        assertThat(converterWith(catalog).priceable("IE000BI8OT95", MWRDF)).isEqualTo(MWRDF);
        verify(catalog, times(1)).hasQuote("MWRDF");
    }

    @Test
    void priceable_resolvesFromSearchWhenOpenFigiReturnedNothing() {
        // The other half of GH issue #74: OpenFIGI misses entirely (down, or rate-limited at 25
        // req/min without a key) and the ISIN itself gets persisted as the ticker, which nothing
        // can ever price. Yahoo's own search still knows the instrument.
        SymbolCatalogPort catalog = silentCatalog();
        when(catalog.searchSymbols("FR0000121014")).thenReturn(List.of(
            new SymbolCatalogPort.SymbolMatch("MC.PA", "LVMH Moet Hennessy Louis Vuitton")));
        when(catalog.hasQuote("MC.PA")).thenReturn(true);

        var result = converterWith(catalog).priceable("FR0000121014", null);

        assertThat(result.ticker()).isEqualTo("MC.PA");
        assertThat(result.name()).isEqualTo("LVMH Moet Hennessy Louis Vuitton");
    }

    @Test
    void priceable_returnsNullWhenNeitherSideKnowsTheInstrument() {
        // Caller then falls back to the ISIN as the ticker, as before.
        SymbolCatalogPort catalog = silentCatalog();

        assertThat(converterWith(catalog).priceable("XS2657412201", null)).isNull();
    }
}
