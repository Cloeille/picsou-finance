package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.exception.DegiroSessionExpiredException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.DegiroSession;
import com.picsou.model.DegiroSessionStatus;
import com.picsou.model.FamilyMember;
import com.picsou.port.DegiroPort;
import com.picsou.port.DegiroPort.DegiroPortfolioData;
import com.picsou.port.DegiroPort.DegiroPosition;
import com.picsou.port.DegiroPort.InitiateResult;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.DegiroSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DegiroSyncServiceTest {

    @Mock DegiroPort port;
    @Mock DegiroSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock AccountService accountService;
    @Mock OpenFigiIsinConverter isinConverter;
    @Mock SecurityIdentityService identityService;
    @Mock CryptoEncryption encryption;
    @Mock DegiroSessionStatusWriter statusWriter;
    @Captor ArgumentCaptor<AccountHolding> holdingCaptor;

    DegiroSyncService service;

    static final Long MEMBER_ID = 7L;

    @BeforeEach
    void setUp() {
        service = new DegiroSyncService(
            port, sessionRepository, accountRepository, holdingRepository,
            memberRepository, accountService, isinConverter, identityService, encryption, statusWriter);
    }

    private FamilyMember member() {
        return FamilyMember.builder().id(MEMBER_ID).build();
    }

    // ─── Auth ─────────────────────────────────────────────────────────────────

    @Test
    void initiateAuth_noTotpRequired_storesSessionAndSyncsImmediately() {
        when(port.initiateAuth("user", "pw"))
            .thenReturn(new InitiateResult(null, false, "plain-blob"));
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(encryption.encrypt("plain-blob")).thenReturn("enc-blob");
        when(sessionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());
        when(port.fetchPortfolio("plain-blob")).thenReturn(new DegiroPortfolioData(BigDecimal.TEN, List.of()));
        when(accountRepository.findByExternalAccountIdAndMemberId("degiro-portfolio", MEMBER_ID))
            .thenReturn(Optional.empty());
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountService.toResponse(any())).thenReturn(mockResponse());

        DegiroSyncService.AuthInitResponse resp = service.initiateAuth("user", "pw", MEMBER_ID);

        assertThat(resp.totpRequired()).isFalse();
        ArgumentCaptor<DegiroSession> sessionCaptor = ArgumentCaptor.forClass(DegiroSession.class);
        verify(sessionRepository).save(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getSessionBlob()).isEqualTo("enc-blob");
        assertThat(sessionCaptor.getValue().getStatus()).isEqualTo(DegiroSessionStatus.ACTIVE);
    }

    @Test
    void initiateAuth_totpRequired_doesNotStoreSessionYet() {
        when(port.initiateAuth("user", "pw"))
            .thenReturn(new InitiateResult("proc-1", true, null));

        DegiroSyncService.AuthInitResponse resp = service.initiateAuth("user", "pw", MEMBER_ID);

        assertThat(resp.totpRequired()).isTrue();
        assertThat(resp.processId()).isEqualTo("proc-1");
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void completeAuth_exchangesCodeAndStoresSession() {
        when(port.completeAuth("proc-1", "123456")).thenReturn("plain-blob");
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(encryption.encrypt("plain-blob")).thenReturn("enc-blob");
        when(sessionRepository.findByMemberId(MEMBER_ID))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(DegiroSession.builder()
                .status(DegiroSessionStatus.ACTIVE).sessionBlob("enc-blob").build()));
        when(port.fetchPortfolio("plain-blob")).thenReturn(new DegiroPortfolioData(BigDecimal.TEN, List.of()));
        when(accountRepository.findByExternalAccountIdAndMemberId("degiro-portfolio", MEMBER_ID))
            .thenReturn(Optional.empty());
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountService.toResponse(any())).thenReturn(mockResponse());

        DegiroSyncService.SessionStatusResponse status = service.completeAuth("proc-1", "123456", MEMBER_ID);

        assertThat(status.isActive()).isTrue();
        verify(sessionRepository).save(any());
    }

    // ─── Sync ─────────────────────────────────────────────────────────────────

    @Test
    void sync_noStoredSession_throws() {
        when(sessionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sync(MEMBER_ID))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("No DEGIRO session");
    }

    @Test
    void sync_sessionMarkedReauthRequired_throwsWithoutCallingPort() {
        when(sessionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(
            DegiroSession.builder().status(DegiroSessionStatus.REAUTH_REQUIRED).sessionBlob("enc").build()));

        assertThatThrownBy(() -> service.sync(MEMBER_ID))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("reconnect");
        verify(port, never()).fetchPortfolio(any());
    }

    @Test
    void sync_upsertsAccountAndDedupesHoldingsByResolvedTicker() {
        DegiroSession session = DegiroSession.builder().status(DegiroSessionStatus.ACTIVE).sessionBlob("enc").build();
        when(sessionRepository.findByMemberId(MEMBER_ID))
            .thenReturn(Optional.of(session))
            .thenReturn(Optional.of(session));
        when(encryption.decrypt("enc")).thenReturn("plain");
        DegiroPosition position = new DegiroPosition(
            "IE00B4L5Y983", "IWDA", "iShares Core MSCI World",
            BigDecimal.TEN, BigDecimal.valueOf(70), BigDecimal.valueOf(80));
        when(port.fetchPortfolio("plain"))
            .thenReturn(new DegiroPortfolioData(BigDecimal.valueOf(500), List.of(position)));
        when(isinConverter.resolve("IE00B4L5Y983"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("IWDA.AS", "iShares Core MSCI World"));
        when(accountRepository.findByExternalAccountIdAndMemberId("degiro-portfolio", MEMBER_ID))
            .thenReturn(Optional.empty());
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountService.toResponse(any())).thenReturn(mockResponse());

        service.sync(MEMBER_ID);

        verify(holdingRepository).save(holdingCaptor.capture());
        assertThat(holdingCaptor.getValue().getTicker()).isEqualTo("IWDA.AS");
        assertThat(holdingCaptor.getValue().getQuantity()).isEqualByComparingTo(BigDecimal.TEN);
        // 500 cash + (10 qty * 80 currentPrice) positions = 1300 total account value —
        // regression check for the real bug found live: currentBalance/snapshot must be
        // cash + positions, not cash alone (that under-reported net worth by the whole
        // positions value on a real account).
        verify(accountService).upsertSnapshot(any(), eq(BigDecimal.valueOf(1300)), any());
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, atLeastOnce()).save(accountCaptor.capture());
        Account savedAccount = accountCaptor.getValue();
        assertThat(savedAccount.getCurrentBalance()).isEqualByComparingTo(BigDecimal.valueOf(1300));
        assertThat(savedAccount.getCashBalance()).isEqualByComparingTo(BigDecimal.valueOf(500));
    }

    @Test
    void sync_sessionExpired_marksReauthRequiredAndThrows() {
        DegiroSession session = DegiroSession.builder().status(DegiroSessionStatus.ACTIVE).sessionBlob("enc").build();
        when(sessionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(session));
        when(encryption.decrypt("enc")).thenReturn("plain");
        when(port.fetchPortfolio("plain")).thenThrow(new DegiroSessionExpiredException());

        assertThatThrownBy(() -> service.sync(MEMBER_ID))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("reconnect");

        // Through the REQUIRES_NEW writer, never by mutating the managed entity: rethrowing
        // marks this service's transaction rollback-only, so an in-transaction save would be
        // discarded and the user would never be prompted to reconnect.
        verify(statusWriter).markReauthRequired(MEMBER_ID);
    }

    @Test
    void sync_nonExpiryFailure_leavesSessionStatusAlone() {
        DegiroSession session = DegiroSession.builder().status(DegiroSessionStatus.ACTIVE).sessionBlob("enc").build();
        when(sessionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(session));
        when(encryption.decrypt("enc")).thenReturn("plain");
        when(port.fetchPortfolio("plain")).thenThrow(new SyncException("Could not fetch your DEGIRO portfolio."));

        assertThatThrownBy(() -> service.sync(MEMBER_ID)).isInstanceOf(SyncException.class);

        verify(statusWriter, never()).markReauthRequired(any());
    }

    // ─── Status / clear ────────────────────────────────────────────────────────

    @Test
    void getSessionStatus_noSession_returnsInactive() {
        when(sessionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());

        DegiroSyncService.SessionStatusResponse status = service.getSessionStatus(MEMBER_ID);

        assertThat(status.isActive()).isFalse();
    }

    @Test
    void clearSession_deletesStoredSession() {
        DegiroSession session = DegiroSession.builder().build();
        when(sessionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(session));

        service.clearSession(MEMBER_ID);

        verify(sessionRepository, times(1)).delete(session);
    }

    private AccountResponse mockResponse() {
        return new AccountResponse(
            1L, "DEGIRO", AccountType.COMPTE_TITRES, "DEGIRO", "EUR",
            BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, Instant.now(),
            false, "#f97316", null, null, null, Instant.now(), null, null,
            null, null, null, false,
            // Ownership shares (this branch): a wholly-owned account carries a null
            // share and is administered by its member.
            null, true);
    }
}
