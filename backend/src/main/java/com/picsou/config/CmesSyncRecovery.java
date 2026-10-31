package com.picsou.config;

import com.picsou.service.CmesSyncService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Recovers persisted jobs before {@link StartupSyncService} queues startup work. */
@Component
@Order(0)
public class CmesSyncRecovery implements ApplicationRunner {
    private final CmesSyncService syncService;

    public CmesSyncRecovery(CmesSyncService syncService) {
        this.syncService = syncService;
    }

    @Override
    public void run(ApplicationArguments args) {
        syncService.recoverInterruptedSyncs();
    }
}
