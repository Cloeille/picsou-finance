package com.picsou.config;

import com.picsou.service.CaisseEpargneSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CaisseEpargneSyncRecoveryTest {

    @Test
    void applicationStartupRecoversPersistedInFlightJobs() {
        CaisseEpargneSyncService syncService = mock(CaisseEpargneSyncService.class);

        new CaisseEpargneSyncRecovery(syncService).run(new DefaultApplicationArguments());

        verify(syncService).recoverInterruptedSyncs();
    }

    @Test
    void theSyncExecutorIsASingleWorkerSoSyncsNeverOverlap() {
        Executor executor = new CaisseEpargneSyncConfig().caisseEpargneSyncExecutor();

        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        assertThat(pool.getCorePoolSize()).isEqualTo(1);
        assertThat(pool.getMaxPoolSize()).isEqualTo(1);
        assertThat(pool.getThreadNamePrefix()).isEqualTo("caisse-epargne-sync-");
        pool.shutdown();
    }
}
