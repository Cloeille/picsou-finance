package com.picsou.service.budget;

import com.picsou.dto.RecurringActivityResponse;
import com.picsou.dto.RecurringActivityType;
import com.picsou.dto.RecurringOccurrenceResponse;
import com.picsou.dto.RecurringRuntimeStatus;
import com.picsou.dto.RecurringSeriesResponse;
import com.picsou.model.RecurringCadence;
import com.picsou.model.RecurringSeries;
import com.picsou.model.RecurringStatus;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.RecurringSeriesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.picsou.dto.RecurringSeriesResponse.STALE_MISSED_PERIODS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for the lifecycle surface over recurring series: the computed runtime status on the
 * list, the derived "what changed" activity feed, and the context-aware undo. Detection itself is
 * covered by {@link RecurringDetectionServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class RecurringSeriesServiceTest {

    @Mock RecurringSeriesRepository seriesRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountRepository accountRepository;

    @InjectMocks RecurringSeriesService service;

    @BeforeEach
    void setUp() {
        // Neutralize the AMEX credit-card projection: no card accounts in these tests.
        lenient().when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
    }

    private static final Long MEMBER_ID = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 9);

    @org.junit.jupiter.api.BeforeEach
    void noCardPaymentsByDefault() {
        org.mockito.Mockito.lenient().when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER_ID)).thenReturn(List.of());
    }

    private static RecurringSeries.RecurringSeriesBuilder series() {
        return RecurringSeries.builder()
            .id(1L)
            .label("Netflix")
            .expectedAmount(new BigDecimal("-12.99"))
            .cadence(RecurringCadence.MONTHLY)
            .status(RecurringStatus.CONFIRMED);
    }

    private void echoSave() {
        when(seriesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ─── Runtime status on the list ─────────────────────────────────────────────

    @Test
    void findAll_computesRuntimeStatusRelativeToToday() {
        RecurringSeries late = series().id(1L).label("Late").nextDueDate(TODAY.minusDays(1)).build();
        RecurringSeries dueSoon = series().id(2L).label("Soon").nextDueDate(TODAY.plusDays(3)).build();
        RecurringSeries scheduled = series().id(3L).label("Later").nextDueDate(TODAY.plusDays(30)).build();
        when(seriesRepository.findAllByMemberIdOrderByNextDueDateAsc(MEMBER_ID))
            .thenReturn(List.of(late, dueSoon, scheduled));

        List<RecurringSeriesResponse> result = service.findAll(MEMBER_ID, null, TODAY);

        assertThat(result).extracting(RecurringSeriesResponse::runtimeStatus)
            .containsExactly(
                RecurringRuntimeStatus.LATE,
                RecurringRuntimeStatus.DUE_SOON,
                RecurringRuntimeStatus.SCHEDULED);
    }

    // ─── Activity feed ──────────────────────────────────────────────────────────

    @Test
    void activity_surfacesRecentAutoConfirmAndPriceChangeNewestFirst() {
        RecurringSeries autoConfirmed = series().id(1L).label("Spotify")
            .autoConfirmed(true).status(RecurringStatus.CONFIRMED)
            .lastSeenDate(TODAY.minusDays(20)).build();
        RecurringSeries repriced = series().id(2L).label("Netflix")
            .expectedAmount(new BigDecimal("-13.99"))
            .previousAmount(new BigDecimal("-12.99"))
            .priceChangedAt(TODAY.minusDays(2)).build();
        when(seriesRepository.findAllByMemberIdOrderByNextDueDateAsc(MEMBER_ID))
            .thenReturn(List.of(autoConfirmed, repriced));

        List<RecurringActivityResponse> feed = service.activity(MEMBER_ID, TODAY);

        assertThat(feed).hasSize(2);
        // Newest first: the 2-day-old price change precedes the 20-day-old auto-confirm.
        assertThat(feed.get(0).type()).isEqualTo(RecurringActivityType.PRICE_CHANGE);
        assertThat(feed.get(0).previousAmount()).isEqualByComparingTo("-12.99");
        assertThat(feed.get(0).expectedAmount()).isEqualByComparingTo("-13.99");
        assertThat(feed.get(1).type()).isEqualTo(RecurringActivityType.AUTO_CONFIRMED);
        assertThat(feed.get(1).previousAmount()).isNull();
    }

    @Test
    void activity_excludesStaleAndUserConfirmedSeries() {
        RecurringSeries stale = series().id(1L).label("Old")
            .autoConfirmed(true).lastSeenDate(TODAY.minusDays(90)).build(); // beyond the 60d window
        RecurringSeries userConfirmed = series().id(2L).label("Manual")
            .autoConfirmed(false).lastSeenDate(TODAY.minusDays(5)).build(); // confirmed by the user
        when(seriesRepository.findAllByMemberIdOrderByNextDueDateAsc(MEMBER_ID))
            .thenReturn(List.of(stale, userConfirmed));

        assertThat(service.activity(MEMBER_ID, TODAY)).isEmpty();
    }

    @Test
    void activity_emitsSingleEntryPreferringPriceChange() {
        // A series both silently auto-confirmed AND recently re-priced → one entry, the price change.
        RecurringSeries both = series().id(1L).label("Disney+")
            .autoConfirmed(true).status(RecurringStatus.CONFIRMED)
            .lastSeenDate(TODAY.minusDays(3))
            .previousAmount(new BigDecimal("-8.99")).priceChangedAt(TODAY.minusDays(3)).build();
        when(seriesRepository.findAllByMemberIdOrderByNextDueDateAsc(MEMBER_ID))
            .thenReturn(List.of(both));

        List<RecurringActivityResponse> feed = service.activity(MEMBER_ID, TODAY);

        assertThat(feed).hasSize(1);
        assertThat(feed.get(0).type()).isEqualTo(RecurringActivityType.PRICE_CHANGE);
    }

    // ─── Undo ───────────────────────────────────────────────────────────────────

    @Test
    void undo_acknowledgesPriceChangeKeepingNewAmount() {
        RecurringSeries repriced = series().id(1L)
            .expectedAmount(new BigDecimal("-13.99"))
            .previousAmount(new BigDecimal("-12.99"))
            .priceChangedAt(TODAY.minusDays(2))
            .status(RecurringStatus.CONFIRMED).build();
        when(seriesRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(repriced));
        echoSave();

        service.undo(1L, MEMBER_ID, TODAY);

        assertThat(repriced.getExpectedAmount()).isEqualByComparingTo("-13.99"); // new amount kept
        assertThat(repriced.getPreviousAmount()).isNull();                       // alert cleared
        assertThat(repriced.getPriceChangedAt()).isNull();
        assertThat(repriced.getStatus()).isEqualTo(RecurringStatus.CONFIRMED);   // still a subscription
    }

    @Test
    void undo_rejectsSilentAutoConfirmByIgnoringSeries() {
        RecurringSeries autoConfirmed = series().id(1L)
            .autoConfirmed(true).status(RecurringStatus.CONFIRMED)
            .lastSeenDate(TODAY.minusDays(4)).build();
        when(seriesRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(autoConfirmed));
        echoSave();

        service.undo(1L, MEMBER_ID, TODAY);

        assertThat(autoConfirmed.getStatus()).isEqualTo(RecurringStatus.IGNORED); // won't be re-confirmed
        assertThat(autoConfirmed.isAutoConfirmed()).isFalse();
    }

    // ─── Staleness (STALE runtime status) ──────────────────────────────────────

    /**
     * Provides boundary cases for staleness across three cadences.
     * Each row: [cadence, nextDueDate-that-is-exactly-STALE_MISSED_PERIODS-periods-before-today,
     *            nextDueDate-that-is-exactly-one-period-before-today (LATE, not STALE)].
     *
     * TODAY = 2026-06-09.
     *
     * MONTHLY boundary: nextDueDate must satisfy cadence.next(cadence.next(due)) < today.
     *   STALE:  2026-04-08 → next=2026-05-08 (<today), next=2026-06-08 (<today) ✓
     *   LATE:   2026-04-09 → next=2026-05-09 (<today), next=2026-06-09 (=today, not before) ✗
     *
     * WEEKLY boundary:
     *   STALE:  2026-05-25 → next=2026-06-01 (<today), next=2026-06-08 (<today) ✓
     *   LATE:   2026-05-26 → next=2026-06-02 (<today), next=2026-06-09 (=today, not before) ✗
     *
     * YEARLY boundary:
     *   STALE:  2024-06-08 → next=2025-06-08 (<today), next=2026-06-08 (<today) ✓
     *   LATE:   2024-06-09 → next=2025-06-09 (<today), next=2026-06-09 (=today, not before) ✗
     */
    static Stream<Arguments> staleBoundaries() {
        return Stream.of(
            Arguments.of(RecurringCadence.MONTHLY,  TODAY.of(2026, 4, 8),  TODAY.of(2026, 4, 9)),
            Arguments.of(RecurringCadence.WEEKLY,   TODAY.of(2026, 5, 25), TODAY.of(2026, 5, 26)),
            Arguments.of(RecurringCadence.YEARLY,   TODAY.of(2024, 6, 8),  TODAY.of(2024, 6, 9))
        );
    }

    @ParameterizedTest(name = "{0}: due={1} → STALE")
    @MethodSource("staleBoundaries")
    void findAll_staleSeriesGetStaleRuntimeStatus(RecurringCadence cadence, LocalDate staleDue, LocalDate lateDue) {
        RecurringSeries stale = series().id(1L).label("Stale").cadence(cadence).nextDueDate(staleDue).build();
        when(seriesRepository.findAllByMemberIdOrderByNextDueDateAsc(MEMBER_ID))
            .thenReturn(List.of(stale));

        List<RecurringSeriesResponse> result = service.findAll(MEMBER_ID, null, TODAY);

        assertThat(result).extracting(RecurringSeriesResponse::runtimeStatus)
            .containsExactly(RecurringRuntimeStatus.STALE);
    }

    @ParameterizedTest(name = "{0}: due={2} → LATE (not STALE)")
    @MethodSource("staleBoundaries")
    void findAll_oneperiodOverdueIsLateNotStale(RecurringCadence cadence, LocalDate staleDue, LocalDate lateDue) {
        RecurringSeries late = series().id(1L).label("Late").cadence(cadence).nextDueDate(lateDue).build();
        when(seriesRepository.findAllByMemberIdOrderByNextDueDateAsc(MEMBER_ID))
            .thenReturn(List.of(late));

        List<RecurringSeriesResponse> result = service.findAll(MEMBER_ID, null, TODAY);

        assertThat(result).extracting(RecurringSeriesResponse::runtimeStatus)
            .containsExactly(RecurringRuntimeStatus.LATE);
    }

    // ─── upcoming() staleness filter ────────────────────────────────────────────

    @Test
    void upcoming_excludesStaleSeriesFromProjection() {
        // A MONTHLY series whose nextDueDate is STALE_MISSED_PERIODS cadence periods in the past.
        LocalDate staleDue = TODAY.minusMonths(STALE_MISSED_PERIODS).minusDays(1); // safely stale
        RecurringSeries stale = series().id(10L).label("Stale").cadence(RecurringCadence.MONTHLY)
            .nextDueDate(staleDue).build();

        when(seriesRepository.findAllByMemberIdAndStatusOrderByNextDueDateAsc(MEMBER_ID, RecurringStatus.CONFIRMED))
            .thenReturn(List.of(stale));

        List<RecurringOccurrenceResponse> result = service.upcoming(MEMBER_ID, TODAY, 60);

        assertThat(result).isEmpty();
    }

    @Test
    void upcoming_includesCreditCardPaymentOnlyWhenAmountPositiveAndDue() {
        com.picsou.model.Account card = com.picsou.model.Account.builder()
            .id(99L).name("Amex").type(com.picsou.model.AccountType.CREDIT_CARD)
            .paymentDueAmount(new BigDecimal("24.50")).paymentDueDate(TODAY.plusDays(3))
            .rewardPoints(250L).build();
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER_ID)).thenReturn(List.of(card));

        List<RecurringOccurrenceResponse> result = service.upcoming(MEMBER_ID, TODAY, 30);

        assertThat(result).singleElement().satisfies(occurrence -> {
            assertThat(occurrence.expectedAmount()).isEqualByComparingTo("-24.50");
            assertThat(occurrence.dueDate()).isEqualTo(TODAY.plusDays(3));
            assertThat(occurrence.creditCardPayment()).isTrue();
            assertThat(occurrence.rewardPoints()).isEqualTo(250L);
        });
    }

    @Test
    void upcoming_omitsZeroAmountCreditCardPayment() {
        com.picsou.model.Account card = com.picsou.model.Account.builder()
            .id(99L).name("Amex").type(com.picsou.model.AccountType.CREDIT_CARD)
            .paymentDueAmount(BigDecimal.ZERO).paymentDueDate(TODAY.plusDays(3)).build();
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER_ID)).thenReturn(List.of(card));

        assertThat(service.upcoming(MEMBER_ID, TODAY, 30)).isEmpty();
    }

    @Test
    void upcoming_includesHealthySeriesAndProjectsForward() {
        LocalDate nextDue = TODAY.plusDays(1);
        RecurringSeries healthy = series().id(20L).label("Netflix").cadence(RecurringCadence.MONTHLY)
            .nextDueDate(nextDue).build();

        when(seriesRepository.findAllByMemberIdAndStatusOrderByNextDueDateAsc(MEMBER_ID, RecurringStatus.CONFIRMED))
            .thenReturn(List.of(healthy));

        List<RecurringOccurrenceResponse> result = service.upcoming(MEMBER_ID, TODAY, 60);

        // Should appear at least once (the immediate due date falls within the 60-day window).
        assertThat(result).isNotEmpty();
        assertThat(result.get(0).dueDate()).isEqualTo(nextDue);
    }

    @Test
    void upcoming_lateButNotStaleSeriesStillProjected() {
        // A MONTHLY series due yesterday (LATE but only 1 missed period) — must be projected.
        LocalDate lateDue = TODAY.minusDays(1);
        RecurringSeries late = series().id(30L).label("Spotify").cadence(RecurringCadence.MONTHLY)
            .nextDueDate(lateDue).build();

        when(seriesRepository.findAllByMemberIdAndStatusOrderByNextDueDateAsc(MEMBER_ID, RecurringStatus.CONFIRMED))
            .thenReturn(List.of(late));

        List<RecurringOccurrenceResponse> result = service.upcoming(MEMBER_ID, TODAY, 60);

        // Should project forward (past-due dates are rolled into the window).
        assertThat(result).isNotEmpty();
        // The rolled-forward occurrence must be in the future.
        assertThat(result.get(0).dueDate()).isAfterOrEqualTo(TODAY);
    }
}
