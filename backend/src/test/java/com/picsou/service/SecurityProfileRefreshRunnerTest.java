package com.picsou.service;

import com.picsou.repository.AccountHoldingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityProfileRefreshRunnerTest {

    @Mock AccountHoldingRepository holdingRepository;
    @Mock SecurityProfileService profileService;

    @Test
    void aSecondCallWhileOneRunsIsRefusedRatherThanQueued() throws Exception {
        when(holdingRepository.findDistinctTickers()).thenReturn(Set.of("AAPL", "MSFT"));
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(profileService.refreshStale(any(), anyInt())).thenAnswer(inv -> {
            inFlight.countDown();
            release.await(5, TimeUnit.SECONDS);
            return 2;
        });

        SecurityProfileRefreshRunner runner =
            new SecurityProfileRefreshRunner(holdingRepository, profileService);

        assertThat(runner.trigger()).isEqualTo(2);
        assertThat(inFlight.await(5, TimeUnit.SECONDS)).isTrue();

        // Leaning on the button must not fan out into concurrent scrapes of two unofficial
        // sources; -1 is what lets the UI say "already running" instead of claiming it started.
        assertThat(runner.trigger()).isEqualTo(-1);

        release.countDown();
        awaitIdle(runner);
        verify(profileService).refreshStale(any(), anyInt());
    }

    @Test
    void anEmptyPortfolioQueuesNothingAndReleasesTheGuard() {
        when(holdingRepository.findDistinctTickers()).thenReturn(Set.of());

        SecurityProfileRefreshRunner runner =
            new SecurityProfileRefreshRunner(holdingRepository, profileService);

        assertThat(runner.trigger()).isZero();
        // The guard has to come back down, or the button would be dead for the rest of the
        // process's life after one empty call.
        assertThat(runner.isRunning()).isFalse();
        verify(profileService, never()).refreshStale(any(), anyInt());
    }

    @Test
    void aFailingPassStillReleasesTheGuard() {
        when(holdingRepository.findDistinctTickers()).thenReturn(Set.of("AAPL"));
        when(profileService.refreshStale(any(), anyInt()))
            .thenThrow(new RuntimeException("yahoo is down"));

        SecurityProfileRefreshRunner runner =
            new SecurityProfileRefreshRunner(holdingRepository, profileService);

        assertThat(runner.trigger()).isEqualTo(1);
        awaitIdle(runner);
        // A scrape that throws is the normal case for these sources, not an exceptional one.
        assertThat(runner.trigger()).isEqualTo(1);
    }

    /** Plain polling: Awaitility is not on this project's test classpath. */
    private static void awaitIdle(SecurityProfileRefreshRunner runner) {
        assertThat(pollUntil(() -> !runner.isRunning()))
            .as("the refresh guard should have been released")
            .isTrue();
    }

    private static boolean pollUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
