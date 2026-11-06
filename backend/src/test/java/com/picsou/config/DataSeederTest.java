package com.picsou.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.picsou.service.SetupService;
import java.util.List;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DataSeederTest {
    @Mock SetupService setupService;

    @ParameterizedTest
    @CsvSource({"qa-admin,$2a$12$hash,true,true", "qa-admin,$2a$12$hash,false,true",
        "'', $2a$12$hash,true,false", "qa-admin,'',true,false"})
    void envAdminWinsAndWarnsOnlyWhenBothCredentialsAndAdminGroupAreSet(
            String username, String hash, boolean adminGroup, boolean seeded) {
        var properties = new SsoProperties();
        if (adminGroup) properties.setAdminGroup("picsou-admin");
        var seeder = new DataSeeder(setupService, properties);
        ReflectionTestUtils.setField(seeder, "username", username);
        ReflectionTestUtils.setField(seeder, "passwordHash", hash);
        Logger logger = (Logger) LoggerFactory.getLogger(DataSeeder.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        List<ILoggingEvent> events;
        try {
            seeder.run(null);
        } finally {
            logger.detachAppender(appender);
            synchronized (appender) {
                events = List.copyOf(appender.list);
            }
            appender.stop();
        }

        assertThat(events.stream().filter(event -> event.getLevel() == Level.WARN).toList())
            .hasSize(seeded && adminGroup ? 1 : 0);
        if (seeded) {
            verify(setupService).seedAdmin(username, hash, username, null);
            verify(setupService).markComplete();
        } else {
            verifyNoInteractions(setupService);
        }
    }
}
