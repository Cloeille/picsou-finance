package com.picsou.config;

import com.picsou.service.CaisseEpargneSyncService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Recovers persisted in-flight jobs at startup; it never starts a sync. */
@Component
@Order(0)
public class CaisseEpargneSyncRecovery implements ApplicationRunner {
    private final CaisseEpargneSyncService syncService;

    public CaisseEpargneSyncRecovery(CaisseEpargneSyncService syncService) {
        this.syncService = syncService;
    }

    @Override
    public void run(ApplicationArguments args) {
        syncService.recoverInterruptedSyncs();
    }
}
