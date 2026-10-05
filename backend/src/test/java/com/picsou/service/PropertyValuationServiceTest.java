package com.picsou.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.dto.PropertyValuationResponse;
import com.picsou.exception.ValuationProviderException;
import com.picsou.model.*;
import com.picsou.port.GeocodingPort;
import com.picsou.port.HousingPriceIndexPort;
import com.picsou.port.PropertyValuationPort;
import com.picsou.port.PropertyValuationPort.ValuationInput;
import com.picsou.port.PropertyValuationPort.ValuationResult;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.PropertyValuationRepository;
import com.picsou.repository.RealEstateMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The contract that matters here is behavioural: a MANUAL property must never have its
 * balance rewritten, an uncovered area must produce an explicit status rather than a number,
 * and an outage must leave the previous value alone.
 */
@ExtendWith(MockitoExtension.class)
class PropertyValuationServiceTest {

    private static final FamilyMember ALICE = FamilyMember.builder().id(1L).displayName("Alice").build();

    @Mock PropertyValuationPort provider;
    @Mock GeocodingPort geocoder;
    @Mock HousingPriceIndexPort priceIndex;
    @Mock RealEstateMetadataRepository metadataRepository;
    @Mock PropertyValuationRepository valuationRepository;
    @Mock AccountRepository accountRepository;
    @Mock AccountAccessResolver accessResolver;

    private PropertyValuationService service;
    private CountingTxManager txManager;

    /**
     * Runs callbacks straight through while counting boundaries, so a test can assert that each
     * property gets its own transaction rather than sharing the caller's.
     */
    private static final class CountingTxManager implements PlatformTransactionManager {
        int started;
        int committed;
        int rolledBack;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            started++;
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            committed++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rolledBack++;
        }
    }

    @BeforeEach
    void setUp() {
        txManager = new CountingTxManager();
        service = new PropertyValuationService(
            List.of(provider), geocoder, priceIndex, new PropertyAdjustments(),
            metadataRepository, valuationRepository, accountRepository, accessResolver,
            new ObjectMapper(), new TransactionTemplate(txManager), true);
    }

    private static Account house() {
        return Account.builder()
            .id(10L).name("Maison").type(AccountType.REAL_ESTATE).currency("EUR")
            .currentBalance(new BigDecimal("300000")).color("#a855f7").member(ALICE)
            .build();
    }

    private static RealEstateMetadata metadata(Account account, String type, String insee, ValuationMode mode) {
        return RealEstateMetadata.builder()
            .account(account).member(ALICE)
            .purchasePrice(new BigDecimal("300000"))
            .propertyType(type)
            .surfaceArea(new BigDecimal("100"))
            .inseeCode(insee)
            .country("FR")
            .valuationMode(mode)
            .garageCount((short) 0).parkingCount((short) 0)
            .hasGarden(false).hasTerrace(false).hasBalcony(false)
            .rentalIncome(BigDecimal.ZERO)
            .build();
    }

    private void stubSuccessfulProvider() {
        when(provider.supports(any())).thenReturn(true);
        when(provider.providerName()).thenReturn("CEREMA_DV3F");
        when(provider.estimate(any())).thenReturn(Optional.of(new ValuationResult(
            new BigDecimal("400000"), new BigDecimal("350000"), new BigDecimal("470000"),
            new BigDecimal("4000"), 500, ValuationConfidence.HIGH, (short) 2025, "communes")));
        when(valuationRepository.findByAccountIdAndValuedAt(eq(10L), any(LocalDate.class)))
            .thenReturn(Optional.empty());
        when(valuationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void estimate_estimatedMode_writesTheBalance() {
        Account account = house();
        when(accessResolver.requireOwner(10L, 1L)).thenReturn(account);
        when(metadataRepository.findByAccountId(10L))
            .thenReturn(Optional.of(metadata(account, "HOUSE", "33063", ValuationMode.ESTIMATED)));
        stubSuccessfulProvider();
        when(priceIndex.reindexRatio(any(), any(), any())).thenReturn(Optional.empty());

        PropertyValuationResponse result = service.estimate(10L, 1L);

        assertThat(result.status()).isEqualTo(ValuationStatus.OK);
        assertThat(result.appliedToBalance()).isTrue();
        assertThat(result.estimatedValue()).isEqualByComparingTo("400000.00");
        // The written balance is what makes net worth and the daily snapshot follow along.
        assertThat(account.getCurrentBalance()).isEqualByComparingTo("400000.00");
        verify(accountRepository).save(account);
    }

    @Test
    void estimate_manualMode_computesButNeverTouchesTheBalance() {
        Account account = house();
        when(accessResolver.requireOwner(10L, 1L)).thenReturn(account);
        when(metadataRepository.findByAccountId(10L))
            .thenReturn(Optional.of(metadata(account, "HOUSE", "33063", ValuationMode.MANUAL)));
        stubSuccessfulProvider();
        when(priceIndex.reindexRatio(any(), any(), any())).thenReturn(Optional.empty());

        PropertyValuationResponse result = service.estimate(10L, 1L);

        // The estimate is still recorded so the user can compare it against their own figure.
        assertThat(result.status()).isEqualTo(ValuationStatus.OK);
        assertThat(result.appliedToBalance()).isFalse();
        assertThat(account.getCurrentBalance()).isEqualByComparingTo("300000");
        verify(accountRepository, never()).save(any());
    }

    @Test
    void estimate_reindexesFromTheSourceVintageToToday() {
        Account account = house();
        when(accessResolver.requireOwner(10L, 1L)).thenReturn(account);
        when(metadataRepository.findByAccountId(10L))
            .thenReturn(Optional.of(metadata(account, "HOUSE", "33063", ValuationMode.ESTIMATED)));
        stubSuccessfulProvider();
        when(priceIndex.reindexRatio(any(), any(), any())).thenReturn(Optional.of(new BigDecimal("1.05")));

        PropertyValuationResponse result = service.estimate(10L, 1L);

        // DVF lags by a year or more; without this the estimate silently reports old money.
        assertThat(result.estimatedValue()).isEqualByComparingTo("420000.00");
        assertThat(result.reindexRatio()).isEqualByComparingTo("1.05");
    }

    @Test
    void estimate_uncoveredArea_saysSoInsteadOfGuessing() {
        Account account = house();
        when(accessResolver.requireOwner(10L, 1L)).thenReturn(account);
        when(metadataRepository.findByAccountId(10L))
            .thenReturn(Optional.of(metadata(account, "HOUSE", "67482", ValuationMode.ESTIMATED)));
        when(provider.supports(any())).thenReturn(false);

        PropertyValuationResponse result = service.estimate(10L, 1L);

        // Alsace-Moselle keeps the livre foncier registry. A plausible-looking number there
        // would be worse than none, because nothing downstream would flag it.
        assertThat(result.status()).isEqualTo(ValuationStatus.UNSUPPORTED_AREA);
        assertThat(result.estimatedValue()).isNull();
        verify(accountRepository, never()).save(any());
    }

    @Test
    void estimate_providerOutage_keepsThePreviousValue() {
        Account account = house();
        when(accessResolver.requireOwner(10L, 1L)).thenReturn(account);
        when(metadataRepository.findByAccountId(10L))
            .thenReturn(Optional.of(metadata(account, "HOUSE", "33063", ValuationMode.ESTIMATED)));
        when(provider.supports(any())).thenReturn(true);
        when(provider.estimate(any())).thenReturn(Optional.empty());

        PropertyValuationResponse result = service.estimate(10L, 1L);

        assertThat(result.status()).isEqualTo(ValuationStatus.NO_COMPARABLE_DATA);
        assertThat(account.getCurrentBalance()).isEqualByComparingTo("300000");
        verify(accountRepository, never()).save(any());
        verify(valuationRepository, never()).save(any());
    }

    @Test
    void estimate_missingLivingArea_refusesEarly() {
        Account account = house();
        RealEstateMetadata m = metadata(account, "HOUSE", "33063", ValuationMode.ESTIMATED);
        m.setSurfaceArea(null);
        when(accessResolver.requireOwner(10L, 1L)).thenReturn(account);
        when(metadataRepository.findByAccountId(10L)).thenReturn(Optional.of(m));

        // Nothing to multiply a price per m² by.
        assertThat(service.estimate(10L, 1L).status()).isEqualTo(ValuationStatus.INCOMPLETE_DATA);
        verifyNoInteractions(provider);
    }

    @Test
    void estimate_landOrBuilding_isNotEstimable() {
        Account account = house();
        when(accessResolver.requireOwner(10L, 1L)).thenReturn(account);
        when(metadataRepository.findByAccountId(10L))
            .thenReturn(Optional.of(metadata(account, "LAND", "33063", ValuationMode.ESTIMATED)));

        assertThat(service.estimate(10L, 1L).status()).isEqualTo(ValuationStatus.NOT_ESTIMABLE);
        verifyNoInteractions(provider);
    }

    @Test
    void estimate_geocodesWhenTheInseeCodeIsMissing() {
        Account account = house();
        RealEstateMetadata m = metadata(account, "HOUSE", null, ValuationMode.ESTIMATED);
        m.setAddress("1 rue de la Paix");
        m.setCity("Paris");
        when(accessResolver.requireOwner(10L, 1L)).thenReturn(account);
        when(metadataRepository.findByAccountId(10L)).thenReturn(Optional.of(m));
        when(geocoder.geocode(any())).thenReturn(Optional.of(new GeocodingPort.GeocodeResult(
            "1 Rue de la Paix 75002 Paris", new BigDecimal("0.96"),
            new BigDecimal("48.868546"), new BigDecimal("2.33031"),
            "75102", "75002", "Paris", "ban-id")));
        stubSuccessfulProvider();
        when(priceIndex.reindexRatio(any(), any(), any())).thenReturn(Optional.empty());

        service.estimate(10L, 1L);

        assertThat(m.getInseeCode()).isEqualTo("75102");
        assertThat(m.getLatitude()).isEqualByComparingTo("48.868546");
        verify(metadataRepository).save(m);
    }

    @Test
    void estimate_weakGeocodeMatch_refusesRatherThanValueTheWrongCommune() {
        Account account = house();
        RealEstateMetadata m = metadata(account, "HOUSE", null, ValuationMode.ESTIMATED);
        m.setAddress("rue qui n'existe pas");
        when(accessResolver.requireOwner(10L, 1L)).thenReturn(account);
        when(metadataRepository.findByAccountId(10L)).thenReturn(Optional.of(m));
        when(geocoder.geocode(any())).thenReturn(Optional.of(new GeocodingPort.GeocodeResult(
            "Quelque part", new BigDecimal("0.12"), null, null, "99999", null, null, null)));

        // A weak match usually means a typo. Valuing against the wrong commune is a silent
        // error the user has no way to spot.
        assertThat(service.estimate(10L, 1L).status()).isEqualTo(ValuationStatus.GEOCODING_FAILED);
        verifyNoInteractions(provider);
    }

    @Test
    void estimate_geocodingUnavailable_reportsIt() {
        Account account = house();
        RealEstateMetadata m = metadata(account, "HOUSE", null, ValuationMode.ESTIMATED);
        m.setAddress("1 rue de la Paix");
        when(accessResolver.requireOwner(10L, 1L)).thenReturn(account);
        when(metadataRepository.findByAccountId(10L)).thenReturn(Optional.of(m));
        when(geocoder.geocode(any())).thenReturn(Optional.empty());

        assertThat(service.estimate(10L, 1L).status()).isEqualTo(ValuationStatus.GEOCODING_FAILED);
    }

    @Test
    void estimate_reusesTodaysRowInsteadOfPilingUpHistory() {
        Account account = house();
        PropertyValuation existing = PropertyValuation.builder()
            .account(account).member(ALICE).valuedAt(LocalDate.now())
            .estimatedValue(new BigDecimal("380000")).provider("CEREMA_DV3F").build();

        when(accessResolver.requireOwner(10L, 1L)).thenReturn(account);
        when(metadataRepository.findByAccountId(10L))
            .thenReturn(Optional.of(metadata(account, "HOUSE", "33063", ValuationMode.ESTIMATED)));
        when(provider.supports(any())).thenReturn(true);
        when(provider.providerName()).thenReturn("CEREMA_DV3F");
        when(provider.estimate(any())).thenReturn(Optional.of(new ValuationResult(
            new BigDecimal("400000"), null, null, new BigDecimal("4000"), 500,
            ValuationConfidence.HIGH, (short) 2025, "communes")));
        when(valuationRepository.findByAccountIdAndValuedAt(eq(10L), any(LocalDate.class)))
            .thenReturn(Optional.of(existing));
        when(valuationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(priceIndex.reindexRatio(any(), any(), any())).thenReturn(Optional.empty());

        service.estimate(10L, 1L);

        // Hitting refresh repeatedly should correct today's figure, not litter the chart.
        assertThat(existing.getEstimatedValue()).isEqualByComparingTo("400000.00");
    }

    @Test
    void estimate_recordsHowTheNumberWasBuilt() {
        Account account = house();
        RealEstateMetadata m = metadata(account, "APARTMENT", "75102", ValuationMode.ESTIMATED);
        m.setFloorNumber((short) 4);
        m.setHasElevator(false);
        when(accessResolver.requireOwner(10L, 1L)).thenReturn(account);
        when(metadataRepository.findByAccountId(10L)).thenReturn(Optional.of(m));
        stubSuccessfulProvider();
        when(priceIndex.reindexRatio(any(), any(), any())).thenReturn(Optional.empty());

        PropertyValuationResponse result = service.estimate(10L, 1L);

        // The applied coefficients are shown to the user, so they must survive the round trip.
        assertThat(result.adjustments()).extracting(PropertyValuationResponse.AdjustmentDto::code)
            .contains("NO_ELEVATOR");
    }

    @Test
    void refreshAllForMember_skipsPropertiesThatFail() {
        Account ok = house();
        Account broken = Account.builder()
            .id(11L).name("Autre").type(AccountType.REAL_ESTATE).currency("EUR")
            .currentBalance(new BigDecimal("100000")).color("#a855f7").member(ALICE).build();

        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L))
            .thenReturn(List.of(broken, ok));
        when(metadataRepository.findByAccountId(11L))
            .thenThrow(new IllegalStateException("boom"));
        when(metadataRepository.findByAccountId(10L))
            .thenReturn(Optional.of(metadata(ok, "HOUSE", "33063", ValuationMode.ESTIMATED)));
        // No requireOwner stub: the scheduled path already iterates the member's own accounts,
        // so it values them directly rather than re-authorizing each one.
        stubSuccessfulProvider();
        when(priceIndex.reindexRatio(any(), any(), any())).thenReturn(Optional.empty());

        // One broken property must not stop the rest of the portfolio from being revalued.
        assertThat(service.refreshAllForMember(1L)).isEqualTo(1);
    }

    @Test
    void refreshAllForMember_ignoresNonProperties() {
        Account checking = Account.builder()
            .id(30L).name("Compte").type(AccountType.CHECKING).currency("EUR")
            .currentBalance(new BigDecimal("5000")).color("#0ea5e9").member(ALICE).build();
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(checking));

        assertThat(service.refreshAllForMember(1L)).isZero();
        verifyNoInteractions(provider);
    }

    @Test
    void estimate_whenDisabled_reportsProviderUnavailable() {
        PropertyValuationService disabled = new PropertyValuationService(
            List.of(provider), geocoder, priceIndex, new PropertyAdjustments(),
            metadataRepository, valuationRepository, accountRepository, accessResolver,
            new ObjectMapper(), new TransactionTemplate(new CountingTxManager()), false);

        Account account = house();
        when(accessResolver.requireOwner(10L, 1L)).thenReturn(account);
        when(metadataRepository.findByAccountId(10L))
            .thenReturn(Optional.of(metadata(account, "HOUSE", "33063", ValuationMode.ESTIMATED)));

        assertThat(disabled.estimate(10L, 1L).status()).isEqualTo(ValuationStatus.PROVIDER_UNAVAILABLE);
        verifyNoInteractions(provider);
    }

    @Test
    void estimate_withoutData_seedsTheValueFromTheCostBasisInsteadOfLeavingItAtZero() {
        Account account = house();
        account.setCurrentBalance(BigDecimal.ZERO);
        RealEstateMetadata m = metadata(account, "HOUSE", "29019", ValuationMode.ESTIMATED);
        m.setNotaryFees(new BigDecimal("10800"));

        when(accessResolver.requireOwner(10L, 1L)).thenReturn(account);
        when(metadataRepository.findByAccountId(10L)).thenReturn(Optional.of(m));
        when(provider.supports(any())).thenReturn(true);
        when(provider.estimate(any())).thenReturn(Optional.empty());

        PropertyValuationResponse result = service.estimate(10L, 1L);

        // Zero would render as a 100% loss against the purchase price -- read by anyone as
        // "your property is worthless" rather than "we have no figure yet".
        assertThat(result.status()).isEqualTo(ValuationStatus.NO_COMPARABLE_DATA);
        assertThat(account.getCurrentBalance()).isEqualByComparingTo("310800");
        verify(accountRepository).save(account);
    }

    @Test
    void estimate_withoutData_leavesAnExistingValueAlone() {
        Account account = house(); // already worth 300000
        when(accessResolver.requireOwner(10L, 1L)).thenReturn(account);
        when(metadataRepository.findByAccountId(10L))
            .thenReturn(Optional.of(metadata(account, "HOUSE", "29019", ValuationMode.ESTIMATED)));
        when(provider.supports(any())).thenReturn(true);
        when(provider.estimate(any())).thenReturn(Optional.empty());

        service.estimate(10L, 1L);

        // The floor only ever lifts a zero; a real valuation is never overwritten by it.
        assertThat(account.getCurrentBalance()).isEqualByComparingTo("300000");
        verify(accountRepository, never()).save(any());
    }

    @Test
    void estimate_providerTransportFailure_reportsUnavailableNotMissingData() {
        Account account = house();
        when(accessResolver.requireOwner(10L, 1L)).thenReturn(account);
        when(metadataRepository.findByAccountId(10L))
            .thenReturn(Optional.of(metadata(account, "HOUSE", "29019", ValuationMode.ESTIMATED)));
        when(provider.supports(any())).thenReturn(true);
        when(provider.estimate(any()))
            .thenThrow(new ValuationProviderException("boom", new RuntimeException()));

        PropertyValuationResponse result = service.estimate(10L, 1L);

        // "The source was unreachable" and "this commune has no sales" are different facts.
        // Collapsing them once sent a user hunting through their address for a bug that was
        // actually a 256 KB buffer limit.
        assertThat(result.status()).isEqualTo(ValuationStatus.PROVIDER_UNAVAILABLE);
    }

    @Test
    void estimate_bandBracketsTheEstimateOnceTheAdjustmentsApply() {
        // A top-floor flat with a lift and two garages: the multiplier pushes up and the
        // area-equivalents add on top. Re-indexing the raw q25/q75 alone used to leave the
        // whole band below the figure it was supposed to bracket.
        Account account = house();
        RealEstateMetadata flat = metadata(account, "APARTMENT", "33063", ValuationMode.ESTIMATED);
        flat.setFloorNumber((short) 6);
        flat.setFloorsTotal((short) 6);
        flat.setHasElevator(true);
        flat.setGarageCount((short) 2);
        flat.setEnergyClass("A");

        when(accessResolver.requireOwner(10L, 1L)).thenReturn(account);
        when(metadataRepository.findByAccountId(10L)).thenReturn(Optional.of(flat));
        stubSuccessfulProvider();
        when(priceIndex.reindexRatio(any(), any(), any())).thenReturn(Optional.empty());

        PropertyValuationResponse result = service.estimate(10L, 1L);

        assertThat(result.status()).isEqualTo(ValuationStatus.OK);
        assertThat(result.estimatedValue()).isGreaterThan(new BigDecimal("400000"));
        assertThat(result.estimatedValue())
            .isBetween(result.lowValue(), result.highValue());
    }

    @Test
    void estimate_bandStaysAroundTheEstimateThroughReindexing() {
        // Both the figure and the bounds are carried forward by the same ratio, so the
        // bracketing has to survive it.
        Account account = house();
        RealEstateMetadata flat = metadata(account, "APARTMENT", "33063", ValuationMode.ESTIMATED);
        flat.setGarageCount((short) 1);

        when(accessResolver.requireOwner(10L, 1L)).thenReturn(account);
        when(metadataRepository.findByAccountId(10L)).thenReturn(Optional.of(flat));
        stubSuccessfulProvider();
        when(priceIndex.reindexRatio(any(), any(), any()))
            .thenReturn(Optional.of(new BigDecimal("1.08")));

        PropertyValuationResponse result = service.estimate(10L, 1L);

        assertThat(result.estimatedValue())
            .isBetween(result.lowValue(), result.highValue());
    }

    @Test
    void refreshAllForMember_givesEachPropertyItsOwnTransaction() {
        // The per-property try/catch is not enough on its own: a DataAccessException marks the
        // surrounding transaction rollback-only, so a single @Transactional method would commit
        // nothing at the end and throw UnexpectedRollbackException, losing the properties that
        // had already succeeded. Only a real boundary per property makes the guard mean what it
        // says, which is what these counts pin.
        Account ok = house();
        Account broken = Account.builder()
            .id(11L).name("Autre").type(AccountType.REAL_ESTATE).currency("EUR")
            .currentBalance(new BigDecimal("100000")).color("#a855f7").member(ALICE).build();

        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L))
            .thenReturn(List.of(broken, ok));
        when(metadataRepository.findByAccountId(11L))
            .thenThrow(new DataAccessResourceFailureException("connection reset"));
        when(metadataRepository.findByAccountId(10L))
            .thenReturn(Optional.of(metadata(ok, "HOUSE", "33063", ValuationMode.ESTIMATED)));
        stubSuccessfulProvider();
        when(priceIndex.reindexRatio(any(), any(), any())).thenReturn(Optional.empty());

        assertThat(service.refreshAllForMember(1L)).isEqualTo(1);

        // One for the account list, one per property.
        assertThat(txManager.started).isEqualTo(3);
        // Only the failing property rolled back; the healthy one committed on its own.
        assertThat(txManager.rolledBack).isEqualTo(1);
        assertThat(txManager.committed).isEqualTo(2);
    }
}
