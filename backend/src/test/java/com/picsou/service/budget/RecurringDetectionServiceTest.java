package com.picsou.service.budget;

import com.picsou.model.RecurringCadence;
import com.picsou.model.RecurringSeries;
import com.picsou.model.RecurringStatus;
import com.picsou.model.Transaction;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.RecurringSeriesRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the v2 recurring detector. Pure {@code analyse}/{@code groupByIdentity}/
 * {@code isPriceChange} are exercised directly; the {@code detect} cases stub the repositories to
 * verify the upsert decisions (auto-confirm, variable-stays-suggested, ignored-not-resurrected,
 * price-change) and the transaction back-linking.
 */
@ExtendWith(MockitoExtension.class)
class RecurringDetectionServiceTest {

    @Mock TransactionRepository transactionRepository;
    @Mock RecurringSeriesRepository seriesRepository;
    @Mock FamilyMemberRepository familyMemberRepository;

    @InjectMocks RecurringDetectionService service;

    private static final Long MEMBER_ID = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 1);

    private static Transaction tx(LocalDate date, String amount, String counterparty) {
        return Transaction.builder()
            .id(date.toEpochDay()) // stable, unique-enough id for ordering
            .date(date)
            .amount(new BigDecimal(amount))
            .counterparty(counterparty)
            .description(counterparty)
            .build();
    }

    private static Transaction txLabeled(LocalDate date, String amount, String counterparty, String label) {
        Transaction t = tx(date, amount, counterparty);
        t.setMerchantLabel(label);
        return t;
    }

    /** Make {@code save} echo its argument, assigning an id to a brand-new series (mimics IDENTITY). */
    private void stubSaveAssigningId(long newId) {
        when(seriesRepository.save(any())).thenAnswer(inv -> {
            RecurringSeries s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(newId);
            }
            return s;
        });
    }

    // ─── Pure analysis ────────────────────────────────────────────────────────

    @Test
    void analyse_detectsMonthlyFixedSubscription() {
        List<Transaction> txs = List.of(
            tx(LocalDate.of(2026, 3, 5), "-12.99", "Netflix"),
            tx(LocalDate.of(2026, 4, 4), "-12.99", "Netflix"),
            tx(LocalDate.of(2026, 5, 6), "-12.99", "Netflix")
        );

        RecurringDetectionService.Candidate c = RecurringDetectionService.analyse(txs);

        assertThat(c).isNotNull();
        assertThat(c.cadence()).isEqualTo(RecurringCadence.MONTHLY);
        assertThat(c.expectedAmount()).isEqualByComparingTo("-12.99");
        assertThat(c.amountMin()).isEqualByComparingTo("-12.99");
        assertThat(c.amountMax()).isEqualByComparingTo("-12.99");
        assertThat(c.isVariable()).isFalse();
        assertThat(c.occurrences()).isEqualTo(3);
        assertThat(c.lastSeen()).isEqualTo(LocalDate.of(2026, 5, 6));
        assertThat(c.nextDue()).isEqualTo(LocalDate.of(2026, 6, 6));
        // gaps [30,32] + zero amount drift + 3 occurrences → 0.832, clears the 0.80 auto-confirm bar.
        assertThat(c.confidence()).isEqualByComparingTo("0.832");
        assertThat(c.autoConfirm()).isTrue();
    }

    @Test
    void analyse_toleratesSmallAmountDrift() {
        // A modest price bump within 15% of the median still counts as the same fixed series.
        List<Transaction> txs = List.of(
            tx(LocalDate.of(2026, 3, 1), "-10.00", "Spotify"),
            tx(LocalDate.of(2026, 4, 1), "-10.00", "Spotify"),
            tx(LocalDate.of(2026, 5, 1), "-11.00", "Spotify")
        );

        RecurringDetectionService.Candidate c = RecurringDetectionService.analyse(txs);

        assertThat(c).isNotNull();
        assertThat(c.isVariable()).isFalse();
        // 10% amount drift drags stability down → below the 0.80 bar, so it stays a quiet suggestion.
        assertThat(c.confidence()).isLessThan(new BigDecimal("0.80"));
        assertThat(c.autoConfirm()).isFalse();
    }

    @Test
    void analyse_classifiesModerateVarianceAsVariable() {
        // A utility-style bill that swings each month: a real series, but never silently auto-confirmed.
        List<Transaction> txs = List.of(
            tx(LocalDate.of(2026, 3, 10), "-40.00", "EDF"),
            tx(LocalDate.of(2026, 4, 10), "-70.00", "EDF"),
            tx(LocalDate.of(2026, 5, 10), "-55.00", "EDF")
        );

        RecurringDetectionService.Candidate c = RecurringDetectionService.analyse(txs);

        assertThat(c).isNotNull();
        assertThat(c.cadence()).isEqualTo(RecurringCadence.MONTHLY);
        assertThat(c.isVariable()).isTrue();
        assertThat(c.autoConfirm()).isFalse();
        assertThat(c.expectedAmount()).isEqualByComparingTo("-55.00"); // median of the recent window
        assertThat(c.amountMin()).isEqualByComparingTo("-70.00");
        assertThat(c.amountMax()).isEqualByComparingTo("-40.00");
    }

    @Test
    void analyse_rejectsTooFewOccurrences() {
        List<Transaction> txs = List.of(
            tx(LocalDate.of(2026, 4, 1), "-12.99", "Netflix"),
            tx(LocalDate.of(2026, 5, 1), "-12.99", "Netflix")
        );

        assertThat(RecurringDetectionService.analyse(txs)).isNull();
    }

    @Test
    void analyse_rejectsIrregularIntervals() {
        // 30d then 90d gap — not a stable cadence.
        List<Transaction> txs = List.of(
            tx(LocalDate.of(2026, 1, 1), "-20.00", "Irregular"),
            tx(LocalDate.of(2026, 1, 31), "-20.00", "Irregular"),
            tx(LocalDate.of(2026, 5, 1), "-20.00", "Irregular")
        );

        assertThat(RecurringDetectionService.analyse(txs)).isNull();
    }

    @Test
    void analyse_rejectsWildlyUnstableAmounts() {
        // Same monthly rhythm but wildly different amounts — looks like groceries, not a sub.
        List<Transaction> txs = List.of(
            tx(LocalDate.of(2026, 3, 3), "-15.00", "Carrefour"),
            tx(LocalDate.of(2026, 4, 3), "-80.00", "Carrefour"),
            tx(LocalDate.of(2026, 5, 3), "-42.00", "Carrefour")
        );

        assertThat(RecurringDetectionService.analyse(txs)).isNull();
    }

    @Test
    void normalise_collapsesCaseAndWhitespace() {
        assertThat(RecurringDetectionService.normalise("  NETFLIX   EU "))
            .isEqualTo("netflix eu");
    }

    @Test
    void groupByIdentity_keysOnMerchantLabelNotRawCounterparty() {
        // Same merchant, noisy raw counterparty each month — must collapse into ONE group via the label.
        List<Transaction> txs = List.of(
            txLabeled(LocalDate.of(2026, 3, 5), "-12.99", "PAYPAL *NETFLIX 4357", "Netflix"),
            txLabeled(LocalDate.of(2026, 4, 4), "-12.99", "NETFLIX EU 889", "Netflix"),
            txLabeled(LocalDate.of(2026, 5, 6), "-12.99", "NETFLIX.COM", "Netflix")
        );

        Map<String, List<Transaction>> groups = RecurringDetectionService.groupByIdentity(txs);

        assertThat(groups).hasSize(1);
        assertThat(groups.values().iterator().next()).hasSize(3);
    }

    @Test
    void isPriceChange_detectsStepAboveThreshold() {
        assertThat(RecurringDetectionService.isPriceChange(new BigDecimal("-9.99"), new BigDecimal("-11.99")))
            .isTrue();
    }

    @Test
    void isPriceChange_ignoresSubThresholdDriftAndNulls() {
        assertThat(RecurringDetectionService.isPriceChange(new BigDecimal("-10.00"), new BigDecimal("-10.20")))
            .isFalse(); // 2% < 5%
        assertThat(RecurringDetectionService.isPriceChange(null, new BigDecimal("-10.00"))).isFalse();
        assertThat(RecurringDetectionService.isPriceChange(BigDecimal.ZERO, new BigDecimal("-10.00"))).isFalse();
    }

    // ─── Full detect() with persistence ─────────────────────────────────────────

    @Test
    void detect_autoConfirmsHighConfidenceFixedSeries() {
        List<Transaction> txs = List.of(
            tx(LocalDate.of(2026, 3, 5), "-12.99", "Netflix"),
            tx(LocalDate.of(2026, 4, 4), "-12.99", "Netflix"),
            tx(LocalDate.of(2026, 5, 6), "-12.99", "Netflix")
        );
        when(transactionRepository.findByMemberIdAndDateBetween(eq(MEMBER_ID), any(), any()))
            .thenReturn(txs);
        when(seriesRepository.findByMemberIdAndLabelIgnoreCase(MEMBER_ID, "Netflix"))
            .thenReturn(Optional.empty());
        when(familyMemberRepository.getReferenceById(anyLong())).thenReturn(null);
        stubSaveAssigningId(99L);

        int upserted = service.detect(MEMBER_ID, TODAY);

        assertThat(upserted).isEqualTo(1);
        ArgumentCaptor<RecurringSeries> saved = ArgumentCaptor.forClass(RecurringSeries.class);
        verify(seriesRepository).save(saved.capture());
        RecurringSeries series = saved.getValue();
        assertThat(series.getStatus()).isEqualTo(RecurringStatus.CONFIRMED); // promoted silently
        assertThat(series.isAutoConfirmed()).isTrue();
        assertThat(series.isVariable()).isFalse();
        assertThat(series.getConfidence()).isEqualByComparingTo("0.832");
        assertThat(series.getLabel()).isEqualTo("Netflix");
        assertThat(series.getCadence()).isEqualTo(RecurringCadence.MONTHLY);
        assertThat(series.getNextDueDate()).isEqualTo(LocalDate.of(2026, 6, 6));

        // back-link: every transaction in the group is stamped with the new series id.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Transaction>> linked = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(linked.capture());
        assertThat(linked.getValue()).hasSize(3);
        assertThat(linked.getValue()).allMatch(t -> Long.valueOf(99L).equals(t.getRecurringSeriesId()));
    }

    @Test
    void detect_keepsVariableSeriesAsSuggested() {
        List<Transaction> txs = List.of(
            tx(LocalDate.of(2026, 3, 10), "-40.00", "EDF"),
            tx(LocalDate.of(2026, 4, 10), "-70.00", "EDF"),
            tx(LocalDate.of(2026, 5, 10), "-55.00", "EDF")
        );
        when(transactionRepository.findByMemberIdAndDateBetween(eq(MEMBER_ID), any(), any()))
            .thenReturn(txs);
        when(seriesRepository.findByMemberIdAndLabelIgnoreCase(MEMBER_ID, "EDF"))
            .thenReturn(Optional.empty());
        when(familyMemberRepository.getReferenceById(anyLong())).thenReturn(null);
        stubSaveAssigningId(50L);

        service.detect(MEMBER_ID, TODAY);

        ArgumentCaptor<RecurringSeries> saved = ArgumentCaptor.forClass(RecurringSeries.class);
        verify(seriesRepository).save(saved.capture());
        RecurringSeries series = saved.getValue();
        assertThat(series.isVariable()).isTrue();
        assertThat(series.getStatus()).isEqualTo(RecurringStatus.SUGGESTED); // never silently confirmed
        assertThat(series.isAutoConfirmed()).isFalse();
        assertThat(series.getExpectedAmount()).isEqualByComparingTo("-55.00");
        assertThat(series.getAmountMin()).isEqualByComparingTo("-70.00");
        assertThat(series.getAmountMax()).isEqualByComparingTo("-40.00");
    }

    @Test
    void detect_doesNotResurrectIgnoredSeries() {
        List<Transaction> txs = List.of(
            tx(LocalDate.of(2026, 3, 5), "-12.99", "Netflix"),
            tx(LocalDate.of(2026, 4, 4), "-12.99", "Netflix"),
            tx(LocalDate.of(2026, 5, 6), "-12.99", "Netflix")
        );
        RecurringSeries ignored = RecurringSeries.builder()
            .id(1L).label("Netflix").status(RecurringStatus.IGNORED).build();
        when(transactionRepository.findByMemberIdAndDateBetween(eq(MEMBER_ID), any(), any()))
            .thenReturn(txs);
        when(seriesRepository.findByMemberIdAndLabelIgnoreCase(MEMBER_ID, "Netflix"))
            .thenReturn(Optional.of(ignored));

        int upserted = service.detect(MEMBER_ID, TODAY);

        assertThat(upserted).isZero();
        verify(seriesRepository, never()).save(any());
        verify(transactionRepository, never()).saveAll(any());
    }

    @Test
    void detect_refreshesConfirmedSeriesInPlace() {
        List<Transaction> txs = List.of(
            tx(LocalDate.of(2026, 3, 5), "-12.99", "Netflix"),
            tx(LocalDate.of(2026, 4, 4), "-12.99", "Netflix"),
            tx(LocalDate.of(2026, 5, 6), "-12.99", "Netflix")
        );
        // A user-confirmed series whose stored amount lags the (sub-threshold) refreshed amount.
        RecurringSeries confirmed = RecurringSeries.builder()
            .id(1L).counterparty("Netflix").label("Netflix")
            .expectedAmount(new BigDecimal("-12.50"))
            .cadence(RecurringCadence.MONTHLY)
            .status(RecurringStatus.CONFIRMED).build();
        when(transactionRepository.findByMemberIdAndDateBetween(eq(MEMBER_ID), any(), any()))
            .thenReturn(txs);
        when(seriesRepository.findByMemberIdAndLabelIgnoreCase(MEMBER_ID, "Netflix"))
            .thenReturn(Optional.of(confirmed));
        when(seriesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int upserted = service.detect(MEMBER_ID, TODAY);

        assertThat(upserted).isEqualTo(1);
        assertThat(confirmed.getStatus()).isEqualTo(RecurringStatus.CONFIRMED); // user decision untouched
        assertThat(confirmed.isAutoConfirmed()).isFalse();                      // not a silent confirm
        assertThat(confirmed.getExpectedAmount()).isEqualByComparingTo("-12.99"); // refreshed
        assertThat(confirmed.getPreviousAmount()).isNull();                     // 3.9% < 5% → no price step
        assertThat(confirmed.getPriceChangedAt()).isNull();
        verify(seriesRepository).save(confirmed);
    }

    @Test
    void detect_flagsPriceChangeOnExistingSeries() {
        List<Transaction> txs = List.of(
            tx(LocalDate.of(2026, 3, 5), "-11.99", "Netflix"),
            tx(LocalDate.of(2026, 4, 4), "-11.99", "Netflix"),
            tx(LocalDate.of(2026, 5, 6), "-11.99", "Netflix")
        );
        RecurringSeries existing = RecurringSeries.builder()
            .id(1L).counterparty("Netflix").label("Netflix")
            .expectedAmount(new BigDecimal("-9.99"))
            .cadence(RecurringCadence.MONTHLY)
            .status(RecurringStatus.CONFIRMED).build();
        when(transactionRepository.findByMemberIdAndDateBetween(eq(MEMBER_ID), any(), any()))
            .thenReturn(txs);
        when(seriesRepository.findByMemberIdAndLabelIgnoreCase(MEMBER_ID, "Netflix"))
            .thenReturn(Optional.of(existing));
        when(seriesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.detect(MEMBER_ID, TODAY);

        assertThat(existing.getStatus()).isEqualTo(RecurringStatus.CONFIRMED);      // user decision untouched
        assertThat(existing.getExpectedAmount()).isEqualByComparingTo("-11.99");    // stepped to the new level
        assertThat(existing.getPreviousAmount()).isEqualByComparingTo("-9.99");     // old level remembered
        assertThat(existing.getPriceChangedAt()).isEqualTo(LocalDate.of(2026, 5, 6)); // = last seen
        verify(seriesRepository).save(existing);
    }
}
