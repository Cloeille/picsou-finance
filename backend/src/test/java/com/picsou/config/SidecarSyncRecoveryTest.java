package com.picsou.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.picsou.service.AmexSyncService;
import com.picsou.service.AmundiSyncService;
import com.picsou.service.BanquePopulaireSyncService;
import com.picsou.service.BnpSyncService;
import com.picsou.service.BourseDirectSyncService;
import com.picsou.service.BoursoSyncService;
import com.picsou.service.CaisseEpargneSyncService;
import com.picsou.service.CicSyncService;
import com.picsou.service.CmesSyncService;
import com.picsou.service.FortuneoSyncService;
import com.picsou.service.TradeRepublicSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SidecarSyncRecoveryTest {

    @Mock AmexSyncService amex;
    @Mock AmundiSyncService amundi;
    @Mock BanquePopulaireSyncService banquePopulaire;
    @Mock BnpSyncService bnp;
    @Mock BourseDirectSyncService bourseDirect;
    @Mock BoursoSyncService bourso;
    @Mock CaisseEpargneSyncService caisseEpargne;
    @Mock CicSyncService cic;
    @Mock CmesSyncService cmes;
    @Mock FortuneoSyncService fortuneo;
    @Mock TradeRepublicSyncService tradeRepublic;

    private SidecarSyncRecovery recovery() {
        return new SidecarSyncRecovery(amex, amundi, banquePopulaire, bnp, bourseDirect, bourso, caisseEpargne, cic, cmes,
            fortuneo, tradeRepublic);
    }

    @Test
    void applicationStartupRecoversEveryBanksInFlightJobs() {
        recovery().run(new DefaultApplicationArguments());

        verify(amex).recoverInterruptedSyncs();
        verify(amundi).recoverInterruptedSyncs();
        verify(banquePopulaire).recoverInterruptedSyncs();
        verify(bnp).recoverInterruptedSyncs();
        verify(bourseDirect).recoverInterruptedSyncs();
        verify(bourso).recoverInterruptedSyncs();
        verify(caisseEpargne).recoverInterruptedSyncs();
        verify(cic).recoverInterruptedSyncs();
        verify(cmes).recoverInterruptedSyncs();
        verify(fortuneo).recoverInterruptedSyncs();
        verify(tradeRepublic).recoverInterruptedSyncs();
    }

    @Test
    void recoveryFailureIsLoggedAndAbortsStartup() {
        IllegalStateException failure = new IllegalStateException("database unavailable");
        doThrow(failure).when(caisseEpargne).recoverInterruptedSyncs();
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SidecarSyncRecovery.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            assertThatThrownBy(() -> recovery().run(new DefaultApplicationArguments())).isSameAs(failure);

            assertThat(logs.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage())
                    .isEqualTo("Caisse d'Epargne sync recovery failed; aborting application startup");
                assertThat(event.getThrowableProxy().getMessage()).isEqualTo("database unavailable");
            });
            verify(cic, never()).recoverInterruptedSyncs();
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void theCaisseEpargneSyncExecutorIsASingleWorkerSoSyncsNeverOverlap() {
        Executor executor = new SyncExecutorConfig().caisseEpargneSyncExecutor();

        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        assertThat(pool.getCorePoolSize()).isEqualTo(1);
        assertThat(pool.getMaxPoolSize()).isEqualTo(1);
        assertThat(pool.getThreadNamePrefix()).isEqualTo("caisse-epargne-sync-");
        pool.shutdown();
    }

    @Test
    void theBanquePopulaireSyncExecutorIsASingleWorkerSoSyncsNeverOverlap() {
        Executor executor = new SyncExecutorConfig().banquePopulaireSyncExecutor();

        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        assertThat(pool.getCorePoolSize()).isEqualTo(1);
        assertThat(pool.getMaxPoolSize()).isEqualTo(1);
        assertThat(pool.getThreadNamePrefix()).isEqualTo("banque-populaire-sync-");
        pool.shutdown();
    }
}
