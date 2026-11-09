package com.picsou.config;

import com.picsou.service.BoursoSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BoursoSyncRecoveryTest {

    @Test
    void applicationStartupRecoversPersistedInFlightJobs() {
        BoursoSyncService syncService = mock(BoursoSyncService.class);

        new BoursoSyncRecovery(syncService).run(new DefaultApplicationArguments());

        verify(syncService).recoverInterruptedSyncs();
    }
}
