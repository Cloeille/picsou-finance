package com.picsou.config;

import com.picsou.service.AmexSyncService;
import com.picsou.service.AmundiSyncService;
import com.picsou.service.BanquePopulaireSyncService;
import com.picsou.service.BourseDirectSyncService;
import com.picsou.service.BnpSyncService;
import com.picsou.service.BoursoSyncService;
import com.picsou.service.CaisseEpargneSyncService;
import com.picsou.service.CicSyncService;
import com.picsou.service.CmesSyncService;
import com.picsou.service.FortuneoSyncService;
import com.picsou.service.TradeRepublicSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Browser-backed sidecar jobs cannot survive a restart: at startup, every persisted QUEUED or
 * RUNNING session becomes a retryable failure. A recovery that fails aborts startup, since
 * continuing would leave a session blocking every later sync of that bank.
 */
@Component
@Order(0)
public class SidecarSyncRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SidecarSyncRecovery.class);

    private final Map<String, Runnable> recoveries = new LinkedHashMap<>();

    public SidecarSyncRecovery(
        AmexSyncService amex,
        AmundiSyncService amundi,
        BanquePopulaireSyncService banquePopulaire,
        BnpSyncService bnp,
        BourseDirectSyncService bourseDirect,
        BoursoSyncService bourso,
        CaisseEpargneSyncService caisseEpargne,
        CicSyncService cic,
        CmesSyncService cmes,
        FortuneoSyncService fortuneo,
        TradeRepublicSyncService tradeRepublic
    ) {
        recoveries.put("Amex", amex::recoverInterruptedSyncs);
        recoveries.put("Amundi", amundi::recoverInterruptedSyncs);
        recoveries.put("Banque Populaire", banquePopulaire::recoverInterruptedSyncs);
        recoveries.put("BNP Paribas", bnp::recoverInterruptedSyncs);
        recoveries.put("Bourse Direct", bourseDirect::recoverInterruptedSyncs);
        recoveries.put("BoursoBank", bourso::recoverInterruptedSyncs);
        recoveries.put("Caisse d'Epargne", caisseEpargne::recoverInterruptedSyncs);
        recoveries.put("CIC", cic::recoverInterruptedSyncs);
        recoveries.put("CMES", cmes::recoverInterruptedSyncs);
        recoveries.put("Fortuneo", fortuneo::recoverInterruptedSyncs);
        recoveries.put("Trade Republic", tradeRepublic::recoverInterruptedSyncs);
    }

    @Override
    public void run(ApplicationArguments args) {
        recoveries.forEach((bank, recovery) -> {
            try {
                recovery.run();
            } catch (RuntimeException ex) {
                log.error("{} sync recovery failed; aborting application startup", bank, ex);
                throw ex;
            }
        });
    }
}
