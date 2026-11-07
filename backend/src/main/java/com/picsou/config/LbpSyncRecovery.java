package com.picsou.config;

import com.picsou.service.LbpSyncService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Recovers persisted in-flight jobs at startup; it never starts a sync. */
@Component
@Order(0)
public class LbpSyncRecovery implements ApplicationRunner {
    private final LbpSyncService syncService;

    public LbpSyncRecovery(LbpSyncService syncService) {
        this.syncService = syncService;
    }

    @Override
    public void run(ApplicationArguments args) {
        syncService.recoverInterruptedSyncs();
    }
}
