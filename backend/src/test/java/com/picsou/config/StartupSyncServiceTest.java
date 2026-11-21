package com.picsou.config;

import com.picsou.service.CatchUpScheduler;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class StartupSyncServiceTest {

    @Test
    void run_skipsInitialSyncWhenDisabled() {
        CatchUpScheduler scheduler = mock(CatchUpScheduler.class);
        StartupSyncService service = new StartupSyncService(scheduler, false);

        service.run(null);

        verify(scheduler, never()).runNow(CatchUpScheduler.DAILY_BANK_SYNC);
    }

    @Test
    void run_startsInitialSyncWhenEnabled() {
        CatchUpScheduler scheduler = mock(CatchUpScheduler.class);
        StartupSyncService service = new StartupSyncService(scheduler, true);

        service.run(null);

        verify(scheduler).runNow(CatchUpScheduler.DAILY_BANK_SYNC);
    }
}
