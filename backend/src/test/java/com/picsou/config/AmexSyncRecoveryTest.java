package com.picsou.config;

import com.picsou.service.AmexSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AmexSyncRecoveryTest {

    @Test
    void applicationStartupRecoversPersistedInFlightJobs() {
        AmexSyncService syncService = mock(AmexSyncService.class);

        new AmexSyncRecovery(syncService).run(new DefaultApplicationArguments());

        verify(syncService).recoverInterruptedSyncs();
    }
}
