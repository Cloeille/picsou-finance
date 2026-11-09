package com.picsou.config;

import com.picsou.service.NatixisSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class NatixisSyncRecoveryTest {

    @Test
    void applicationStartupFailsTheJobsTheRestartInterrupted() {
        NatixisSyncService syncService = mock(NatixisSyncService.class);

        new NatixisSyncRecovery(syncService).run(new DefaultApplicationArguments());

        verify(syncService).recoverSyncsInterruptedByRestart();
        verify(syncService, never()).recoverInterruptedSyncs();
    }
}
