package com.picsou.config;

import com.picsou.service.SchedulerService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class StartupSyncServiceTest {

    @Test
    void run_skipsInitialSyncWhenDisabled() {
        SchedulerService scheduler = mock(SchedulerService.class);
        StartupSyncService service = new StartupSyncService(scheduler, false);

        service.run(null);

        verify(scheduler, never()).dailyBankSync();
    }

    @Test
    void run_startsInitialSyncWhenEnabled() {
        SchedulerService scheduler = mock(SchedulerService.class);
        StartupSyncService service = new StartupSyncService(scheduler, true);

        service.run(null);

        verify(scheduler).dailyBankSync();
    }
}
