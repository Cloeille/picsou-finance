package com.picsou.service;

import com.picsou.adapter.LbpAdapter;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.FamilyMember;
import com.picsou.model.LbpSession;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.LbpSessionRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** The only login field that may be remembered is the identifier, encrypted at rest. */
@ExtendWith(MockitoExtension.class)
class LbpSyncServiceStoreSessionTest {
    private static final long MEMBER = 7L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC);

    @Mock LbpAdapter adapter;
    @Mock LbpSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock AccountService accountService;
    @Mock SavingsAutoConfigService savingsAutoConfig;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock Executor executor;

    private LbpSyncService service;
    private LbpSession saved;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new LbpSyncService(adapter, sessionRepository, accountRepository, transactionRepository,
            memberRepository, accountService, savingsAutoConfig, encryption, txTemplate, executor, CLOCK);
        lenient().when(txTemplate.execute(any())).thenAnswer(invocation -> {
            var callback = (org.springframework.transaction.support.TransactionCallback<?>) invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        lenient().when(memberRepository.findById(MEMBER))
            .thenReturn(Optional.of(FamilyMember.builder().id(MEMBER).build()));
        lenient().when(encryption.encrypt(any())).thenAnswer(invocation -> "enc-" + invocation.getArgument(0));
        lenient().when(encryption.decrypt(any())).thenAnswer(invocation ->
            ((String) invocation.getArgument(0)).substring("enc-".length()));
        lenient().when(sessionRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            saved = invocation.getArgument(0);
            return saved;
        });
        lenient().when(sessionRepository.findByMemberId(MEMBER))
            .thenAnswer(invocation -> Optional.ofNullable(saved));
    }

    @Test
    void storesTheIdentifierEncryptedWhenTheUserAsksToRememberIt() {
        LbpSyncService.SessionStatusResponse status =
            service.storeSession("cookies", MEMBER, "1234567890", true);

        assertThat(saved.getEncryptedIdentifier()).isEqualTo("enc-1234567890");
        assertThat(status.savedIdentifier()).isEqualTo("1234567890");
    }

    @Test
    void clearsTheIdentifierWhenTheUserDoesNotWantIt() {
        LbpSyncService.SessionStatusResponse status =
            service.storeSession("cookies", MEMBER, "1234567890", false);

        assertThat(saved.getEncryptedIdentifier()).isNull();
        assertThat(status.savedIdentifier()).isNull();
    }

    @Test
    void replacingAPreviouslySavedIdentifierWithAnOptOutClearsIt() {
        when(sessionRepository.findByMemberIdForUpdate(MEMBER))
            .thenReturn(Optional.of(LbpSession.create(
                FamilyMember.builder().id(MEMBER).build(), "old-encrypted", CLOCK.instant())));

        LbpSyncService.SessionStatusResponse status =
            service.storeSession("cookies", MEMBER, "1234567890", false);

        assertThat(saved.getEncryptedIdentifier()).isNull();
        assertThat(status.savedIdentifier()).isNull();
    }

    @Test
    void refusesASessionFromAnAttemptStartedBeforeADisconnect() {
        service.clearSession(MEMBER);

        assertThatThrownBy(() ->
            service.storeSession("cookies", MEMBER, "1234567890", true, 0L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo("AUTH_ATTEMPT_EXPIRED"));
    }
}
