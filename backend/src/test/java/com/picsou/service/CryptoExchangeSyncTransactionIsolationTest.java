package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.CryptoExchangeSession;
import com.picsou.model.ExchangeType;
import com.picsou.model.FamilyMember;
import com.picsou.port.CryptoExchangePort;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CryptoExchangePositionRepository;
import com.picsou.repository.CryptoExchangeSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.service.sync.SourceSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.when;

/** Verifies per-exchange rollback isolation against the real PostgreSQL schema. */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import(CryptoExchangeSyncService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CryptoExchangeSyncTransactionIsolationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired CryptoExchangeSyncService service;
    @Autowired CryptoExchangeSessionRepository sessions;
    @Autowired AccountRepository accounts;
    @Autowired FamilyMemberRepository members;
    @Autowired CryptoExchangePositionRepository positions;
    @Autowired PlatformTransactionManager transactionManager;

    @MockitoBean CryptoExchangePort adapter;
    @MockitoBean CryptoExchangePort meriaAdapter;
    @MockitoBean AccountService accountService;
    @MockitoBean PriceService priceService;
    @MockitoBean CryptoEncryption encryption;
    @MockitoBean CryptoExchangeStatusWriter statusWriter;
    @MockitoBean CryptoLogoService cryptoLogoService;

    private FamilyMember member;
    private CryptoExchangeSession failingSession;
    private CryptoExchangeSession successfulSession;
    private Account failingAccount;
    private Account successfulAccount;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> seedData());
    }

    private void seedData() {
        member = members.saveAndFlush(FamilyMember.builder().displayName("crypto tx isolation").build());
        failingAccount = account(ExchangeType.BINANCE);
        successfulAccount = account(ExchangeType.MERIA);
        failingSession = session(ExchangeType.BINANCE);
        successfulSession = session(ExchangeType.MERIA);
        when(encryption.decrypt(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(adapter.exchangeName()).thenReturn(ExchangeType.BINANCE.name());
        when(adapter.requiresApiSecret()).thenReturn(false);
        when(meriaAdapter.exchangeName()).thenReturn(ExchangeType.MERIA.name());
        when(meriaAdapter.requiresApiSecret()).thenReturn(false);
        when(adapter.fetchPositions("bad", null)).thenReturn(List.of(
            new CryptoExchangePort.ExchangePosition(null, "BTC", BigDecimal.ONE, null, null)));
        when(meriaAdapter.fetchPositions("good", null)).thenReturn(List.of(
            CryptoExchangePort.ExchangePosition.spot("ETH", BigDecimal.ONE)));
        when(priceService.refreshCryptoQuotes(anySet())).thenReturn(Map.of(
            "BTC", new PriceService.Quote(BigDecimal.TEN, null, true),
            "ETH", new PriceService.Quote(BigDecimal.TEN, null, true)));
        when(accountService.toResponse(any())).thenReturn(null);
        when(cryptoLogoService.getLogoUrls(anySet())).thenReturn(Map.of());
    }

    @Test
    void legacyResyncAllKeepsSuccessfulExchangeCommitAfterDbWriteFailureInsideCallerTransaction() {
        new TransactionTemplate(transactionManager).executeWithoutResult(outer -> service.resyncAll(member.getId()));

        assertThat(positions.findByAccountIdOrderByProductAscTickerAsc(failingAccount.getId())).isEmpty();
        assertThat(positions.findByAccountIdOrderByProductAscTickerAsc(successfulAccount.getId()))
            .singleElement().extracting(position -> position.getTicker()).isEqualTo("ETH");
        assertThat(sessions.findById(failingSession.getId()).orElseThrow().getLastSyncedAt()).isNull();
        assertThat(sessions.findById(successfulSession.getId()).orElseThrow().getLastSyncedAt()).isNotNull();
    }

    @Test
    void reportingResyncCommitsSuccessfulExchangeAfterDbWriteFailure() {
        SourceSyncResult result = service.resyncAllReporting(member.getId());

        assertThat(result.status()).isEqualTo(SourceSyncResult.Status.FAILED);
        assertThat(positions.findByAccountIdOrderByProductAscTickerAsc(successfulAccount.getId()))
            .singleElement().extracting(position -> position.getTicker()).isEqualTo("ETH");
        assertThat(sessions.findById(successfulSession.getId()).orElseThrow().getLastSyncedAt()).isNotNull();
    }

    private Account account(ExchangeType type) {
        return accounts.saveAndFlush(Account.builder().member(member).name(type.name())
            .type(AccountType.CRYPTO).currentBalance(BigDecimal.ZERO)
            .externalAccountId("crypto_exchange_" + type.name().toLowerCase()).isManual(false).build());
    }

    private CryptoExchangeSession session(ExchangeType type) {
        String key = type == ExchangeType.BINANCE ? "bad" : "good";
        return sessions.saveAndFlush(CryptoExchangeSession.builder().member(member).exchangeType(type)
            .apiKey(key).status("CONNECTED").build());
    }
}
