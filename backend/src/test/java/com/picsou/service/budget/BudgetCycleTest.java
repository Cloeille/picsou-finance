package com.picsou.service.budget;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetCycleTest {

    @Test
    void calendarMonthWhenStartDayIsOne() {
        BudgetCycle.CycleRange c = BudgetCycle.cycleFor(LocalDate.of(2026, 6, 15), 1);
        assertThat(c.start()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(c.end()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void paydayCycleMidMonth() {
        // payday on the 27th, a date after the 27th -> cycle starts this month
        BudgetCycle.CycleRange c = BudgetCycle.cycleFor(LocalDate.of(2026, 6, 28), 27);
        assertThat(c.start()).isEqualTo(LocalDate.of(2026, 6, 27));
        assertThat(c.end()).isEqualTo(LocalDate.of(2026, 7, 26));
    }

    @Test
    void paydayCycleBeforeStartDayRollsBack() {
        // payday on the 27th, a date before the 27th -> cycle started last month
        BudgetCycle.CycleRange c = BudgetCycle.cycleFor(LocalDate.of(2026, 6, 15), 27);
        assertThat(c.start()).isEqualTo(LocalDate.of(2026, 5, 27));
        assertThat(c.end()).isEqualTo(LocalDate.of(2026, 6, 26));
    }

    @Test
    void cycleStartDayExactlyOnDate() {
        BudgetCycle.CycleRange c = BudgetCycle.cycleFor(LocalDate.of(2026, 6, 27), 27);
        assertThat(c.start()).isEqualTo(LocalDate.of(2026, 6, 27));
    }

    @Test
    void rollBackOverYearBoundary() {
        BudgetCycle.CycleRange c = BudgetCycle.cycleFor(LocalDate.of(2026, 1, 10), 27);
        assertThat(c.start()).isEqualTo(LocalDate.of(2025, 12, 27));
        assertThat(c.end()).isEqualTo(LocalDate.of(2026, 1, 26));
    }

    @Test
    void containsIsHalfOpenInclusive() {
        BudgetCycle.CycleRange c = BudgetCycle.cycleFor(LocalDate.of(2026, 6, 15), 1);
        assertThat(c.contains(LocalDate.of(2026, 6, 1))).isTrue();
        assertThat(c.contains(LocalDate.of(2026, 6, 30))).isTrue();
        assertThat(c.contains(LocalDate.of(2026, 7, 1))).isFalse();
    }

    @Test
    void rejectsOutOfRangeStartDay() {
        assertThatThrownBy(() -> BudgetCycle.cycleFor(LocalDate.of(2026, 6, 15), 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BudgetCycle.cycleFor(LocalDate.of(2026, 6, 15), 29))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cyclesBetweenEnumeratesInclusive() {
        var cycles = BudgetCycle.cyclesBetween(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 15), 1);
        assertThat(cycles).hasSize(3);
        assertThat(cycles.get(0).start()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(cycles.get(2).start()).isEqualTo(LocalDate.of(2026, 3, 1));
    }
}
