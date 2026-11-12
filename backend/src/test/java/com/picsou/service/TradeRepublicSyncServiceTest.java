package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.adapter.OpenFigiIsinConverter.TickerResult;
import com.picsou.config.CryptoEncryption;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.FamilyMember;
import com.picsou.model.SidecarSession;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.port.SidecarErrorCode;
import com.picsou.adapter.TradeRepublicAdapter.TrSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.adapter.TradeRepublicAdapter;
import com.picsou.adapter.TradeRepublicAdapter.TrAccountData;
import com.picsou.adapter.TradeRepublicAdapter.TrPosition;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.SidecarSessionRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class TradeRepublicSyncServiceTest {

    @Mock TradeRepublicAdapter trPort;
    @Mock SidecarSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock OpenFigiIsinConverter isinConverter;
    @Mock SecurityIdentityService identityService;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock TransactionStatus transactionStatus;
    @Mock CategorizationService categorizationService;
    @Mock CategoryRepository categoryRepository;

    TradeRepublicSyncService service;

    private static final JsonNode CLAIMS = new ObjectMapper().createObjectNode().put("sub", "u1");

    @BeforeEach
    void setUp() {
        lenient().doAnswer(invocation -> ((TransactionCallback<Object>) invocation.getArgument(0))
            .doInTransaction(transactionStatus)).when(txTemplate).execute(any(TransactionCallback.class));
        lenient().doAnswer(invocation -> {
            ((Consumer<TransactionStatus>) invocation.getArgument(0)).accept(transactionStatus);
            return null;
        }).when(txTemplate).executeWithoutResult(any());
        service = new TradeRepublicSyncService(trPort, sessionRepository, accountRepository, holdingRepository,
            transactionRepository, familyMemberRepository, accountService, isinConverter, identityService,
            encryption, txTemplate, categorizationService, categoryRepository, Runnable::run);
    }

    /** An active stored session whose state decrypts to "plain-state", opening onto the cookie "cookie". */
    private SidecarSession arrangeSession(FamilyMember member) {
        SidecarSession session = SidecarSession.builder().bank(SidecarBank.TRADE_REPUBLIC).id(3L).member(member)
            .sessionState("encrypted").active(true).syncStatus(SidecarSyncStatus.IDLE).build();
        lenient().when(sessionRepository.findByBankAndMemberId(SidecarBank.TRADE_REPUBLIC, member.getId()))
            .thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.TRADE_REPUBLIC, member.getId()))
            .thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByIdAndMemberIdForUpdate(3L, member.getId()))
            .thenReturn(Optional.of(session));
        lenient().when(encryption.decrypt("encrypted")).thenReturn("plain-state");
        lenient().when(trPort.openSession("plain-state")).thenReturn(new TrSession("cookie", CLAIMS));
        lenient().when(familyMemberRepository.findById(member.getId())).thenReturn(Optional.of(member));
        return session;
    }

    @Test
    void resyncReporting_queuesTheImportOfAnActiveSession() {
        FamilyMember member = FamilyMember.builder().id(7L).displayName("Owner").build();
        SidecarSession session = arrangeSession(member);
        when(trPort.fetchAccounts("cookie", CLAIMS)).thenReturn(List.of());

        var result = service.resyncReporting(7L);

        assertThat(result.source()).isEqualTo("trade-republic");
        assertThat(result.status()).isEqualTo(com.picsou.service.sync.SourceSyncResult.Status.QUEUED);
        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
    }

    @Test
    void resyncReporting_skipsAMemberWithoutASession() {
        when(sessionRepository.findByBankAndMemberId(SidecarBank.TRADE_REPUBLIC, 7L)).thenReturn(Optional.empty());

        assertThat(service.resyncReporting(7L).status())
            .isEqualTo(com.picsou.service.sync.SourceSyncResult.Status.SKIPPED_NOT_CONNECTED);
        verify(trPort, never()).openSession(anyString());
    }

    @Test
    void resyncReporting_asksAnExpiredSessionToReconnect() {
        FamilyMember member = FamilyMember.builder().id(7L).displayName("Owner").build();
        SidecarSession session = arrangeSession(member);
        session.markQueued();
        session.markFailed(SidecarErrorCode.SESSION_EXPIRED, java.time.Instant.now());

        var result = service.resyncReporting(7L);

        assertThat(result.status()).isEqualTo(com.picsou.service.sync.SourceSyncResult.Status.NEEDS_REAUTH);
        assertThat(result.message()).isEqualTo("SESSION_EXPIRED");
    }

    @Test
    void resyncReporting_databaseFailureIsReportedWithoutLeakingItsMessage() {
        RuntimeException failure = new RuntimeException("private SQL password=private-marker");
        when(sessionRepository.findByBankAndMemberId(SidecarBank.TRADE_REPUBLIC, 7L)).thenThrow(failure);

        var result = service.resyncReporting(7L);

        assertThat(result.status()).isEqualTo(com.picsou.service.sync.SourceSyncResult.Status.FAILED);
        assertThat(result.message()).isEqualTo("Unexpected sync error").doesNotContain("private-marker");
    }

    @Test
    void clearSessionReportsTheSessionItDeleted() {
        FamilyMember member = FamilyMember.builder().id(7L).displayName("Owner").build();
        SidecarSession session = arrangeSession(member);

        assertThat(service.clearSession(7L)).isTrue();
        verify(sessionRepository).findByBankAndMemberIdForUpdate(SidecarBank.TRADE_REPUBLIC, 7L);
        verify(sessionRepository).delete(session);
    }

    @Test
    void clearSessionReportsNothingWhenNoSessionIsStored() {
        when(sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.TRADE_REPUBLIC, 7L))
            .thenReturn(Optional.empty());

        assertThat(service.clearSession(7L)).isFalse();
        verify(sessionRepository, never()).delete(any());
    }

    @Test
    void initiateAuth_relaysTheFlowAndTheCountdown() {
        when(trPort.initiateAuth("+33600000000", "1234"))
            .thenReturn(new TradeRepublicAdapter.TrInitiate("p1", "confirmInApp", 120));

        var response = service.initiateAuth("+33600000000", "1234");

        assertThat(response).isEqualTo(new TradeRepublicSyncService.AuthInitResponse("p1", "confirmInApp", 120));
    }

    @Test
    void completeAuth_storesTheSessionLiftsTombstonesAndQueuesTheFirstImport() {
        FamilyMember member = FamilyMember.builder().id(7L).displayName("Owner").build();
        when(trPort.completeAuth("p1", null)).thenReturn("plain-state");
        when(encryption.encrypt("plain-state")).thenReturn("encrypted");
        when(familyMemberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.TRADE_REPUBLIC, 7L))
            .thenReturn(Optional.empty());
        ArgumentCaptor<SidecarSession> saved = ArgumentCaptor.forClass(SidecarSession.class);
        when(sessionRepository.saveAndFlush(saved.capture())).thenAnswer(invocation -> {
            SidecarSession session = invocation.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(session, "id", 3L);
            return session;
        });
        when(sessionRepository.findByIdAndMemberIdForUpdate(3L, 7L)).thenAnswer(invocation -> Optional.of(saved.getValue()));
        when(sessionRepository.findByBankAndMemberId(SidecarBank.TRADE_REPUBLIC, 7L))
            .thenAnswer(invocation -> Optional.of(saved.getValue()));
        when(trPort.openSession("plain-state")).thenReturn(new TrSession("cookie", CLAIMS));
        when(trPort.fetchAccounts("cookie", CLAIMS)).thenReturn(List.of());

        var status = service.completeAuth("p1", null, 7L);

        verify(accountRepository).restoreSoftDeletedTrAccounts(7L);
        assertThat(saved.getValue().getBank()).isEqualTo(SidecarBank.TRADE_REPUBLIC);
        assertThat(saved.getValue().getSessionState()).isEqualTo("encrypted");
        assertThat(status.isActive()).isTrue();
        assertThat(status.syncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
    }

    @Test
    void completeAuth_aDisconnectDuringTheWaitStoresNothing() {
        // The member clicks Disconnect while the in-app confirmation is still pending.
        when(trPort.completeAuth("p1", null)).thenAnswer(invocation -> {
            service.clearSession(7L);
            return "plain-state";
        });

        assertThatThrownBy(() -> service.completeAuth("p1", null, 7L))
            .isInstanceOfSatisfying(SyncException.class,
                ex -> assertThat(ex.getCode()).isEqualTo("AUTH_ATTEMPT_EXPIRED"));

        verify(sessionRepository, never()).saveAndFlush(any());
        verify(accountRepository, never()).restoreSoftDeletedTrAccounts(anyLong());
        verify(trPort, never()).fetchAccounts(any(), any());
    }

    @Test
    void completeAuth_aDisconnectBeforeANewLoginDoesNotBlockIt() {
        service.clearSession(7L);
        when(trPort.completeAuth("p1", null)).thenReturn("plain-state");
        when(encryption.encrypt("plain-state")).thenReturn("encrypted");
        when(familyMemberRepository.findById(7L))
            .thenReturn(Optional.of(FamilyMember.builder().id(7L).displayName("Owner").build()));
        when(sessionRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            SidecarSession session = invocation.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(session, "id", 3L);
            return session;
        });

        service.completeAuth("p1", null, 7L);

        verify(sessionRepository).saveAndFlush(any());
    }

    @Test
    void completeAuth_aRejectedSecondFactorStoresNothing() {
        when(trPort.completeAuth("p1", "1234"))
            .thenThrow(new SyncException("rejected", null, "INVALID_OTP"));

        assertThatThrownBy(() -> service.completeAuth("p1", "1234", 7L)).isInstanceOf(SyncException.class);

        verify(sessionRepository, never()).saveAndFlush(any());
        verify(accountRepository, never()).restoreSoftDeletedTrAccounts(anyLong());
    }

    @Test
    void getStatus_isInactiveWithoutASession() {
        when(sessionRepository.findByBankAndMemberId(SidecarBank.TRADE_REPUBLIC, 7L)).thenReturn(Optional.empty());

        var status = service.getStatus(7L);

        assertThat(status.isActive()).isFalse();
        assertThat(status.syncStatus()).isEqualTo(SidecarSyncStatus.IDLE);
    }

    // --- Stored phone: masked in the status, read back for the one-field reconnect ---

    private static final String STATE_WITH_PHONE =
        "{\"refresh\":\"r\",\"device\":\"d\",\"sub\":\"s\",\"phone\":\"+33612345699\"}";

    @Test
    void getStatus_masksTheStoredPhoneKeepingThePrefixAndTheLastTwoDigits() {
        FamilyMember member = FamilyMember.builder().id(7L).displayName("Owner").build();
        arrangeSession(member);
        when(encryption.decrypt("encrypted")).thenReturn(STATE_WITH_PHONE);

        var status = service.getStatus(7L);

        assertThat(status.maskedPhone()).isEqualTo("+33 * ** ** ** 99");
        assertThat(status.maskedPhone()).doesNotContain("1234");
    }

    @Test
    void getStatus_hasNoMaskedPhoneForAStateStoredBeforeThePhoneWasKept() {
        FamilyMember member = FamilyMember.builder().id(7L).displayName("Owner").build();
        arrangeSession(member);
        when(encryption.decrypt("encrypted")).thenReturn("{\"refresh\":\"r\",\"device\":\"d\",\"sub\":\"s\"}");

        assertThat(service.getStatus(7L).maskedPhone()).isNull();
        assertThat(service.storedPhone(7L)).isEmpty();
    }

    @Test
    void getStatus_toleratesAStateThatIsNotJsonOrCannotBeDecrypted() {
        FamilyMember member = FamilyMember.builder().id(7L).displayName("Owner").build();
        arrangeSession(member);
        when(encryption.decrypt("encrypted")).thenReturn("not json {");
        assertThat(service.getStatus(7L).maskedPhone()).isNull();

        when(encryption.decrypt("encrypted")).thenThrow(new IllegalStateException("bad tag"));
        var status = service.getStatus(7L);
        assertThat(status.isActive()).isTrue();
        assertThat(status.maskedPhone()).isNull();
    }

    @Test
    void maskPhone_neverRevealsMoreThanTheLastTwoDigits() {
        assertThat(TradeRepublicSyncService.maskPhone("+4915112345678").replaceAll("[^0-9]", "")).isEqualTo("4978");
        assertThat(TradeRepublicSyncService.maskPhone("0612345699")).isNull();
        assertThat(TradeRepublicSyncService.maskPhone(null)).isNull();
    }

    @Test
    void maskPhone_ignoresSeparatorsLeftInAnOlderStoredState() {
        assertThat(TradeRepublicSyncService.maskPhone("+33 6 12 34 56 99")).isEqualTo("+33 * ** ** ** 99");
        assertThat(TradeRepublicSyncService.maskPhone("+33-6-12-34-56-99")).isEqualTo("+33 * ** ** ** 99");
    }

    @Test
    void storedPhone_isReadFromADeactivatedSessionToo() {
        FamilyMember member = FamilyMember.builder().id(7L).displayName("Owner").build();
        SidecarSession session = arrangeSession(member);
        session.markQueued();
        session.markFailed(SidecarErrorCode.SESSION_EXPIRED, java.time.Instant.now());
        when(encryption.decrypt("encrypted")).thenReturn(STATE_WITH_PHONE);

        assertThat(session.isActive()).isFalse();
        assertThat(service.storedPhone(7L)).contains("+33612345699");
        assertThat(service.getStatus(7L).maskedPhone()).isEqualTo("+33 * ** ** ** 99");
    }

    @Test
    void storedPhone_isEmptyWithoutASession() {
        when(sessionRepository.findByBankAndMemberId(SidecarBank.TRADE_REPUBLIC, 7L)).thenReturn(Optional.empty());

        assertThat(service.storedPhone(7L)).isEmpty();
    }

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

        arrangeSession(member);

        TrPosition pos1 = new TrPosition("IE00ISIN_A", bd("2"), bd("10"), bd("100"));
        TrPosition pos2 = new TrPosition("IE00ISIN_B", bd("3"), bd("20"), bd("110"));
        TrAccountData accountData = new TrAccountData(
            "tr_cto", "TR Titres", AccountType.COMPTE_TITRES, bd("530"), List.of(pos1, pos2));
        when(trPort.fetchAccounts("cookie", CLAIMS)).thenReturn(List.of(accountData));

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

        service.queueSync(memberId);

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
     * OpenFIGI can take seconds per ISIN, so the tickers are resolved while fetching, before the
     * commit transaction takes the session row lock: no resolve() runs inside a transaction.
     */
    @Test
    void sync_resolvesIsinsBeforeTheCommitTransaction() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        arrangeSession(member);
        TrPosition pos = new TrPosition("IE00ISIN_A", bd("2"), bd("10"), bd("100"));
        when(trPort.fetchAccounts("cookie", CLAIMS)).thenReturn(List.of(new TrAccountData(
            "tr_cto", "TR Titres", AccountType.COMPTE_TITRES, bd("200"), List.of(pos))));
        boolean[] inTransaction = {false};
        doAnswer(invocation -> {
            inTransaction[0] = true;
            try {
                return ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(transactionStatus);
            } finally {
                inTransaction[0] = false;
            }
        }).when(txTemplate).execute(any(TransactionCallback.class));
        List<Boolean> resolvedInTransaction = new java.util.ArrayList<>();
        when(isinConverter.resolve("IE00ISIN_A")).thenAnswer(inv -> {
            resolvedInTransaction.add(inTransaction[0]);
            return new TickerResult("RKLB", "Rocket Lab");
        });
        when(accountRepository.findByExternalAccountIdAndMemberId("tr_cto", memberId)).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });

        service.queueSync(memberId);

        assertThat(resolvedInTransaction).containsExactly(false);
        ArgumentCaptor<AccountHolding> captor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository).save(captor.capture());
        assertThat(captor.getValue().getTicker()).isEqualTo("RKLB");
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

        arrangeSession(member);

        TrPosition posA = new TrPosition("IE00ISIN_A", bd("2"), bd("10"), bd("100"));
        TrPosition posB = new TrPosition("IE00ISIN_B", bd("1"), bd("50"), bd("100"));
        TrAccountData accountData = new TrAccountData(
            "tr_pea", "TR PEA", AccountType.PEA, bd("700"), List.of(posA, posB), bd("500"));
        when(trPort.fetchAccounts("cookie", CLAIMS)).thenReturn(List.of(accountData));
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

        service.queueSync(memberId);

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

        arrangeSession(member);

        TrPosition unpriceable = new TrPosition("IE000BI8OT95", bd("10"), bd("80"), bd("84"));
        TrAccountData accountData = new TrAccountData(
            "tr_cto", "TR Titres", AccountType.COMPTE_TITRES, bd("840"), List.of(unpriceable));
        when(trPort.fetchAccounts("cookie", CLAIMS)).thenReturn(List.of(accountData));
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

        service.queueSync(memberId);

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

        arrangeSession(member);

        TrPosition noPrice = new TrPosition("IE000BI8OT95", bd("10"), bd("80"), bd("0"));
        TrAccountData accountData = new TrAccountData(
            "tr_cto", "TR Titres", AccountType.COMPTE_TITRES, bd("800"), List.of(noPrice));
        when(trPort.fetchAccounts("cookie", CLAIMS)).thenReturn(List.of(accountData));
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

        service.queueSync(memberId);

        ArgumentCaptor<AccountHolding> captor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository).save(captor.capture());

        assertThat(captor.getValue().getProviderValueEur()).isEqualByComparingTo("800"); // 10 × 80
    }

    @Test
    void sync_deletesOldHoldingsWhenPortfolioReturnsEmpty() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        arrangeSession(member);

        TrAccountData accountData = new TrAccountData(
            "tr_cto", "TR Titres", AccountType.COMPTE_TITRES, bd("0"), List.of());
        when(trPort.fetchAccounts("cookie", CLAIMS)).thenReturn(List.of(accountData));

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

        service.queueSync(memberId);

        // The empty exit snapshots after the deletion too, or the day's point would still be
        // costed with the positions that were just removed.
        InOrder inOrder = inOrder(holdingRepository, accountService);
        inOrder.verify(holdingRepository).deleteByAccountId(42L);
        inOrder.verify(accountService).upsertSnapshot(any(Account.class), eq(bd("0")), any());
        verify(holdingRepository).flush();
        verify(holdingRepository, never()).save(any(AccountHolding.class));
    }

    // ─── CSV transaction import ────────────────────────────────────────────────

    private static final Long MEMBER = 7L;

    private Account trCash() {
        return Account.builder().id(1L).name("TR Cash").type(AccountType.CHECKING)
            .provider("Trade Republic").currency("EUR").build();
    }

    private Account trPea() {
        return Account.builder().id(2L).name("TR PEA").type(AccountType.PEA)
            .provider("Trade Republic").currency("EUR").build();
    }

    /** Seeded TRANSFER category used to tag the investment leg (kept out of cashflow). */
    private Category investTransferCategory() {
        return Category.builder().id(9L).name("Investissement").slug("investissement")
            .kind(CategoryKind.TRANSFER).build();
    }

    /** EXPENSE category used to tag the cash leg of a buy/sell (counts as spending). */
    private Category investPurchaseCategory() {
        return Category.builder().id(10L).name("Investissement").slug("investissement-titres")
            .kind(CategoryKind.EXPENSE).build();
    }

    /** Category map as a member who already has both categories would resolve. */
    private Map<String, Category> categoriesWithBoth(Category transfer, Category purchase) {
        return Map.of("investissement", transfer, "investissement-titres", purchase);
    }

    /** Builds one 19-column TR CSV data row with the fields the parser reads at their real indices. */
    private static String row(String date, String accountType, String category, String type,
                              String stock, String amount, String description, String externalId) {
        String[] cols = new String[19];
        Arrays.fill(cols, "");
        cols[1] = date;
        cols[2] = accountType;
        cols[3] = category;
        cols[4] = type;
        cols[6] = stock;
        // Quote the free-text/amount fields the way a real TR export does — the amount uses a
        // decimal comma, so it must be quoted to survive comma-delimited splitting.
        cols[10] = '"' + amount + '"';
        cols[17] = '"' + description + '"';
        cols[18] = externalId;
        return String.join(",", cols);
    }

    private static MockMultipartFile csv(String... dataRows) {
        String header = "datetime" + ",".repeat(18); // first line contains "datetime" → skipped as header
        String body = header + "\n" + String.join("\n", dataRows) + "\n";
        return new MockMultipartFile("file", "tr.csv", "text/csv", body.getBytes(StandardCharsets.UTF_8));
    }

    private void stubTrAccounts(Account... accounts) {
        when(accountRepository.findByIdAndMemberId(1L, MEMBER)).thenReturn(Optional.of(accounts[0]));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER)).thenReturn(List.of(accounts));
    }

    /**
     * A BUY on the PEA produces a mirrored double entry: cash leg WITHDRAWAL (−amount) on TR Cash,
     * investment leg DEPOSIT (+amount) on TR PEA. The cash leg is booked under the "Investissement"
     * EXPENSE category so cashflow counts the purchase as spending; only the investment leg is
     * tagged TRANSFER, which keeps the same movement from being counted a second time (as income).
     */
    @Test
    void importTransactions_buyOnPea_cashLegIsExpense_investmentLegIsTransfer() {
        Account cash = trCash();
        Account pea = trPea();
        Category transfer = investTransferCategory();
        Category purchase = investPurchaseCategory();
        stubTrAccounts(cash, pea);
        when(categorizationService.categoriesBySlug(MEMBER)).thenReturn(categoriesWithBoth(transfer, purchase));
        when(transactionRepository.existsByAccountIdAndExternalId(anyLong(), anyString())).thenReturn(false);

        // FR decimal comma exercised on purpose; BUY amount is negative in the TR export.
        var file = csv(row("2024-03-01", "PEA", "TRADING", "BUY", "S&P 500", "-59,31", "Buy S&P 500", "ext-1"));

        TradeRepublicSyncService.ImportResult result = service.importTransactionsCsv(1L, file, MEMBER);

        assertThat(result.inserted()).isEqualTo(2);
        assertThat(result.skipped()).isZero();

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(2)).save(captor.capture());
        List<Transaction> saved = captor.getAllValues();

        Transaction cashLeg = saved.stream().filter(t -> t.getAccount() == cash).findFirst().orElseThrow();
        assertThat(cashLeg.getAmount()).isEqualByComparingTo("-59.31");
        assertThat(cashLeg.getTxType()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(cashLeg.getExternalId()).isEqualTo("ext-1_cash");
        // EXPENSE "Investissement" → cashflow counts the buy as spending.
        assertThat(cashLeg.getCategoryRef()).isEqualTo(purchase);

        Transaction invLeg = saved.stream().filter(t -> t.getAccount() == pea).findFirst().orElseThrow();
        assertThat(invLeg.getAmount()).isEqualByComparingTo("59.31");
        assertThat(invLeg.getTxType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(invLeg.getExternalId()).isEqualTo("ext-1_inv");
        // TRANSFER-kind → excluded from cashflow so the movement is not counted twice.
        assertThat(invLeg.getCategoryRef()).isEqualTo(transfer);
        // Never books BUY/SELL as tx_type, so HoldingComputeService ignores these rows.
        assertThat(invLeg.getTxType()).isNotIn(TransactionType.BUY, TransactionType.SELL);
    }

    /** A SELL mirrors the other way: cash DEPOSIT (+), investment WITHDRAWAL (−). */
    @Test
    void importTransactions_sellOnPea_mirrorsSigns() {
        Account cash = trCash();
        Account pea = trPea();
        stubTrAccounts(cash, pea);
        when(categorizationService.categoriesBySlug(MEMBER))
            .thenReturn(categoriesWithBoth(investTransferCategory(), investPurchaseCategory()));
        when(transactionRepository.existsByAccountIdAndExternalId(anyLong(), anyString())).thenReturn(false);

        var file = csv(row("2024-03-02", "PEA", "TRADING", "SELL", "Dell", "72.72", "Sell Dell", "ext-2"));

        service.importTransactionsCsv(1L, file, MEMBER);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(2)).save(captor.capture());
        Transaction cashLeg = captor.getAllValues().stream().filter(t -> t.getAccount() == cash).findFirst().orElseThrow();
        Transaction invLeg = captor.getAllValues().stream().filter(t -> t.getAccount() == pea).findFirst().orElseThrow();
        assertThat(cashLeg.getAmount()).isEqualByComparingTo("72.72");
        assertThat(cashLeg.getTxType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(invLeg.getAmount()).isEqualByComparingTo("-72.72");
        assertThat(invLeg.getTxType()).isEqualTo(TransactionType.WITHDRAWAL);
    }

    /** A plain CASH row (e.g. a card payment) is a single cash leg, left uncategorized so cashflow counts it. */
    @Test
    void importTransactions_cashRow_singleUncategorizedLeg() {
        Account cash = trCash();
        stubTrAccounts(cash);
        when(categorizationService.categoriesBySlug(MEMBER)).thenReturn(Map.of("investissement", investTransferCategory()));
        when(transactionRepository.existsByAccountIdAndExternalId(anyLong(), anyString())).thenReturn(false);

        var file = csv(row("2024-03-03", "DEFAULT", "CASH", "CARD_PAYMENT", "", "-12.50", "Coffee", "ext-3"));

        TradeRepublicSyncService.ImportResult result = service.importTransactionsCsv(1L, file, MEMBER);

        assertThat(result.inserted()).isEqualTo(1);
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(1)).save(captor.capture());
        Transaction leg = captor.getValue();
        assertThat(leg.getAccount()).isEqualTo(cash);
        assertThat(leg.getTxType()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(leg.getCategoryRef()).isNull();
    }

    /** TRANSFER_IN / TRANSFER_OUT rows are ignored (already covered by the TRADING double-entry). */
    @Test
    void importTransactions_transferRows_ignored() {
        stubTrAccounts(trCash(), trPea());
        when(categorizationService.categoriesBySlug(MEMBER)).thenReturn(Map.of("investissement", investTransferCategory()));

        var file = csv(
            row("2024-03-04", "PEA", "TRANSFER", "TRANSFER_IN", "", "100.00", "Top-up", "ext-4"),
            row("2024-03-05", "PEA", "TRANSFER", "TRANSFER_OUT", "", "-100.00", "Top-up", "ext-5"));

        TradeRepublicSyncService.ImportResult result = service.importTransactionsCsv(1L, file, MEMBER);

        assertThat(result.inserted()).isZero();
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    /** Re-importing the same CSV inserts nothing and counts the row as skipped. */
    @Test
    void importTransactions_reimport_skipsExisting() {
        stubTrAccounts(trCash(), trPea());
        when(categorizationService.categoriesBySlug(MEMBER)).thenReturn(Map.of("investissement", investTransferCategory()));
        when(transactionRepository.existsByAccountIdAndExternalId(anyLong(), anyString())).thenReturn(true);

        var file = csv(row("2024-03-01", "PEA", "TRADING", "BUY", "S&P 500", "-59,31", "Buy", "ext-1"));

        TradeRepublicSyncService.ImportResult result = service.importTransactionsCsv(1L, file, MEMBER);

        assertThat(result.inserted()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    /** The triggering {id} must belong to the member — a spoofed id is rejected before any work. */
    @Test
    void importTransactions_unauthorizedAccount_throws() {
        when(accountRepository.findByIdAndMemberId(999L, MEMBER)).thenReturn(Optional.empty());

        var file = csv(row("2024-03-01", "PEA", "TRADING", "BUY", "S&P 500", "-59,31", "Buy", "ext-1"));

        assertThatThrownBy(() -> service.importTransactionsCsv(999L, file, MEMBER))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    /**
     * A member who predates the "investissement-titres" category (existing members are never
     * re-seeded) gets it created on demand on their first TRADING row, and the cash leg is booked
     * under it — so the purchase shows as an "Investissement" expense.
     */
    @Test
    void importTransactions_missingExpenseCategory_createsItOnDemand() {
        Account cash = trCash();
        Account pea = trPea();
        stubTrAccounts(cash, pea);
        // Map has the transfer category but NOT the purchase one → it must be created.
        when(categorizationService.categoriesBySlug(MEMBER))
            .thenReturn(Map.of("investissement", investTransferCategory()));
        when(transactionRepository.existsByAccountIdAndExternalId(anyLong(), anyString())).thenReturn(false);
        when(familyMemberRepository.getReferenceById(MEMBER))
            .thenReturn(FamilyMember.builder().id(MEMBER).build());
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> {
            Category c = inv.getArgument(0);
            c.setId(100L);
            return c;
        });

        var file = csv(row("2024-03-01", "PEA", "TRADING", "BUY", "S&P 500", "-59,31", "Buy", "ext-1"));

        service.importTransactionsCsv(1L, file, MEMBER);

        ArgumentCaptor<Category> catCaptor = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository).save(catCaptor.capture());
        Category created = catCaptor.getValue();
        assertThat(created.getSlug()).isEqualTo("investissement-titres");
        assertThat(created.getKind()).isEqualTo(CategoryKind.EXPENSE);
        assertThat(created.getName()).isEqualTo("Investissement");

        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(2)).save(txCaptor.capture());
        Transaction cashLeg = txCaptor.getAllValues().stream()
            .filter(t -> t.getAccount() == cash).findFirst().orElseThrow();
        assertThat(cashLeg.getCategoryRef()).isSameAs(created);
    }

    @Test
    void sync_keepsTheLastKnownPeaCash_whenTheAdapterDoesNotKnowIt() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        arrangeSession(member);

        // cashEur null: the cash frame never arrived, or there is no cash account to ask.
        TrAccountData accountData = new TrAccountData(
            "tr_pea", "TR PEA", AccountType.PEA, bd("700"), List.of(), null);
        when(trPort.fetchAccounts("cookie", CLAIMS)).thenReturn(List.of(accountData));

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

        service.queueSync(memberId);

        // An unknown pocket is not an empty one: the last known 500 stays, as the holdings
        // stay when the portfolio answer errors.
        assertThat(existingAccount.getCashBalance()).isEqualByComparingTo("500");
        assertThat(existingAccount.getCurrentBalance()).isEqualByComparingTo("700");
    }

    // --- Session lifecycle: the sidecar renews the cookie, a rejected refresh ends the session ---

    @Test
    void sync_aSessionTheSidecarReportsExpiredIsDeactivatedNotDeleted() {
        FamilyMember member = FamilyMember.builder().id(7L).displayName("Owner").build();
        SidecarSession session = arrangeSession(member);
        when(trPort.openSession("plain-state"))
            .thenThrow(new SyncException("expired", null, "SESSION_EXPIRED"));

        var status = service.queueSync(7L);

        assertThat(status.isActive()).isFalse();
        assertThat(status.syncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(status.lastSyncError()).isEqualTo(SidecarErrorCode.SESSION_EXPIRED);
        verify(sessionRepository, never()).delete(any(SidecarSession.class));
        verify(trPort, never()).fetchAccounts(anyString(), any());
        assertThat(session.isActive()).isFalse();
    }

    @Test
    void sync_aTransientSidecarFailureKeepsTheSessionConnected() {
        FamilyMember member = FamilyMember.builder().id(7L).displayName("Owner").build();
        SidecarSession session = arrangeSession(member);
        when(trPort.openSession("plain-state"))
            .thenThrow(new SyncException("down", null, "UPSTREAM_UNAVAILABLE"));

        var status = service.queueSync(7L);

        assertThat(status.isActive()).isTrue();
        assertThat(status.syncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(status.lastSyncError()).isEqualTo(SidecarErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(session.isActive()).isTrue();
    }

    @Test
    void sync_anUnreadableStoredStateDeactivatesTheSession() {
        FamilyMember member = FamilyMember.builder().id(7L).displayName("Owner").build();
        arrangeSession(member);
        when(encryption.decrypt("encrypted")).thenThrow(new IllegalStateException("bad tag"));

        assertThatThrownBy(() -> service.queueSync(7L))
            .isInstanceOf(SyncException.class)
            .extracting(ex -> ((SyncException) ex).getCode())
            .isEqualTo("INVALID_SESSION_STATE");
        verify(trPort, never()).openSession(anyString());
    }

    // --- Automatic transaction import during the sync ---

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Europe/Paris"));

    private static String csvText(String... dataRows) {
        return "datetime" + ",".repeat(18) + "\n" + String.join("\n", dataRows) + "\n";
    }

    private FamilyMember syncMember() {
        FamilyMember member = FamilyMember.builder().id(MEMBER).displayName("Owner").build();
        arrangeSession(member);
        return member;
    }

    @Test
    void sync_aFirstImportAsksForThreeYearsOfTransactions() {
        syncMember();
        when(trPort.fetchAccounts("cookie", CLAIMS)).thenReturn(List.of());
        when(trPort.fetchTransactionsCsv(anyString(), any(), any())).thenReturn(csvText());

        service.queueSync(MEMBER);

        verify(trPort).fetchTransactionsCsv("plain-state", TODAY.minusYears(3), TODAY);
    }

    @Test
    void sync_aLaterImportRereadsTheLastTwoWeeksBeforeTheNewestImportedEntry() {
        syncMember();
        Account cash = trCash();
        when(trPort.fetchAccounts("cookie", CLAIMS)).thenReturn(List.of());
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER)).thenReturn(List.of(cash));
        when(transactionRepository.findLatestSyncedDateByAccountId(1L)).thenReturn(TODAY.minusDays(7));
        when(trPort.fetchTransactionsCsv(anyString(), any(), any())).thenReturn(csvText());

        service.queueSync(MEMBER);

        verify(trPort).fetchTransactionsCsv("plain-state", TODAY.minusDays(21), TODAY);
    }

    @Test
    void sync_aNewestEntryOlderThanFiveYearsAsksForTheLongestWindowTheSidecarAccepts() {
        syncMember();
        Account cash = trCash();
        when(trPort.fetchAccounts("cookie", CLAIMS)).thenReturn(List.of());
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER)).thenReturn(List.of(cash));
        when(transactionRepository.findLatestSyncedDateByAccountId(1L)).thenReturn(TODAY.minusYears(6));
        when(trPort.fetchTransactionsCsv(anyString(), any(), any())).thenReturn(csvText());

        service.queueSync(MEMBER);

        verify(trPort).fetchTransactionsCsv("plain-state", TODAY.minusDays(1830), TODAY);
    }

    @Test
    void importTransactions_leavesOutAndCountsRowsTheExportStatesInAnotherCurrency() {
        Account cash = trCash();
        stubTrAccounts(cash);
        when(categorizationService.categoriesBySlug(MEMBER)).thenReturn(Map.of("investissement", investTransferCategory()));
        // The importer finds both columns by their header name; these indices are free slots.
        String[] header = new String[19];
        Arrays.fill(header, "");
        header[0] = "datetime";
        header[12] = "currency";
        header[14] = "original_currency";
        String eur = row("2026-10-02", "DEFAULT", "CASH", "CARD_PAYMENT", "", "-12.50", "Coffee", "ext-eur");
        String usd = row("2026-10-03", "DEFAULT", "CASH", "CARD_PAYMENT", "", "-20.00", "Diner", "ext-usd");
        String abroad = row("2026-10-04", "DEFAULT", "CASH", "CARD_PAYMENT", "", "-9.10", "Taxi", "ext-abroad");
        String blank = row("2026-10-05", "DEFAULT", "CASH", "INTEREST", "", "1.00", "Interest", "ext-blank");
        String body = String.join(",", header).toUpperCase() + "\n"
            + withColumn(eur, 12, "eur") + "\n"
            + withColumn(usd, 12, "USD") + "\n"
            + withColumn(withColumn(abroad, 12, "EUR"), 14, "USD") + "\n"
            + blank + "\n";
        var file = new MockMultipartFile("file", "tr.csv", "text/csv", body.getBytes(StandardCharsets.UTF_8));

        TradeRepublicSyncService.ImportResult result = service.importTransactionsCsv(1L, file, MEMBER);

        assertThat(result.inserted()).isEqualTo(3);
        assertThat(result.nonEur()).isEqualTo(1);
        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(3)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(Transaction::getExternalId)
            .containsExactlyInAnyOrder("ext-eur_cash", "ext-abroad_cash", "ext-blank_cash");
    }

    private static String withColumn(String row, int index, String value) {
        List<String> cols = new ArrayList<>(Arrays.asList(row.split(",", -1)));
        cols.set(index, value);
        return String.join(",", cols);
    }

    @Test
    void sync_importsTheExportIntoTrCashAndTheInvestmentAccount() {
        SidecarSession session = arrangeSession(FamilyMember.builder().id(MEMBER).displayName("Owner").build());
        Account cash = trCash();
        Account pea = trPea();
        when(trPort.fetchAccounts("cookie", CLAIMS)).thenReturn(List.of());
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER)).thenReturn(List.of(cash, pea));
        when(categorizationService.categoriesBySlug(MEMBER))
            .thenReturn(categoriesWithBoth(investTransferCategory(), investPurchaseCategory()));
        when(trPort.fetchTransactionsCsv(anyString(), any(), any())).thenReturn(csvText(
            row("2026-10-01", "PEA", "TRADING", "BUY", "S&P 500", "-59,31", "Buy", "ext-1"),
            row("2026-10-02", "DEFAULT", "CASH", "CARD_PAYMENT", "", "-12.50", "Coffee", "ext-2")));

        service.queueSync(MEMBER);

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(3)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(Transaction::getExternalId)
            .containsExactlyInAnyOrder("ext-1_cash", "ext-1_inv", "ext-2_cash");
        assertThat(saved.getAllValues()).filteredOn(t -> t.getExternalId().endsWith("_inv"))
            .singleElement().satisfies(t -> assertThat(t.getAccount()).isSameAs(pea));
        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
    }

    @Test
    void sync_aSecondIdenticalImportInsertsNothing() {
        syncMember();
        when(trPort.fetchAccounts("cookie", CLAIMS)).thenReturn(List.of());
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER)).thenReturn(List.of(trCash(), trPea()));
        when(categorizationService.categoriesBySlug(MEMBER))
            .thenReturn(categoriesWithBoth(investTransferCategory(), investPurchaseCategory()));
        when(trPort.fetchTransactionsCsv(anyString(), any(), any())).thenReturn(csvText(
            row("2026-10-01", "PEA", "TRADING", "BUY", "S&P 500", "-59,31", "Buy", "ext-1")));
        Set<String> stored = new HashSet<>();
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction tx = inv.getArgument(0);
            stored.add(tx.getAccount().getId() + ":" + tx.getExternalId());
            return tx;
        });
        when(transactionRepository.existsByAccountIdAndExternalId(anyLong(), anyString()))
            .thenAnswer(inv -> stored.contains(inv.getArgument(0) + ":" + inv.getArgument(1)));

        service.queueSync(MEMBER);
        service.queueSync(MEMBER);

        verify(transactionRepository, times(2)).save(any(Transaction.class));
        assertThat(stored).containsExactlyInAnyOrder("1:ext-1_cash", "2:ext-1_inv");
    }

    @Test
    void sync_aFailedExportKeepsTheCommittedAccountsAndTheSyncSuccessful() {
        SidecarSession session = arrangeSession(FamilyMember.builder().id(MEMBER).displayName("Owner").build());
        stubOneSyncedAccount();
        when(trPort.fetchTransactionsCsv(anyString(), any(), any()))
            .thenThrow(new SyncException("down", null, "UPSTREAM_UNAVAILABLE"));

        service.queueSync(MEMBER);

        verify(accountRepository).save(any(Account.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
    }

    @Test
    void sync_anExportTheImporterCannotWriteKeepsTheCommittedAccountsAndTheSyncSuccessful() {
        SidecarSession session = arrangeSession(FamilyMember.builder().id(MEMBER).displayName("Owner").build());
        stubOneSyncedAccount();
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER)).thenReturn(List.of(trCash()));
        when(categorizationService.categoriesBySlug(MEMBER)).thenReturn(Map.of("investissement", investTransferCategory()));
        when(trPort.fetchTransactionsCsv(anyString(), any(), any())).thenReturn(csvText(
            row("2026-10-02", "DEFAULT", "CASH", "CARD_PAYMENT", "", "-12.50", "Coffee", "ext-2")));
        when(transactionRepository.save(any(Transaction.class))).thenThrow(new IllegalStateException("boom"));

        service.queueSync(MEMBER);

        verify(accountRepository).save(any(Account.class));
        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
    }

    @Test
    void sync_anExpiredSessionOnTheExportStillFailsTheSync() {
        SidecarSession session = arrangeSession(FamilyMember.builder().id(MEMBER).displayName("Owner").build());
        when(trPort.fetchAccounts("cookie", CLAIMS)).thenReturn(List.of());
        when(trPort.fetchTransactionsCsv(anyString(), any(), any()))
            .thenThrow(new SyncException("expired", null, "SESSION_EXPIRED"));

        service.queueSync(MEMBER);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(SidecarErrorCode.SESSION_EXPIRED);
    }

    private void stubOneSyncedAccount() {
        TrAccountData data = new TrAccountData("tr_cto", "TR Titres", AccountType.COMPTE_TITRES, bd("0"), List.of());
        when(trPort.fetchAccounts("cookie", CLAIMS)).thenReturn(List.of(data));
        Account existing = Account.builder().id(42L).name("TR Titres").type(AccountType.COMPTE_TITRES)
            .provider("Trade Republic").currency("EUR").currentBalance(bd("1000"))
            .externalAccountId("tr_cto").isManual(false).build();
        when(accountRepository.findByExternalAccountIdAndMemberId("tr_cto", MEMBER)).thenReturn(Optional.of(existing));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenAnswer(inv -> com.picsou.dto.AccountResponse.from(inv.getArgument(0), bd("0")));
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }
}
