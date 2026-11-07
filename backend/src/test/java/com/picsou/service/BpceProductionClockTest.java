package com.picsou.service;

import com.picsou.port.BanquePopulairePort;
import com.picsou.port.CaisseEpargnePort;
import com.picsou.repository.SidecarSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The production clock of the two BPCE sync services must follow the JVM default zone (pinned to
 * Europe/Paris by {@code TimeZoneConfig}), not UTC: the balance snapshot is dated with
 * {@code LocalDate.now(clock)}, so a UTC clock dates a sync run between 00:00 and 02:00 Paris
 * onto the previous day and overwrites that day's closing snapshot.
 */
class BpceProductionClockTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private TimeZone previousDefault;

    @BeforeEach
    void pinParis() {
        previousDefault = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(PARIS));
    }

    @AfterEach
    void restoreZone() {
        TimeZone.setDefault(previousDefault);
    }

    @Test
    void caisseEpargneProductionClockUsesTheJvmDefaultZone() throws Exception {
        CaisseEpargneSyncService service = new CaisseEpargneSyncService(
            mock(CaisseEpargnePort.class), mock(SidecarSessionRepository.class),
            null, null, null, null, null, null, null, null, null, Runnable::run, null);

        assertThat(engineClock(service).getZone()).isEqualTo(PARIS);
    }

    @Test
    void banquePopulaireProductionClockUsesTheJvmDefaultZone() throws Exception {
        BanquePopulaireSyncService service = new BanquePopulaireSyncService(
            mock(BanquePopulairePort.class), mock(SidecarSessionRepository.class),
            null, null, null, null, null, null, null, null, null, Runnable::run, null, null);

        assertThat(engineClock(service).getZone()).isEqualTo(PARIS);
    }

    private static Clock engineClock(Object service) throws Exception {
        Field engineField = service.getClass().getDeclaredField("engine");
        engineField.setAccessible(true);
        Object engine = engineField.get(service);
        Field clockField = BpceSyncEngine.class.getDeclaredField("clock");
        clockField.setAccessible(true);
        return (Clock) clockField.get(engine);
    }
}
