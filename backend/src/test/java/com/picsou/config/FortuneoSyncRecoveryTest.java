package com.picsou.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.picsou.service.FortuneoSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FortuneoSyncRecoveryTest {

    @Mock FortuneoSyncService syncService;

    @Test
    void applicationStartupRecoversPersistedInFlightJobs() {
        new FortuneoSyncRecovery(syncService).run(new DefaultApplicationArguments());

        verify(syncService).recoverInterruptedSyncs();
    }

    @Test
    void recoveryFailureIsLoggedAndAbortsStartup() {
        IllegalStateException failure = new IllegalStateException("database unavailable");
        doThrow(failure).when(syncService).recoverInterruptedSyncs();
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(FortuneoSyncRecovery.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);

        try {
            assertThatThrownBy(() -> new FortuneoSyncRecovery(syncService)
                .run(new DefaultApplicationArguments()))
                .isSameAs(failure);

            assertThat(logs.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage())
                    .isEqualTo("Fortuneo sync recovery failed; aborting application startup");
                assertThat(event.getThrowableProxy().getMessage()).isEqualTo("database unavailable");
            });
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }
}
