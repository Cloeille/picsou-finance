package com.picsou.service;

import com.picsou.repository.DegiroSessionRepository;
import com.picsou.repository.FinarySessionRepository;
import com.picsou.repository.IbkrConnectionRepository;
import com.picsou.repository.RequisitionRepository;
import com.picsou.repository.SimplefinConnectionRepository;
import com.picsou.repository.TradeRepublicSessionRepository;
import com.picsou.repository.WalletAddressRepository;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyncStatusTransactionIsolationTest {

    @Test
    void reportsReaderFailureAndKeepsLaterStatusesVisible() {
        try (AnnotationConfigApplicationContext context = context()) {
            String report = context.getBean(SyncStatusService.class).describe(42L);

            assertThat(report).contains("enable-banking: FAILED");
            assertThat(report).contains("degiro: NOT_CONNECTED");
        }
    }

    @Test
    void readerFailureDoesNotRollbackAnExternalTransaction() {
        try (AnnotationConfigApplicationContext context = context()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.execute("create table if not exists tx_witness (marker integer not null)");
            jdbc.update("delete from tx_witness");
            TransactionTemplate callerTransaction = new TransactionTemplate(
                context.getBean(PlatformTransactionManager.class));

            callerTransaction.executeWithoutResult(status -> {
                jdbc.update("insert into tx_witness(marker) values (1)");
                String report = context.getBean(SyncStatusService.class).describe(42L);
                assertThat(report).contains("enable-banking: FAILED");
                assertThat(report).contains("degiro: NOT_CONNECTED");
                jdbc.update("insert into tx_witness(marker) values (2)");
            });

            assertThat(jdbc.queryForList("select marker from tx_witness order by marker", Integer.class))
                .containsExactly(1, 2);
        }
    }

    private static AnnotationConfigApplicationContext context() {
        return new AnnotationConfigApplicationContext(TestConfig.class);
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        RequisitionRepository requisitionRepository(PlatformTransactionManager transactionManager) {
            RequisitionRepository target = mock(RequisitionRepository.class);
            when(target.findAllByMemberId(anyLong()))
                .thenThrow(new DataAccessResourceFailureException("reader failed"));

            TransactionTemplate required = new TransactionTemplate(transactionManager);
            required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
            ProxyFactory proxyFactory = new ProxyFactory(target);
            proxyFactory.setInterfaces(RequisitionRepository.class);
            proxyFactory.addAdvice((MethodInterceptor) invocation -> required.execute(status -> {
                try {
                    return invocation.proceed();
                } catch (Throwable failure) {
                    throw failure instanceof RuntimeException runtimeFailure
                        ? runtimeFailure
                        : new IllegalStateException(failure);
                }
            }));
            return (RequisitionRepository) proxyFactory.getProxy();
        }

        @Bean
        SyncStatusService syncStatusService(RequisitionRepository requisitionRepository) {
            DegiroSessionRepository degiroSessions = mock(DegiroSessionRepository.class);
            when(degiroSessions.findByMemberId(anyLong())).thenReturn(Optional.empty());
            return new SyncStatusService(
                mock(RevolutSyncService.class),
                requisitionRepository,
                mock(TradeRepublicSyncService.class),
                mock(TradeRepublicSessionRepository.class),
                mock(BoursoSyncService.class),
                mock(BourseDirectSyncService.class),
                mock(AmundiSyncService.class),
                mock(FortuneoSyncService.class),
                mock(AmexSyncService.class),
                mock(IbkrConnectionRepository.class),
                mock(SimplefinConnectionRepository.class),
                mock(CryptoExchangeSyncService.class),
                mock(WalletAddressRepository.class),
                mock(FinarySessionRepository.class),
                degiroSessions);
        }
    }
}
