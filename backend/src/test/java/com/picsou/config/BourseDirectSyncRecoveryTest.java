package com.picsou.config;

import com.picsou.service.BourseDirectSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BourseDirectSyncRecoveryTest {

    @Test
    void applicationStartupRecoversPersistedInFlightJobs() {
        BourseDirectSyncService syncService = mock(BourseDirectSyncService.class);

        new BourseDirectSyncRecovery(syncService).run(new DefaultApplicationArguments());

        verify(syncService).recoverInterruptedSyncs();
    }
}
