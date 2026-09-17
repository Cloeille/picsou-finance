package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.BoursoSession;
import com.picsou.model.BoursoSyncStatus;
import com.picsou.model.FamilyMember;
import com.picsou.port.BoursoErrorCode;
import com.picsou.port.BoursoPort;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BoursoSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class BoursoSyncServiceTest {
    private static final String PEA_ID = "bourso_9651d8edd5975de1b9eff3865505f15f";
    private static final String CHECKING_ID = "bourso_e2f509c466f5294f15abd873dbbf8a62";

    @Mock BoursoPort port;
    @Mock BoursoSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock AccountService accountService;
    @Mock OpenFigiIsinConverter isinConverter;
    @Mock SecurityIdentityService identityService;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock TransactionStatus transactionStatus;
    @Captor ArgumentCaptor<Account> accountCaptor;
    @Captor ArgumentCaptor<List<AccountHolding>> holdingsCaptor;

    BoursoSyncService service;

    @BeforeEach
    void setUp() {
        executeTransactionsImmediately();
        service = serviceWith(Runnable::run);
    }

    @Test
    void queueSync_writesEveryAccountWithItsOwnEnvelope() {
        arrangeCommittableSync(checkingAccount(), savingsAccount(), peaAccount());

        BoursoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(BoursoSyncStatus.SUCCESS);
        verify(accountRepository, org.mockito.Mockito.times(3)).save(accountCaptor.capture());
        List<Account> saved = accountCaptor.getAllValues();
        assertThat(saved).extracting(Account::getType).containsExactly(
            AccountType.CHECKING, AccountType.SAVINGS, AccountType.PEA
        );
        assertThat(saved).allSatisfy(account -> {
            assertThat(account.getProvider()).isEqualTo("BoursoBank");
            assertThat(account.getCurrency()).isEqualTo("EUR");
            assertThat(account.isManual()).isFalse();
        });
        assertThat(saved.getFirst().getExternalAccountId()).isEqualTo(CHECKING_ID);
        assertThat(saved.getFirst().getCurrentBalance()).isEqualByComparingTo("20810.50");
    }

    /**
     * A livret has one number and no cash sleeve. Writing 0 would report a
     * phantom cash balance that AccountService folds into the invested amount.
     */
    @Test
    void queueSync_leavesTheCashBalanceNullOnACashAccount() {
        arrangeCommittableSync(checkingAccount(), peaAccount());

        service.queueSync(7L);

        verify(accountRepository, org.mockito.Mockito.times(2)).save(accountCaptor.capture());
        assertThat(accountCaptor.getAllValues().getFirst().getCashBalance()).isNull();
        assertThat(accountCaptor.getAllValues().getLast().getCashBalance())
            .isEqualByComparingTo("3088.89");
    }

    @Test
    void queueSync_keepsTheBrokerValuationOnEveryHolding() {
        arrangeCommittableSync(peaAccount());
        when(isinConverter.resolve("IE00B4L5Y983"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("IWDA.AS", "iShares Core MSCI World"));

        service.queueSync(7L);

        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        AccountHolding holding = holdingsCaptor.getValue().getFirst();
        assertThat(holding.getTicker()).isEqualTo("IWDA.AS");
        assertThat(holding.getQuantity()).isEqualByComparingTo("1000");
        assertThat(holding.getAverageBuyIn()).isEqualByComparingTo("128.00");
        assertThat(holding.getCurrentPrice()).isEqualByComparingTo("140.00");
        assertThat(holding.getQuoteCurrency()).isEqualTo("EUR");
        assertThat(holding.getProviderValueEur()).isEqualByComparingTo("140000.00");
        assertThat(holding.getProviderPnlEur()).isEqualByComparingTo("12000.00");
    }

    @Test
    void queueSync_remembersWhichIsinTheTickerCameFrom() {
        arrangeCommittableSync(peaAccount());
        when(isinConverter.resolve("IE00B4L5Y983"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("IWDA.AS", "iShares Core MSCI World"));

        service.queueSync(7L);

        // The ISIN is what Boursorama's search actually resolves for a composition, and the only
        // key a fund-facts lookup has. Converting it to a ticker and dropping it is what left the
        // ETF look-through unable to find the funds it was asked about.
        ArgumentCaptor<Map<String, String>> isins = ArgumentCaptor.captor();
        verify(identityService).record(isins.capture());
        assertThat(isins.getValue()).containsEntry("IWDA.AS", "IE00B4L5Y983");
    }

    /**
     * BoursoBank's trading board exposes only its own symbol, so an unresolved
     * ISIN is routine. The line must still sync -- its provider valuation is
     * what keeps it from reading as 0 EUR.
     */
    @Test
    void queueSync_fallsBackToTheBoursoSymbolWhenNoIsinCameBack() {
        arrangeCommittableSync(account(PEA_ID, "PEA DOE", AccountType.PEA, "143088.89", "3088.89",
            position(null, "1rTCW8", "iShares Core MSCI World", "1000", "128.00", "140.00", "140000.00", "12000.00")));

        service.queueSync(7L);

        verify(isinConverter, never()).resolve(anyString());
        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        AccountHolding holding = holdingsCaptor.getValue().getFirst();
        assertThat(holding.getTicker()).isEqualTo("1rTCW8");
        assertThat(holding.getProviderValueEur()).isEqualByComparingTo("140000.00");
    }

    @Test
    void queueSync_writesTheCostBasisAsTheInvestedAmountOfASecuritiesAccount() {
        arrangeCommittableSync(peaAccount());

        service.queueSync(7L);

        // 3088.89 cash + 1000 x 128.00 = 131088.89
        verify(accountService).upsertSnapshot(
            any(Account.class),
            eq(new BigDecimal("143088.89")),
            eq(new BigDecimal("131088.89")),
            eq(LocalDate.now())
        );
    }

    /**
     * A current account has no cost basis of its own; AccountService derives one
     * exactly as it does for every other balance-only connector.
     */
    @Test
    void queueSync_letsAccountServiceDeriveTheSnapshotOfACashAccount() {
        arrangeCommittableSync(checkingAccount());

        service.queueSync(7L);

        verify(accountService).upsertSnapshot(
            any(Account.class),
            eq(new BigDecimal("20810.50")),
            eq(LocalDate.now())
        );
        verify(accountService, never()).upsertSnapshot(any(), any(), any(), any());
    }

    /**
     * The failure this connector exists to prevent: a truncated position list
     * still looks like a valid, smaller portfolio. Overwriting a correct PEA
     * with it would engrave a false loss in the net-worth series.
     */
    @Test
    void queueSync_refusesAPortfolioThatDoesNotReconcileAndKeepsTheLastGoodOne() {
        BoursoSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(
            account(PEA_ID, "PEA DOE", AccountType.PEA, "143088.89", "3088.89",
                position("IE00B4L5Y983", "1rTCW8", "iShares", "1000", "128.00", "140.00", "40000.00", "12000.00"))
        ));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(BoursoSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(BoursoErrorCode.PORTFOLIO_INCOMPLETE);
        verify(accountRepository, never()).save(any());
        verify(holdingRepository, never()).deleteByAccountId(any());
    }

    @Test
    void queueSync_refusesPositionsReportedOnACashAccount() {
        BoursoSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(
            account(CHECKING_ID, "BoursoBank", AccountType.CHECKING, "20810.50", null,
                position("IE00B4L5Y983", "1rTCW8", "iShares", "1", "1", "1", "1", null))
        ));

        service.queueSync(7L);

        assertThat(session.getLastSyncError()).isEqualTo(BoursoErrorCode.INVALID_DATA);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void queueSync_refusesASnapshotTheSidecarFlaggedAsIncomplete() {
        BoursoSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(
            new BoursoPort.AccountData(CHECKING_ID, "BoursoBank", AccountType.CHECKING,
                new BigDecimal("20810.50"), null, List.of(), false)
        ));

        service.queueSync(7L);

        assertThat(session.getLastSyncError()).isEqualTo(BoursoErrorCode.PORTFOLIO_INCOMPLETE);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void queueSync_refusesDuplicateAccounts() {
        BoursoSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(checkingAccount(), checkingAccount()));

        service.queueSync(7L);

        assertThat(session.getLastSyncError()).isEqualTo(BoursoErrorCode.INVALID_DATA);
    }

    /** Two lines resolving to one ticker must not overwrite each other. */
    @Test
    void queueSync_mergesLinesThatResolveToTheSameTicker() {
        arrangeCommittableSync(account(PEA_ID, "PEA DOE", AccountType.PEA, "20000.00", "0",
            position("IE00B4L5Y983", "1rTCW8", "iShares", "100", "100.00", "150.00", "15000.00", "5000.00"),
            position("IE00B4L5Y983", "1rTCW8b", "iShares bis", "50", "80.00", "150.00", "5000.00", "1000.00")));
        when(isinConverter.resolve("IE00B4L5Y983"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("IWDA.AS", "iShares"));

        service.queueSync(7L);

        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        assertThat(holdingsCaptor.getValue()).singleElement().satisfies(holding -> {
            assertThat(holding.getQuantity()).isEqualByComparingTo("150");
            // (100x100 + 50x80) / 150
            assertThat(holding.getAverageBuyIn()).isEqualByComparingTo("93.33333333");
            assertThat(holding.getProviderValueEur()).isEqualByComparingTo("20000.00");
            assertThat(holding.getProviderPnlEur()).isEqualByComparingTo("6000.00");
        });
    }

    @Test
    void queueSync_doesNotResurrectAnAccountTheUserDeleted() {
        FamilyMember member = member();
        arrangeQueuedSession(activeSession(member));
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(checkingAccount()));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(accountRepository.findByExternalAccountIdAndMemberId(CHECKING_ID, 7L))
            .thenReturn(Optional.empty());
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(CHECKING_ID, 7L))
            .thenReturn(true);

        BoursoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(BoursoSyncStatus.SUCCESS);
        verify(accountRepository, never()).save(any());
    }

    /**
     * A session cleared or replaced while the job was in flight must not have an
     * older job commit over the top of whatever replaced it.
     */
    @Test
    void queueSync_discardsAResultWhoseSessionDisappearedMidFlight() {
        BoursoSession session = activeSession(member());
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));
        when(encryption.decrypt("encrypted")).thenReturn("plain-state");
        when(sessionRepository.findByIdAndMemberIdForUpdate(3L, 7L))
            .thenReturn(Optional.of(session))   // markRunning
            .thenReturn(Optional.empty());      // commit: gone
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(checkingAccount()));

        service.queueSync(7L);

        verify(accountRepository, never()).save(any());
    }

    @Test
    void queueSync_doesNotStackASecondJobOnAnInFlightSession() {
        BoursoSession session = activeSession(member());
        session.markQueued();
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));

        BoursoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(BoursoSyncStatus.QUEUED);
        verify(port, never()).fetchAccounts(any());
    }

    @Test
    void queueSync_refusesToRunWithoutAnActiveSession() {
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.queueSync(7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(BoursoErrorCode.SESSION_EXPIRED.name())
            );
    }

    /** Only an expired session needs re-authentication. */
    @Test
    void anExpiredSessionIsDeactivatedSoTheUserIsPromptedToReconnect() {
        BoursoSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenThrow(
            new SyncException("gone", null, BoursoErrorCode.SESSION_EXPIRED.name())
        );

        service.queueSync(7L);

        assertThat(session.isActive()).isFalse();
    }

    /**
     * A transient failure leaves a usable session behind, so the daily scheduler
     * simply retries instead of asking the user to sign in again.
     */
    @Test
    void aTransientFailureKeepsTheSessionUsable() {
        BoursoSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenThrow(
            new SyncException("down", null, BoursoErrorCode.UPSTREAM_UNAVAILABLE.name())
        );

        service.queueSync(7L);

        assertThat(session.isActive()).isTrue();
        assertThat(session.getLastSyncError()).isEqualTo(BoursoErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    void recoverInterruptedSyncs_failsJobsAWrestartLeftInFlight() {
        service.recoverInterruptedSyncs();

        verify(sessionRepository).markInterruptedSyncsFailed(
            eq(List.of(BoursoSyncStatus.QUEUED, BoursoSyncStatus.RUNNING)),
            eq(BoursoSyncStatus.FAILED),
            any(),
            eq(BoursoErrorCode.INTERNAL_ERROR)
        );
    }

    @Test
    void initiateAuth_storesTheSessionAndSyncsWhenNoSecondFactorIsAsked() {
        FamilyMember member = member();
        when(port.initiateAuth("12345678", "123456"))
            .thenReturn(new BoursoPort.InitiateResult(null, false, null, "cookies"));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(encryption.encrypt("cookies")).thenReturn("encrypted");
        when(sessionRepository.saveAndFlush(any(BoursoSession.class))).thenAnswer(invocation -> {
            BoursoSession stored = invocation.getArgument(0);
            when(sessionRepository.findByIdAndMemberIdForUpdate(any(), eq(7L)))
                .thenReturn(Optional.of(stored));
            return stored;
        });
        when(port.fetchAccounts("cookies")).thenReturn(List.of(checkingAccount()));
        when(accountRepository.findByExternalAccountIdAndMemberId(CHECKING_ID, 7L))
            .thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            if (account.getId() == null) account.setId(11L);
            return account;
        });

        BoursoSyncService.AuthInitResponse result = service.initiateAuth("12345678", "123456", 7L);

        assertThat(result.mfaRequired()).isFalse();
        verify(sessionRepository).saveAndFlush(any(BoursoSession.class));
        verify(accountRepository).save(any(Account.class));
    }

    @Test
    void initiateAuth_storesNothingWhileTheAppPushIsStillPending() {
        when(port.initiateAuth("12345678", "123456"))
            .thenReturn(new BoursoPort.InitiateResult("p1", true, "APP_PUSH", null));

        BoursoSyncService.AuthInitResponse result = service.initiateAuth("12345678", "123456", 7L);

        assertThat(result.mfaRequired()).isTrue();
        assertThat(result.mfaType()).isEqualTo("APP_PUSH");
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void completeAuth_refusesASidecarResponseWithoutASession() {
        when(port.completeAuth("p1")).thenReturn("  ");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.completeAuth("p1", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(BoursoErrorCode.INVALID_DATA.name())
            );
    }

    @Test
    void aScheduledResyncIsSkippedWhenNoSessionIsActive() {
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());

        service.resyncIfSessionActive(7L);

        verify(port, never()).fetchAccounts(any());
    }

    @Test
    void aScheduledResyncSwallowsUpstreamFailuresSoOtherMembersStillSync() {
        BoursoSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenThrow(
            new SyncException("down", null, BoursoErrorCode.UPSTREAM_UNAVAILABLE.name())
        );

        service.resyncIfSessionActive(7L);

        assertThat(session.getLastSyncError()).isEqualTo(BoursoErrorCode.UPSTREAM_UNAVAILABLE);
    }

    // -- helpers ------------------------------------------------------------

    private void arrangeCommittableSync(BoursoPort.AccountData... accounts) {
        FamilyMember member = member();
        arrangeQueuedSession(activeSession(member));
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(accounts));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(accountRepository.findByExternalAccountIdAndMemberId(any(), eq(7L)))
            .thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            if (account.getId() == null) account.setId(11L);
            return account;
        });
    }

    private void executeTransactionsImmediately() {
        lenient().doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        }).when(txTemplate).execute(any(TransactionCallback.class));
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(transactionStatus);
            return null;
        }).when(txTemplate).executeWithoutResult(any());
    }

    private BoursoSyncService serviceWith(Executor executor) {
        return new BoursoSyncService(
            port,
            sessionRepository,
            accountRepository,
            holdingRepository,
            memberRepository,
            accountService,
            isinConverter,
            identityService,
            encryption,
            txTemplate,
            executor
        );
    }

    private void arrangeQueuedSession(BoursoSession session) {
        lenient().when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByIdAndMemberIdForUpdate(session.getId(), 7L))
            .thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));
        lenient().when(encryption.decrypt("encrypted")).thenReturn("plain-state");
    }

    private FamilyMember member() {
        return FamilyMember.builder().id(7L).displayName("Owner").build();
    }

    private BoursoSession activeSession(FamilyMember member) {
        return BoursoSession.builder()
            .id(3L)
            .member(member)
            .sessionState("encrypted")
            .active(true)
            .syncStatus(BoursoSyncStatus.IDLE)
            .build();
    }

    private BoursoPort.AccountData checkingAccount() {
        return account(CHECKING_ID, "BoursoBank", AccountType.CHECKING, "20810.50", null);
    }

    private BoursoPort.AccountData savingsAccount() {
        return account("bourso_a8a23172b7e7c91c538831578242112e",
            "LIVRET DEVELOPPEMENT DURABLE SOLIDAIRE", AccountType.SAVINGS, "11010.00", null);
    }

    private BoursoPort.AccountData peaAccount() {
        return account(PEA_ID, "PEA DOE", AccountType.PEA, "143088.89", "3088.89",
            position("IE00B4L5Y983", "1rTCW8", "iShares Core MSCI World",
                "1000", "128.00", "140.00", "140000.00", "12000.00"));
    }

    private BoursoPort.AccountData account(
        String externalId, String name, AccountType type,
        String balanceEur, String cashBalance, BoursoPort.Position... positions
    ) {
        return new BoursoPort.AccountData(
            externalId,
            name,
            type,
            new BigDecimal(balanceEur),
            cashBalance == null ? null : new BigDecimal(cashBalance),
            List.of(positions),
            true
        );
    }

    private BoursoPort.Position position(
        String isin, String symbol, String label, String quantity,
        String buyingPriceEur, String currentPrice, String currentValueEur, String pnlEur
    ) {
        return new BoursoPort.Position(
            isin,
            symbol,
            label,
            new BigDecimal(quantity),
            buyingPriceEur == null ? null : new BigDecimal(buyingPriceEur),
            currentPrice == null ? null : new BigDecimal(currentPrice),
            "EUR",
            new BigDecimal(currentValueEur),
            pnlEur == null ? null : new BigDecimal(pnlEur)
        );
    }
}
