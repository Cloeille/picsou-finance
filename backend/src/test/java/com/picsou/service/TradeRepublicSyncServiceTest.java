package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.adapter.OpenFigiIsinConverter.TickerResult;
import com.picsou.config.CryptoEncryption;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.TradeRepublicSession;
import com.picsou.port.TradeRepublicPort;
import com.picsou.port.TradeRepublicPort.TrAccountData;
import com.picsou.port.TradeRepublicPort.TrPosition;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TradeRepublicSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradeRepublicSyncServiceTest {

    @Mock TradeRepublicPort trPort;
    @Mock TradeRepublicSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock OpenFigiIsinConverter isinConverter;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;

    @InjectMocks TradeRepublicSyncService service;

    /**
     * When two ISINs resolve to the same ticker, the saved holding's averageBuyIn
     * must be the VWAP -- not whichever position HashMap iteration happens to yield first.
     *
     * Scenario: ISIN_A (qty=2, avg=10) and ISIN_B (qty=3, avg=20) both resolve to "RKLB".
     * Expected merged holding: quantity=5, averageBuyIn = (2*10 + 3*20)/5 = 16,
     * provider value = 2*100 + 3*110 = 530.
     */
    @Test
    void sync_mergesDuplicateTickersWithVwap() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .expiresAt(java.time.Instant.now().plusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");

        TrPosition pos1 = new TrPosition("IE00ISIN_A", bd("2"), bd("10"), bd("100"));
        TrPosition pos2 = new TrPosition("IE00ISIN_B", bd("3"), bd("20"), bd("110"));
        TrAccountData accountData = new TrAccountData(
            "tr_cto", "TR Titres", AccountType.COMPTE_TITRES, bd("530"), List.of(pos1, pos2));
        when(trPort.fetchAccounts("plain-session")).thenReturn(List.of(accountData));

        when(isinConverter.resolve("IE00ISIN_A")).thenReturn(new TickerResult("RKLB", "Rocket Lab"));
        when(isinConverter.resolve("IE00ISIN_B")).thenReturn(new TickerResult("RKLB", "Rocket Lab"));

        when(accountRepository.findByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(false);
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenAnswer(inv -> com.picsou.dto.AccountResponse.from(inv.getArgument(0), bd("530")));

        service.sync(memberId);

        ArgumentCaptor<AccountHolding> captor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository).save(captor.capture());

        AccountHolding saved = captor.getValue();
        assertThat(saved.getTicker()).isEqualTo("RKLB");
        assertThat(saved.getQuantity()).isEqualByComparingTo("5");
        // VWAP: (2*10 + 3*20) / 5 = 16  -- scale-8 representation 16.00000000
        assertThat(saved.getAverageBuyIn()).isEqualByComparingTo("16.00000000");
        assertThat(saved.getProviderValueEur()).isEqualByComparingTo("530");
    }

    /**
     * Two things a PEA sync must get right. The daily snapshot comes after the holdings are
     * replaced: the 3-arg upsertSnapshot derives investedAmount from the holdings in the table,
     * and taken first it costed today with the previous sync's positions. And the cash pocket,
     * already inside balanceEur, reaches Account.cashBalance so the valuation can put it on both
     * sides instead of dropping it from the live value.
     */
    @Test
    void sync_snapshotsAfterTheHoldingsAreReplaced_andStoresThePeaCashPocket() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .expiresAt(java.time.Instant.now().plusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");

        TrPosition posA = new TrPosition("IE00ISIN_A", bd("2"), bd("10"), bd("100"));
        TrPosition posB = new TrPosition("IE00ISIN_B", bd("1"), bd("50"), bd("100"));
        TrAccountData accountData = new TrAccountData(
            "tr_pea", "TR PEA", AccountType.PEA, bd("700"), List.of(posA, posB), bd("500"));
        when(trPort.fetchAccounts("plain-session")).thenReturn(List.of(accountData));
        when(isinConverter.resolve("IE00ISIN_A")).thenReturn(new TickerResult("RKLB", "Rocket Lab"));
        when(isinConverter.resolve("IE00ISIN_B")).thenReturn(new TickerResult("MSFT", "Microsoft"));

        when(accountRepository.findByExternalAccountIdAndMemberId("tr_pea", memberId))
            .thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("tr_pea", memberId))
            .thenReturn(false);
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenAnswer(inv -> com.picsou.dto.AccountResponse.from(inv.getArgument(0), bd("700")));

        service.sync(memberId);

        InOrder inOrder = inOrder(holdingRepository, accountService);
        inOrder.verify(holdingRepository).deleteByAccountId(1L);
        // Both positions land before the snapshot: a snapshot taken inside the position loop
        // would pass a single-position fixture.
        inOrder.verify(holdingRepository, times(2)).save(any(AccountHolding.class));
        inOrder.verify(accountService).upsertSnapshot(any(Account.class), eq(bd("700")), any());

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        assertThat(captor.getValue().getCashBalance()).isEqualByComparingTo("500");
        assertThat(captor.getValue().getCurrentBalance()).isEqualByComparingTo("700");
    }

    @Test
    void sync_storesTheBrokerPositionValueInEur() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .expiresAt(java.time.Instant.now().plusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");

        TrPosition unpriceable = new TrPosition("IE000BI8OT95", bd("10"), bd("80"), bd("84"));
        TrAccountData accountData = new TrAccountData(
            "tr_cto", "TR Titres", AccountType.COMPTE_TITRES, bd("840"), List.of(unpriceable));
        when(trPort.fetchAccounts("plain-session")).thenReturn(List.of(accountData));
        when(isinConverter.resolve("IE000BI8OT95"))
            .thenReturn(new TickerResult("MWRDF", "Amundi Core MSCI World"));

        when(accountRepository.findByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(false);
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenAnswer(inv -> com.picsou.dto.AccountResponse.from(inv.getArgument(0), bd("840")));

        service.sync(memberId);

        ArgumentCaptor<AccountHolding> captor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository).save(captor.capture());

        AccountHolding saved = captor.getValue();
        assertThat(saved.getQuoteCurrency()).isEqualTo("EUR");
        assertThat(saved.getProviderValueEur()).isEqualByComparingTo("840"); // 10 × 84
    }

    @Test
    void sync_fallsBackToAverageBuyIn_whenTradeRepublicHasNoLivePrice() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .expiresAt(java.time.Instant.now().plusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");

        TrPosition noPrice = new TrPosition("IE000BI8OT95", bd("10"), bd("80"), bd("0"));
        TrAccountData accountData = new TrAccountData(
            "tr_cto", "TR Titres", AccountType.COMPTE_TITRES, bd("800"), List.of(noPrice));
        when(trPort.fetchAccounts("plain-session")).thenReturn(List.of(accountData));
        when(isinConverter.resolve("IE000BI8OT95"))
            .thenReturn(new TickerResult("MWRDF", "Amundi Core MSCI World"));

        when(accountRepository.findByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(false);
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenAnswer(inv -> com.picsou.dto.AccountResponse.from(inv.getArgument(0), bd("800")));

        service.sync(memberId);

        ArgumentCaptor<AccountHolding> captor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository).save(captor.capture());

        assertThat(captor.getValue().getProviderValueEur()).isEqualByComparingTo("800"); // 10 × 80
    }

    @Test
    void sync_deletesOldHoldingsWhenPortfolioReturnsEmpty() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .expiresAt(java.time.Instant.now().plusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");

        TrAccountData accountData = new TrAccountData(
            "tr_cto", "TR Titres", AccountType.COMPTE_TITRES, bd("0"), List.of());
        when(trPort.fetchAccounts("plain-session")).thenReturn(List.of(accountData));

        Account existingAccount = Account.builder()
            .id(42L)
            .member(member)
            .name("TR Titres")
            .type(AccountType.COMPTE_TITRES)
            .provider("Trade Republic")
            .currency("EUR")
            .currentBalance(bd("1000"))
            .externalAccountId("tr_cto")
            .isManual(false)
            .build();
        when(accountRepository.findByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(Optional.of(existingAccount));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenAnswer(inv -> com.picsou.dto.AccountResponse.from(inv.getArgument(0), bd("0")));

        service.sync(memberId);

        // The empty exit snapshots after the deletion too, or the day's point would still be
        // costed with the positions that were just removed.
        InOrder inOrder = inOrder(holdingRepository, accountService);
        inOrder.verify(holdingRepository).deleteByAccountId(42L);
        inOrder.verify(accountService).upsertSnapshot(any(Account.class), eq(bd("0")), any());
        verify(holdingRepository).flush();
        verify(holdingRepository, never()).save(any(AccountHolding.class));
    }

    @Test
    void sync_keepsTheLastKnownPeaCash_whenTheAdapterDoesNotKnowIt() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .expiresAt(java.time.Instant.now().plusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");

        // cashEur null: the cash frame never arrived, or there is no cash account to ask.
        TrAccountData accountData = new TrAccountData(
            "tr_pea", "TR PEA", AccountType.PEA, bd("700"), List.of(), null);
        when(trPort.fetchAccounts("plain-session")).thenReturn(List.of(accountData));

        Account existingAccount = Account.builder()
            .id(42L)
            .member(member)
            .name("TR PEA")
            .type(AccountType.PEA)
            .provider("Trade Republic")
            .currency("EUR")
            .currentBalance(bd("650"))
            .cashBalance(bd("500"))
            .externalAccountId("tr_pea")
            .isManual(false)
            .build();
        when(accountRepository.findByExternalAccountIdAndMemberId("tr_pea", memberId))
            .thenReturn(Optional.of(existingAccount));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenAnswer(inv -> com.picsou.dto.AccountResponse.from(inv.getArgument(0), bd("700")));

        service.sync(memberId);

        // An unknown pocket is not an empty one: the last known 500 stays, as the holdings
        // stay when the portfolio answer errors.
        assertThat(existingAccount.getCashBalance()).isEqualByComparingTo("500");
        assertThat(existingAccount.getCurrentBalance()).isEqualByComparingTo("700");
    }

    // --- Session lifecycle: refresh instead of dying at the 2h heuristic ---

    @Test
    void resync_attemptsRefreshWhenExpired() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .refreshToken("enc-refresh")
            .expiresAt(java.time.Instant.now().minusSeconds(3600)) // past the heuristic window
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");
        when(encryption.decrypt("enc-refresh")).thenReturn("plain-refresh");
        when(encryption.encrypt(any(String.class))).thenAnswer(inv -> "enc:" + inv.getArgument(0));

        when(trPort.fetchAccounts("plain-session"))
            .thenThrow(new com.picsou.exception.SyncException("SESSION_EXPIRED"));
        when(trPort.refreshSession("plain-refresh"))
            .thenReturn(new TradeRepublicPort.TrTokens("new-session", "new-refresh"));
        when(trPort.fetchAccounts("new-session")).thenReturn(List.of());

        service.resyncIfSessionActive(memberId);

        verify(trPort).refreshSession("plain-refresh");
        verify(trPort).fetchAccounts("new-session");
        verify(sessionRepository).save(storedSession);
        verify(sessionRepository, never()).delete(any(TradeRepublicSession.class));
        assertThat(storedSession.getSessionToken()).isEqualTo("enc:new-session");
        assertThat(storedSession.getRefreshToken()).isEqualTo("enc:new-refresh");
    }

    @Test
    void refreshFailure_transient_keepsSession() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .refreshToken("enc-refresh")
            .expiresAt(java.time.Instant.now().minusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");
        when(encryption.decrypt("enc-refresh")).thenReturn("plain-refresh");

        when(trPort.fetchAccounts("plain-session"))
            .thenThrow(new com.picsou.exception.SyncException("SESSION_EXPIRED"));
        when(trPort.refreshSession("plain-refresh"))
            .thenThrow(new com.picsou.exception.SyncException(
                "Trade Republic authentication service is unavailable. Please make sure tr-auth is running on port 8001."));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.sync(memberId))
            .isInstanceOf(com.picsou.exception.SyncException.class)
            .hasMessageContaining("unavailable");

        verify(sessionRepository, never()).delete(any(TradeRepublicSession.class));
    }

    @Test
    void refreshFailure_expired_clearsSession() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .refreshToken("enc-refresh")
            .expiresAt(java.time.Instant.now().minusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");
        when(encryption.decrypt("enc-refresh")).thenReturn("plain-refresh");

        when(trPort.fetchAccounts("plain-session"))
            .thenThrow(new com.picsou.exception.SyncException("SESSION_EXPIRED"));
        when(trPort.refreshSession("plain-refresh"))
            .thenThrow(new com.picsou.exception.SyncException("SESSION_EXPIRED"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.sync(memberId))
            .isInstanceOf(com.picsou.exception.SyncException.class)
            .hasMessageContaining("reconnect");

        verify(sessionRepository).delete(storedSession);
    }

    @Test
    void getSessionStatus_activeWhenRefreshTokenPresent() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession expiredWithRefresh = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .refreshToken("enc-refresh")
            .expiresAt(java.time.Instant.now().minusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(expiredWithRefresh));
        assertThat(service.getSessionStatus(memberId).isActive()).isTrue();

        TradeRepublicSession expiredNoRefresh = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .expiresAt(java.time.Instant.now().minusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(expiredNoRefresh));
        assertThat(service.getSessionStatus(memberId).isActive()).isFalse();
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }
}
