package com.picsou.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.picsou.exception.SyncException;
import com.picsou.service.sync.SourceSyncResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.lang.reflect.Constructor;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

class BrokerSyncReportingTest {
    private static final long MEMBER_ID = 7L;

    static Stream<Broker> brokers() {
        return Stream.of(
            new Broker("bourso", BoursoSyncService.class),
            new Broker("bourse-direct", BourseDirectSyncService.class),
            new Broker("amundi", AmundiSyncService.class),
            new Broker("fortuneo", FortuneoSyncService.class)
        );
    }

    @ParameterizedTest(name = "{0} maps inactive session errors")
    @MethodSource("brokers")
    void inactiveSessionReportsErrors(Broker broker) throws Exception {
        Harness h = harness(broker);
        h.status.accept(status(broker.serviceType, false, "SESSION_EXPIRED"), null);
        assertThat(h.report.apply(null).status()).isEqualTo(SourceSyncResult.Status.NEEDS_REAUTH);

        h.status.accept(status(broker.serviceType, false, "INVALID_CREDENTIALS"), null);
        SourceSyncResult invalid = h.report.apply(null);
        assertThat(invalid.status()).isEqualTo(broker.source.equals("bourso")
            ? SourceSyncResult.Status.NEEDS_REAUTH : SourceSyncResult.Status.FAILED);

        h.status.accept(status(broker.serviceType, false, null), null);
        assertThat(h.report.apply(null).status()).isEqualTo(SourceSyncResult.Status.SKIPPED_NOT_CONNECTED);
    }

    @ParameterizedTest(name = "{0} queues active sessions")
    @MethodSource("brokers")
    void activeSessionRemainsQueued(Broker broker) throws Exception {
        Harness h = harness(broker);
        Object queued = status(broker.serviceType, true, null);
        h.status.accept(queued, null);
        h.queue.accept(queued, null);
        SourceSyncResult result = h.report.apply(null);
        assertThat(result.source()).isEqualTo(broker.source);
        assertThat(result.status()).isEqualTo(SourceSyncResult.Status.QUEUED);
    }

    @ParameterizedTest(name = "{0} hides database details and logs the throwable")
    @MethodSource("brokers")
    void databaseFailureIsGenericAndLogsThrowable(Broker broker) throws Exception {
        Harness h = harness(broker);
        DataAccessResourceFailureException cause = new DataAccessResourceFailureException(
            "sensitive SQL: select password");
        h.status.accept(null, cause);
        try (CapturedLogs logs = captureLogs(broker.serviceType)) {
            SourceSyncResult result = h.report.apply(null);
            assertThat(result.status()).isEqualTo(SourceSyncResult.Status.FAILED);
            assertThat(result.message()).isEqualTo("Database error").doesNotContain("password", "select");
            assertThat(logs.appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(event.getThrowableProxy().getClassName()).isEqualTo(cause.getClass().getName());
                assertThat(event.getThrowableProxy().getMessage()).isEqualTo(cause.getMessage());
            });
        }
    }

    @ParameterizedTest(name = "{0} classifies sync failures and logs the throwable")
    @MethodSource("brokers")
    void syncFailureLogsThrowableWithoutLeakingItsMessage(Broker broker) throws Exception {
        Harness h = harness(broker);
        SyncException cause = new SyncException("sensitive provider response", null, "SESSION_EXPIRED");
        h.status.accept(null, cause);
        try (CapturedLogs logs = captureLogs(broker.serviceType)) {
            SourceSyncResult result = h.report.apply(null);
            assertThat(result.status()).isEqualTo(SourceSyncResult.Status.NEEDS_REAUTH);
            assertThat(result.message()).doesNotContain("sensitive provider response");
            assertThat(logs.appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(event.getThrowableProxy().getClassName()).isEqualTo(cause.getClass().getName());
                assertThat(event.getThrowableProxy().getMessage()).isEqualTo(cause.getMessage());
            });
        }
    }

    private record CapturedLogs(Logger logger, ListAppender<ILoggingEvent> appender) implements AutoCloseable {
        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static CapturedLogs captureLogs(Class<?> type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new CapturedLogs(logger, appender);
    }

    private static Harness harness(Broker broker) throws Exception {
        Object service = serviceSpy(broker.serviceType);
        return switch (broker.source) {
            case "bourso" -> new Harness(
                (value, failure) -> { if (failure == null) doReturn(value).when((BoursoSyncService) service).getStatus(MEMBER_ID); else doThrow(failure).when((BoursoSyncService) service).getStatus(MEMBER_ID); },
                (value, failure) -> doReturn(value).when((BoursoSyncService) service).queueSync(MEMBER_ID),
                ignored -> ((BoursoSyncService) service).resyncReporting(MEMBER_ID));
            case "bourse-direct" -> new Harness(
                (value, failure) -> { if (failure == null) doReturn(value).when((BourseDirectSyncService) service).getStatus(MEMBER_ID); else doThrow(failure).when((BourseDirectSyncService) service).getStatus(MEMBER_ID); },
                (value, failure) -> doReturn(value).when((BourseDirectSyncService) service).queueSync(MEMBER_ID),
                ignored -> ((BourseDirectSyncService) service).resyncReporting(MEMBER_ID));
            case "amundi" -> new Harness(
                (value, failure) -> { if (failure == null) doReturn(value).when((AmundiSyncService) service).getStatus(MEMBER_ID); else doThrow(failure).when((AmundiSyncService) service).getStatus(MEMBER_ID); },
                (value, failure) -> doReturn(value).when((AmundiSyncService) service).queueSync(MEMBER_ID),
                ignored -> ((AmundiSyncService) service).resyncReporting(MEMBER_ID));
            case "fortuneo" -> new Harness(
                (value, failure) -> { if (failure == null) doReturn(value).when((FortuneoSyncService) service).getStatus(MEMBER_ID); else doThrow(failure).when((FortuneoSyncService) service).getStatus(MEMBER_ID); },
                (value, failure) -> doReturn(value).when((FortuneoSyncService) service).queueSync(MEMBER_ID),
                ignored -> ((FortuneoSyncService) service).resyncReporting(MEMBER_ID));
            default -> throw new IllegalArgumentException(broker.source);
        };
    }

    private static Object serviceSpy(Class<?> serviceType) throws Exception {
        Constructor<?> constructor = serviceType.getConstructors()[0];
        Class<?>[] types = constructor.getParameterTypes();
        Object[] arguments = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            arguments[i] = Executor.class.isAssignableFrom(types[i]) ? (Executor) Runnable::run : mock(types[i]);
        }
        return spy(constructor.newInstance(arguments));
    }

    private static Object status(Class<?> serviceType, boolean active, String errorName) throws Exception {
        Class<?> responseType = Stream.of(serviceType.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals("SessionStatusResponse"))
            .findFirst().orElseThrow();
        Constructor<?> constructor = responseType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Class<?>[] types = constructor.getParameterTypes();
        Object[] values = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == boolean.class) values[i] = active;
            else if (types[i].isEnum()) {
                String name = types[i].getSimpleName().endsWith("ErrorCode")
                    ? errorName : (active ? "QUEUED" : "FAILED");
                values[i] = name == null ? null : enumValue(types[i], name);
            } else values[i] = null;
        }
        return constructor.newInstance(values);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String name) {
        if (name.equals("INVALID_CREDENTIALS") && !java.util.Arrays.stream(type.getEnumConstants())
            .map(Object::toString).anyMatch(name::equals)) {
            return java.util.Arrays.stream((Enum[]) type.getEnumConstants())
                .filter(value -> !value.name().equals("SESSION_EXPIRED"))
                .findFirst().orElseThrow();
        }
        return Enum.valueOf((Class<? extends Enum>) type, name);
    }

    private record Broker(String source, Class<?> serviceType) {
        @Override public String toString() { return source; }
    }
    private record Harness(BiConsumer<Object, Throwable> status, BiConsumer<Object, Throwable> queue,
                           Function<Object, SourceSyncResult> report) {}
}
