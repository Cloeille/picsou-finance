package com.picsou.service;

import com.picsou.model.AppSetting;
import com.picsou.repository.AppSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatchUpSchedulerTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private final SchedulerService jobs = mock(SchedulerService.class);
    private final AppSettingRepository settings = mock(AppSettingRepository.class);
    private final Map<String, String> stored = new HashMap<>();
    private MutableClock clock;

    @BeforeEach
    void storeSettingsInMemory() {
        when(settings.findByKey(anyString())).thenAnswer(call -> Optional.ofNullable(stored.get(call.<String>getArgument(0)))
            .map(value -> AppSetting.builder().key(call.getArgument(0)).value(value).build()));
        when(settings.save(any(AppSetting.class))).thenAnswer(call -> {
            AppSetting setting = call.getArgument(0);
            stored.put(setting.getKey(), setting.getValue());
            return setting;
        });
    }

    private CatchUpScheduler startAt(String localDateTime) {
        clock = new MutableClock(ZonedDateTime.of(java.time.LocalDateTime.parse(localDateTime), PARIS).toInstant());
        return new CatchUpScheduler(jobs, settings, clock);
    }

    private void lastRun(String job, String localDateTime) {
        stored.put(CatchUpScheduler.KEY_PREFIX + job,
            ZonedDateTime.of(java.time.LocalDateTime.parse(localDateTime), PARIS).toInstant().toString());
    }

    @Test
    void aSlotMissedWhileOffRunsOnceAtTheNextTickThenWaitsForTomorrow() {
        lastRun("daily-bank-sync", "2026-11-14T08:00:30");
        lastRun("daily-snapshots", "2026-11-14T08:05:30");
        CatchUpScheduler scheduler = startAt("2026-11-15T10:12:00");

        scheduler.tick();
        clock.advance(Duration.ofMinutes(1));
        scheduler.tick();

        var order = inOrder(jobs);
        order.verify(jobs).dailyBankSync();
        order.verify(jobs).dailySnapshots();
        verify(jobs, times(1)).dailyBankSync();
        verify(jobs, times(1)).dailySnapshots();

        clock.advance(Duration.ofHours(21).plusMinutes(48));
        scheduler.tick();
        verify(jobs, times(2)).dailyBankSync();
    }

    @Test
    void severalMissedDaysStillGiveOneRun() {
        lastRun("daily-bank-sync", "2026-11-09T08:00:30");
        CatchUpScheduler scheduler = startAt("2026-11-15T19:00:00");

        scheduler.tick();
        scheduler.tick();

        verify(jobs, times(1)).dailyBankSync();
    }

    @Test
    void aRestartRightAfterARunDoesNotRunAgain() {
        lastRun("daily-bank-sync", "2026-11-15T08:00:30");
        CatchUpScheduler scheduler = startAt("2026-11-15T08:20:00");

        scheduler.tick();

        verify(jobs, never()).dailyBankSync();
    }

    @Test
    void aJobNeverSeenBeforeWaitsForItsNextSlot() {
        CatchUpScheduler scheduler = startAt("2026-11-15T10:00:00");

        scheduler.tick();

        verify(jobs, never()).dailyBankSync();
        verify(jobs, never()).monthlyPropertyValuation();
        assertThat(stored).containsKey(CatchUpScheduler.KEY_PREFIX + "daily-bank-sync");

        clock.advance(Duration.ofHours(22));
        scheduler.tick();
        verify(jobs, times(1)).dailyBankSync();
    }

    @Test
    void aWeeklyJobMissedOnSundayRunsOnMonday() {
        lastRun("weekly-security-profiles", "2026-11-08T03:45:30");
        lastRun("weekly-dividend-history", "2026-11-08T04:15:30");
        CatchUpScheduler scheduler = startAt("2026-11-16T09:00:00");

        scheduler.tick();

        verify(jobs).refreshSecurityProfiles();
        verify(jobs).refreshDividendHistory();
    }

    @Test
    void aFailingJobIsRecordedAndWaitsForItsNextSlot() {
        lastRun("daily-bank-sync", "2026-11-14T08:00:30");
        doThrow(new IllegalStateException("bank down")).when(jobs).dailyBankSync();
        CatchUpScheduler scheduler = startAt("2026-11-15T10:00:00");

        scheduler.tick();
        clock.advance(Duration.ofMinutes(1));
        scheduler.tick();

        verify(jobs, times(1)).dailyBankSync();
    }

    @Test
    void theLastRunSurvivesARestart() {
        lastRun("daily-bank-sync", "2026-11-14T08:00:30");
        startAt("2026-11-15T10:00:00").tick();

        new CatchUpScheduler(jobs, settings, clock).tick();

        verify(jobs, times(1)).dailyBankSync();
    }

    @Test
    void runNowRecordsTheRunSoTheTickDoesNotRepeatIt() {
        lastRun("daily-bank-sync", "2026-11-14T08:00:30");
        CatchUpScheduler scheduler = startAt("2026-11-15T10:00:00");

        assertThat(scheduler.runNow(CatchUpScheduler.DAILY_BANK_SYNC)).isTrue();
        scheduler.tick();

        verify(jobs, times(1)).dailyBankSync();
        assertThatThrownBy(() -> scheduler.runNow("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnreadableLastRunStartsFromNow() {
        stored.put(CatchUpScheduler.KEY_PREFIX + "daily-bank-sync", "yesterday");
        CatchUpScheduler scheduler = startAt("2026-11-15T10:00:00");

        scheduler.tick();

        verify(jobs, never()).dailyBankSync();
        assertThat(Instant.parse(stored.get(CatchUpScheduler.KEY_PREFIX + "daily-bank-sync"))).isEqualTo(clock.instant());
    }

    @Test
    void aFailedSaveDoesNotRunTheJobAgainAtEveryTick() {
        lastRun("daily-bank-sync", "2026-11-14T08:00:30");
        CatchUpScheduler scheduler = startAt("2026-11-15T10:00:00");
        when(settings.save(any(AppSetting.class))).thenThrow(new IllegalStateException("database down"));

        scheduler.tick();
        clock.advance(Duration.ofMinutes(1));
        scheduler.tick();

        verify(jobs, times(1)).dailyBankSync();
        assertThat(scheduler.runNow(CatchUpScheduler.DAILY_BANK_SYNC)).isTrue();
    }

    @Test
    void aJobAlreadyRunningIsSkippedNotRunTwice() {
        lastRun("daily-bank-sync", "2026-11-14T08:00:30");
        CatchUpScheduler scheduler = startAt("2026-11-15T10:00:00");
        boolean[] nested = new boolean[1];
        doAnswer(call -> {
            nested[0] = scheduler.runNow(CatchUpScheduler.DAILY_BANK_SYNC);
            return null;
        }).when(jobs).dailyBankSync();

        scheduler.tick();

        assertThat(nested[0]).isFalse();
        verify(jobs, times(1)).dailyBankSync();
    }

    @Test
    void theSundayJobMissedOnTheNightClocksGoBackRunsOnce() {
        lastRun("weekly-security-profiles", "2026-10-18T03:45:30");
        CatchUpScheduler scheduler = startAt("2026-10-25T02:30:00");

        scheduler.tick();
        verify(jobs, never()).refreshSecurityProfiles();

        clock.advance(Duration.ofHours(3));
        scheduler.tick();
        clock.advance(Duration.ofMinutes(1));
        scheduler.tick();
        verify(jobs, times(1)).refreshSecurityProfiles();
    }

    @Test
    void aLastRunInTheFutureIsResetToNowInsteadOfBlockingTheJob() {
        lastRun("daily-bank-sync", "2026-12-25T08:00:30");
        CatchUpScheduler scheduler = startAt("2026-11-15T10:00:00");

        scheduler.tick();
        verify(jobs, never()).dailyBankSync();

        clock.advance(Duration.ofHours(22));
        scheduler.tick();
        verify(jobs, times(1)).dailyBankSync();
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return PARIS;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }
}
