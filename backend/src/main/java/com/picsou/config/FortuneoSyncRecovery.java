package com.picsou.config;

import com.picsou.service.FortuneoSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Recovers persisted jobs before {@link StartupSyncService} queues startup work. */
@Component
@Order(0)
public class FortuneoSyncRecovery implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(FortuneoSyncRecovery.class);

    private final FortuneoSyncService syncService;

    public FortuneoSyncRecovery(FortuneoSyncService syncService) {
        this.syncService = syncService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            syncService.recoverInterruptedSyncs();
        } catch (RuntimeException ex) {
            // Continuing would leave persisted QUEUED/RUNNING sessions blocking all
            // later synchronizations. Fail startup loudly so operators can act on the
            // original exception instead of discovering an indefinitely stuck session.
            log.error("Fortuneo sync recovery failed; aborting application startup", ex);
            throw ex;
        }
    }
}
