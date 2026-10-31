package com.picsou.finary;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.finary.dto.FinaryAccountCurrency;
import com.picsou.finary.dto.FinaryAccountDto;
import com.picsou.finary.dto.FinaryAssetRefDto;
import com.picsou.finary.dto.FinaryPositionDto;
import com.picsou.finary.dto.FinarySecurityRefDto;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.repository.AccountHoldingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinaryHoldingsImporterTest {

    @Mock AccountHoldingRepository holdingRepository;
    @Mock OpenFigiIsinConverter isinConverter;
    @InjectMocks FinaryHoldingsImporter importer;

    @Test
    void importHoldings_persistsSecurityWithOpenFigiTickerAndSkipsCash() {
        when(isinConverter.resolve("FR0000120628"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("CS.PA", "AXA"));
        when(holdingRepository.save(any(AccountHolding.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        Account account = account();
        FinaryAccountDto finary = portfolio(
            List.of(
                security("AXA", "FR0000120628", 54, 1892.052, 2420.28, 528.228),
                cashSecurity(3447.07)
            ),
            null,
            null,
            List.of(fiat(3447.07))
        );

        int saved = importer.importHoldings(account, finary, true);

        assertThat(saved).isEqualTo(1);
        assertThat(account.getCashBalance()).isEqualByComparingTo("6894.14");

        ArgumentCaptor<AccountHolding> captor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository).save(captor.capture());
        AccountHolding holding = captor.getValue();
        assertThat(holding.getTicker()).isEqualTo("CS.PA");
        assertThat(holding.getName()).isEqualTo("AXA");
        assertThat(holding.getQuantity()).isEqualByComparingTo("54");
        assertThat(holding.getAverageBuyIn()).isEqualByComparingTo("35.038");
        assertThat(holding.getProviderValueEur()).isEqualByComparingTo("2420.28");
        assertThat(holding.getProviderPnlEur()).isEqualByComparingTo("528.228");
        assertThat(holding.getQuoteCurrency()).isEqualTo("EUR");
    }

    @Test
    void importHoldings_usesCryptoCodeAndDisplayEurValues() {
        when(holdingRepository.save(any(AccountHolding.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        Account account = account();
        FinaryPositionDto btc = new FinaryPositionDto(
            "h1", null, "user_crypto", 0.14427879,
            57343.07, 8273.39, 63622.0, 9179.30, 905.91,
            7139.22, 54900.27, 7920.94, 781.73,
            null,
            new FinaryAssetRefDto(1L, "Bitcoin", "BTC", null, "currency"),
            null
        , null, null);
        FinaryAccountDto finary = portfolio(null, List.of(btc), null, null);

        importer.importHoldings(account, finary, true);

        ArgumentCaptor<AccountHolding> captor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository).save(captor.capture());
        AccountHolding holding = captor.getValue();
        assertThat(holding.getTicker()).isEqualTo("BTC");
        assertThat(holding.getName()).isEqualTo("Bitcoin");
        assertThat(holding.getProviderValueEur()).isEqualByComparingTo("7920.94");
        assertThat(holding.getCurrentPrice()).isEqualByComparingTo("54900.27");
        assertThat(account.getCashBalance()).isNull();
    }

    @Test
    void importHoldings_sanitizesLongRealTCodesAndFondsEuroName() {
        when(holdingRepository.save(any(AccountHolding.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        String longCode = "REALTOKEN-S-4478-LILLIBRIDGE-ST-DETROIT-MI";
        FinaryPositionDto realt = new FinaryPositionDto(
            "h2", null, "user_crypto", 2.0,
            null, 80.0, null, 101.42, 21.42,
            70.0, null, 87.5, 17.5,
            null,
            new FinaryAssetRefDto(2L, "RealT Detroit", longCode, null, "currency"),
            null
        , null, null);
        FinaryPositionDto fonds = new FinaryPositionDto(
            "fe1", "FDS EUROS SURAVENIR RENDEMENT", "fonds_euro", 1.0,
            null, 20876.93, null, 20876.93, 0.0,
            20876.93, null, 20876.93, 0.0,
            null, null, null
        , null, null);
        FinaryAccountDto finary = portfolio(null, List.of(realt), List.of(fonds), null);

        int saved = importer.importHoldings(account(), finary, true);

        assertThat(saved).isEqualTo(2);
        ArgumentCaptor<AccountHolding> captor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(AccountHolding::getTicker)
            .containsExactlyInAnyOrder(
                longCode,
                "FINARY_FDS_EUROS_SURAVENIR_RENDEMENT"
            );
        assertThat(longCode.length()).isLessThanOrEqualTo(FinaryHoldingsImporter.TICKER_MAX);
    }

    @Test
    void importHoldings_clearsExistingWhenFinaryConfirmsAnEmptyPortfolio() {
        Account account = account();
        account.setCashBalance(new BigDecimal("500"));
        FinaryAccountDto empty = portfolio(List.of(), List.of(), List.of(), List.of());

        int saved = importer.importHoldings(account, empty, true);

        assertThat(saved).isZero();
        assertThat(account.getCashBalance()).isNull();
        verify(holdingRepository).deleteByAccountId(9L);
        verify(holdingRepository, never()).save(any());
    }

    @Test
    void importHoldings_keepsExistingHoldingsAndCash_whenFinaryOmitsEveryPositionList() {
        Account account = account();
        account.setCashBalance(new BigDecimal("500"));
        FinaryAccountDto omitted = accountDto(null, null, null, null);

        int saved = importer.importHoldings(account, omitted, true);

        assertThat(saved).isZero();
        assertThat(account.getCashBalance()).isEqualByComparingTo("500");
        verify(holdingRepository, never()).deleteByAccountId(any());
        verify(holdingRepository, never()).save(any());
    }

    @Test
    void importHoldings_keepsExistingHoldingsAndCash_whenFinaryOmitsSomePositionLists() {
        Account account = account();
        account.setCashBalance(new BigDecimal("500"));
        FinaryAccountDto fiatsOnly = accountDto(null, null, null, List.of());

        int saved = importer.importHoldings(account, fiatsOnly, true);

        assertThat(saved).isZero();
        assertThat(account.getCashBalance()).isEqualByComparingTo("500");
        verify(holdingRepository, never()).deleteByAccountId(any());
        verify(holdingRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"generic_assets", "scpis", "precious_metals"})
    void importHoldings_keepsExistingHoldingsAndCash_whenFinaryOmitsAnyImportedGroup(String omitted) {
        Account account = account();
        account.setCashBalance(new BigDecimal("500"));
        List<FinaryPositionDto> empty = List.of();
        FinaryAccountDto finary = accountDto(empty, empty, empty, empty,
            omitted.equals("generic_assets") ? null : empty,
            omitted.equals("scpis") ? null : empty,
            omitted.equals("precious_metals") ? null : empty);

        int saved = importer.importHoldings(account, finary, true);

        assertThat(saved).isZero();
        assertThat(account.getCashBalance()).isEqualByComparingTo("500");
        verify(holdingRepository, never()).deleteByAccountId(any());
        verify(holdingRepository, never()).save(any());
    }

    @Test
    void importHoldings_keepsExistingHoldingsAndCash_whenAFiatHasNoEurValue() {
        assertKeepsExisting(portfolio(
            List.of(security("AXA", null, 1, 90, 100, 10)), null, null, List.of(withoutEurValue(fiat(100)))));
    }

    @Test
    void importHoldings_keepsExistingHoldingsAndCash_whenACashSecurityHasNoEurValue() {
        assertKeepsExisting(portfolio(
            List.of(security("AXA", null, 1, 90, 100, 10), withoutEurValue(cashSecurity(100))), null, null, null));
    }

    private void assertKeepsExisting(FinaryAccountDto finary) {
        Account account = account();
        account.setCashBalance(new BigDecimal("500"));

        int saved = importer.importHoldings(account, finary, true);

        assertThat(saved).isZero();
        assertThat(account.getCashBalance()).isEqualByComparingTo("500");
        verify(holdingRepository, never()).deleteByAccountId(any());
        verify(holdingRepository, never()).save(any());
    }

    @Test
    void importHoldings_keepsExistingHoldingsAndCash_whenTheDisplayCurrencyIsUnverified() {
        Account account = account();
        account.setCashBalance(new BigDecimal("500"));
        FinaryAccountDto finary = portfolio(
            List.of(security("AXA", "FR0000120628", 54, 1892.052, 2420.28, 528.228)), null, null, List.of());

        int saved = importer.importHoldings(account, finary, false);

        assertThat(saved).isZero();
        assertThat(account.getCashBalance()).isEqualByComparingTo("500");
        verify(holdingRepository, never()).deleteByAccountId(any());
        verify(holdingRepository, never()).save(any());
    }

    @Test
    void importHoldings_ignoresNullPositionsAndNullFiats() {
        when(isinConverter.resolve("FR0000120628"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("CS.PA", "AXA"));
        when(holdingRepository.save(any(AccountHolding.class))).thenAnswer(inv -> inv.getArgument(0));
        Account account = account();
        FinaryAccountDto finary = portfolio(
            java.util.Arrays.asList(null, security("AXA", "FR0000120628", 54, 1892.052, 2420.28, 528.228)),
            null, null, java.util.Arrays.asList(null, fiat(10)));

        int saved = importer.importHoldings(account, finary, true);

        assertThat(saved).isEqualTo(1);
        assertThat(account.getCashBalance()).isEqualByComparingTo("10");
    }

    @Test
    void importHoldings_leavesTheValueUnknown_whenOneLineOfAMergedTickerHasNoValue() {
        when(isinConverter.resolve("FR0000120628"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("CS.PA", "AXA"));
        when(holdingRepository.save(any(AccountHolding.class))).thenAnswer(inv -> inv.getArgument(0));
        FinaryPositionDto valued = security("AXA", "FR0000120628", 1, 90, 100, 10);
        FinaryPositionDto unvalued = new FinaryPositionDto(
            "s2", "AXA", "user_security", 1.0,
            null, null, null, null, null,
            null, null, null, null,
            new FinarySecurityRefDto(1L, "AXA", "FR0000120628", null, null, "security"),
            null, null, null, null);

        importer.importHoldings(account(), portfolio(List.of(valued, unvalued), null, null, null), true);

        ArgumentCaptor<AccountHolding> captor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository).save(captor.capture());
        assertThat(captor.getValue().getQuantity()).isEqualByComparingTo("2");
        assertThat(captor.getValue().getProviderValueEur()).isNull();
        assertThat(captor.getValue().getProviderPnlEur()).isNull();
    }

    @Test
    void importHoldings_leavesTheCostUnknown_whenOneLineOfAMergedTickerHasNoBuyingValue() {
        when(isinConverter.resolve("FR0000120628"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("CS.PA", "AXA"));
        when(holdingRepository.save(any(AccountHolding.class))).thenAnswer(inv -> inv.getArgument(0));
        FinaryPositionDto known = security("AXA", "FR0000120628", 1, 90, 100, 10);
        FinaryPositionDto unknownCost = new FinaryPositionDto(
            "s2", "AXA", "user_security", 1.0,
            null, null, null, null, null,
            null, null, 100.0, null,
            new FinarySecurityRefDto(1L, "AXA", "FR0000120628", null, null, "security"),
            null, null, null, null);

        importer.importHoldings(account(), portfolio(List.of(known, unknownCost), null, null, null), true);

        ArgumentCaptor<AccountHolding> captor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository).save(captor.capture());
        assertThat(captor.getValue().getQuantity()).isEqualByComparingTo("2");
        assertThat(captor.getValue().getAverageBuyIn()).isNull();
    }

    @Test
    void importHoldings_neverLabelsANativeValueAsEur_whenTheDisplayValueIsMissing() {
        when(holdingRepository.save(any(AccountHolding.class))).thenAnswer(inv -> inv.getArgument(0));
        FinaryPositionDto nativeOnly = new FinaryPositionDto(
            "h3", null, "user_crypto", 1.0,
            90.0, 90.0, 100.0, 100.0, 10.0,
            null, null, null, null,
            null,
            new FinaryAssetRefDto(3L, "Ether", "ETH", null, "currency"),
            null, null, null);

        importer.importHoldings(account(), portfolio(null, List.of(nativeOnly), null, null), true);

        ArgumentCaptor<AccountHolding> captor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository).save(captor.capture());
        AccountHolding holding = captor.getValue();
        assertThat(holding.getProviderValueEur()).isNull();
        assertThat(holding.getProviderPnlEur()).isNull();
        assertThat(holding.getCurrentPrice()).isNull();
        assertThat(holding.getAverageBuyIn()).isNull();
    }

    @Test
    void importHoldings_takesASecuritysEurPnlFromDisplayCurrentUpnl() {
        when(isinConverter.resolve("FR0000120628"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("CS.PA", "AXA"));
        when(holdingRepository.save(any(AccountHolding.class))).thenAnswer(inv -> inv.getArgument(0));
        FinaryPositionDto security = new FinaryPositionDto(
            "s1", "AXA", "user_security", 1.0,
            null, null, null, 100.0, null,
            null, null, 90.0, null,
            new FinarySecurityRefDto(1L, "AXA", "FR0000120628", null, null, "security"),
            null, null, 10.0, 9.0);

        importer.importHoldings(account(), portfolio(List.of(security), null, null, null), true);

        ArgumentCaptor<AccountHolding> captor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository).save(captor.capture());
        assertThat(captor.getValue().getProviderValueEur()).isEqualByComparingTo("90");
        assertThat(captor.getValue().getProviderPnlEur()).isEqualByComparingTo("9");
    }

    @Test
    void isCash_detectsSoldeEspecesAndFiats() {
        assertThat(FinaryHoldingsImporter.isCash(cashSecurity(10))).isTrue();
        assertThat(FinaryHoldingsImporter.isCash(fiat(10))).isTrue();
        assertThat(FinaryHoldingsImporter.isCash(
            security("AXA", "FR0000120628", 1, 1, 1, 0))).isFalse();
    }

    @Test
    void sanitizeTicker_uppercasesStripsAndTruncates() {
        assertThat(FinaryHoldingsImporter.sanitizeTicker(" btc ")).isEqualTo("BTC");
        assertThat(FinaryHoldingsImporter.sanitizeTicker("Foo Bar/Baz")).isEqualTo("FOO_BAR_BAZ");
        String over = "A".repeat(120);
        assertThat(FinaryHoldingsImporter.sanitizeTicker(over)).hasSize(100);
    }

    private static Account account() {
        return Account.builder()
            .id(9L)
            .name("PEA")
            .type(AccountType.PEA)
            .currency("EUR")
            .currentBalance(BigDecimal.ZERO)
            .build();
    }

    private static FinaryAccountDto accountDto(
            List<FinaryPositionDto> securities,
            List<FinaryPositionDto> cryptos,
            List<FinaryPositionDto> fondsEuro,
            List<FinaryPositionDto> fiats) {
        return accountDto(securities, cryptos, fondsEuro, fiats, null, null, null);
    }

    private static FinaryAccountDto accountDto(
            List<FinaryPositionDto> securities,
            List<FinaryPositionDto> cryptos,
            List<FinaryPositionDto> fondsEuro,
            List<FinaryPositionDto> fiats,
            List<FinaryPositionDto> genericAssets,
            List<FinaryPositionDto> scpis,
            List<FinaryPositionDto> preciousMetals) {
        return new FinaryAccountDto(
            "acc-1", "PEA", null, 1000.0, 1000.0, null,
            new FinaryAccountCurrency("EUR", "€"), false,
            1000.0, securities, cryptos, fondsEuro, fiats, genericAssets, scpis, preciousMetals);
    }

    /** A complete snapshot: the lists left null here are sent empty, as Finary does. */
    private static FinaryAccountDto portfolio(
            List<FinaryPositionDto> securities,
            List<FinaryPositionDto> cryptos,
            List<FinaryPositionDto> fondsEuro,
            List<FinaryPositionDto> fiats) {
        return accountDto(orEmpty(securities), orEmpty(cryptos), orEmpty(fondsEuro), orEmpty(fiats),
            List.of(), List.of(), List.of());
    }

    private static FinaryPositionDto withoutEurValue(FinaryPositionDto p) {
        return new FinaryPositionDto(
            p.id(), p.name(), p.type(), p.quantity(),
            p.buyingPrice(), p.buyingValue(), p.currentPrice(), p.currentValue(), p.unrealizedPnl(),
            null, null, null, null,
            p.security(), p.crypto(), p.fiat(), p.currentUpnl(), null);
    }

    private static List<FinaryPositionDto> orEmpty(List<FinaryPositionDto> list) {
        return list != null ? list : List.of();
    }

    private static FinaryPositionDto security(
            String name, String isin, double qty, double buy, double value, double pnl) {
        return new FinaryPositionDto(
            "s1", name, "user_security", qty,
            buy / qty, buy, null, value, pnl,
            buy, null, value, pnl,
            new FinarySecurityRefDto(1L, name, isin, null, null, "security"),
            null, null, null, null);
    }

    private static FinaryPositionDto cashSecurity(double value) {
        return new FinaryPositionDto(
            "cash", "Solde Espèces", "user_security", value,
            1.0, value, 1.0, value, 0.0,
            value, 1.0, value, 0.0,
            new FinarySecurityRefDto(2L, "Solde Espèces", null, "000000000000", null, "security"),
            null, null, null, null);
    }

    private static FinaryPositionDto fiat(double value) {
        return new FinaryPositionDto(
            "f1", "Euro", "user_fiat", value,
            1.0, value, 1.0, value, 0.0,
            value, 1.0, value, 0.0,
            null, null,
            new FinaryAssetRefDto(1L, "Euro", "EUR", "€", "currency"), null, null);
    }
}
