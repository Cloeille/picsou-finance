package com.picsou.service;

import com.picsou.adapter.LbpAdapter;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.CardNature;
import com.picsou.model.FamilyMember;
import com.picsou.model.LbpSession;
import com.picsou.model.LbpSyncStatus;
import com.picsou.model.Transaction;
import com.picsou.port.SidecarErrorCode;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.LbpSessionRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static com.picsou.support.TestEntities.owner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Snapshot write path: kinds, guards, dedup, soft-delete, atomicity. Synthetic fixtures only. */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class LbpSyncServiceSyncTest {

    private static final long MEMBER = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);
    private static final Clock CLOCK = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    @Mock LbpAdapter adapter;
    @Mock LbpSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock AccountService accountService;
    @Mock SavingsAutoConfigService savingsAutoConfig;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock TransactionStatus transactionStatus;
    @Mock Executor executor;
    @Captor ArgumentCaptor<Account> accountCaptor;
    @Captor ArgumentCaptor<Iterable<Transaction>> transactionsCaptor;

    LbpSyncService service;

    @BeforeEach
    void setUp() {
        executeTransactionsImmediately();
        service = serviceWith(txTemplate, Runnable::run);
    }

    // -- kind mapping ---------------------------------------------------------

    @Test
    void queueSync_mapsEachSupportedKindToItsPicsouAccountType() {
        Fixture f = new Fixture();
        arrangeCommittableSync(f.current(), f.livretA(), f.ldds(), f.withName(f.lep(), null),
            f.csl(), f.jeune(), f.pel(), f.cel());

        LbpSyncService.SessionStatusResponse result = service.queueSync(MEMBER);

        assertThat(result.syncStatus()).isEqualTo(LbpSyncStatus.SUCCESS);
        verify(accountRepository, times(8)).save(accountCaptor.capture());
        List<Account> saved = accountCaptor.getAllValues();
        assertThat(saved).extracting(Account::getType).containsExactly(
            AccountType.CHECKING, AccountType.LIVRET_A, AccountType.LDDS, AccountType.LEP,
            AccountType.SAVINGS, AccountType.LIVRET_JEUNE, AccountType.PEL, AccountType.CEL);
        assertThat(saved).extracting(Account::getExternalAccountId).containsExactly(
            "lbp_1001", "lbp_1002", "lbp_1003", "lbp_1004",
            "lbp_1005", "lbp_1006", "lbp_1007", "lbp_1008");
        assertThat(saved).allSatisfy(account -> {
            assertThat(account.getProvider()).isEqualTo("La Banque Postale");
            assertThat(account.getCurrency()).isEqualTo("EUR");
            assertThat(account.isManual()).isFalse();
            assertThat(account.getLastSyncedAt()).isNotNull();
            assertThat(account.getIban()).isNull();
            assertThat(account.getParentAccountId()).isNull();
        });
        assertThat(saved.get(1).getColor()).isEqualTo("#f59e0b");
        assertThat(saved.get(0).getColor()).isEqualTo("#dc2626");
        // A blank label falls back to a readable product name.
        assertThat(saved.get(3).getName()).isEqualTo("LEP");
    }

    @Test
    void queueSync_mapsTheCardAndLoanKindsWithTheCardOutstandingAndDueDate() {
        Fixture f = new Fixture();
        arrangeCommittableSync(f.current(), f.card(), f.loan());

        LbpSyncService.SessionStatusResponse result = service.queueSync(MEMBER);

        assertThat(result.syncStatus()).isEqualTo(LbpSyncStatus.SUCCESS);
        verify(accountRepository, times(3)).save(accountCaptor.capture());
        List<Account> saved = accountCaptor.getAllValues();
        assertThat(saved).extracting(Account::getType).containsExactly(
            AccountType.CHECKING, AccountType.CREDIT_CARD, AccountType.LOAN);
        Account card = saved.get(1);
        assertThat(card.getCurrentBalance()).isEqualByComparingTo("-87.10");
        assertThat(card.getType().isLiability()).isTrue();
        assertThat(card.getCardNature()).isEqualTo(CardNature.DEFERRED_DEBIT);
        assertThat(card.getPaymentDueAmount()).isEqualByComparingTo("87.10");
        assertThat(card.getPaymentDueDate()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(card.getColor()).isEqualTo("#2563eb");
        assertThat(saved.get(0).getCardNature()).isNull();
        assertThat(saved.get(0).getPaymentDueAmount()).isNull();
        assertThat(saved.get(0).getPaymentDueDate()).isNull();
        Account loan = saved.get(2);
        assertThat(loan.getCurrentBalance()).isEqualByComparingTo("9000.00");
        assertThat(loan.getType().isLiability()).isTrue();
    }

    // -- transactions ---------------------------------------------------------

    @Test
    void queueSync_persistsTransactionsWithPrefixedExternalIdsAndADailySnapshot() {
        Fixture f = new Fixture();
        arrangeCommittableSync(f.with(f.current(), List.of(
            f.tx("t1", "2026-11-01", "-12.30", "SUPERMARCHE"),
            f.tx("t2", "2026-11-02", "1500.00", "VIREMENT RECU"))));

        service.queueSync(MEMBER);

        verify(transactionRepository).saveAllAndFlush(transactionsCaptor.capture());
        List<Transaction> rows = toList(transactionsCaptor.getValue());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).satisfies(row -> {
            assertThat(row.getExternalId()).isEqualTo("lbp_t1");
            assertThat(row.getDate()).isEqualTo(LocalDate.of(2026, 11, 1));
            assertThat(row.getAmount()).isEqualByComparingTo("-12.30");
            assertThat(row.getDescription()).isEqualTo("SUPERMARCHE");
            assertThat(row.getNativeCurrency()).isEqualTo("EUR");
            assertThat(row.isManual()).isFalse();
            assertThat(row.getAccount().getExternalAccountId()).isEqualTo("lbp_1001");
        });
        assertThat(rows.get(1).getExternalId()).isEqualTo("lbp_t2");
        verify(accountService).upsertSnapshot(any(Account.class), eq(new BigDecimal("1234.56")), eq(TODAY));
    }

    @Test
    void queueSync_updatesAStoredTransactionInPlaceInsteadOfDuplicatingIt() {
        arrangeCommittableSync(new Fixture().current());
        Transaction stored = Transaction.builder().id(55L).externalId("lbp_t1")
            .date(LocalDate.of(2026, 10, 1)).description("old").amount(new BigDecimal("-1.00")).build();
        when(transactionRepository.findByAccountIdAndIsManualFalse(any())).thenReturn(List.of(stored));

        service.queueSync(MEMBER);

        verify(transactionRepository).saveAllAndFlush(transactionsCaptor.capture());
        List<Transaction> rows = toList(transactionsCaptor.getValue());
        assertThat(rows).singleElement().isSameAs(stored);
        assertThat(stored.getDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(stored.getAmount()).isEqualByComparingTo("-12.30");
        assertThat(stored.getDescription()).isEqualTo("SUPERMARCHE");
    }

    @Test
    void queueSync_keepsLastOccurrenceWhenAnIdRepeatsInOneAccount() {
        Fixture f = new Fixture();
        arrangeCommittableSync(f.with(f.current(), List.of(
            f.tx("t1", "2026-11-01", "-1.00", "FIRST"),
            f.tx("t1", "2026-11-01", "-2.00", "SECOND"))));

        service.queueSync(MEMBER);

        verify(transactionRepository).saveAllAndFlush(transactionsCaptor.capture());
        assertThat(toList(transactionsCaptor.getValue())).singleElement()
            .satisfies(row -> {
                assertThat(row.getExternalId()).isEqualTo("lbp_t1");
                assertThat(row.getDescription()).isEqualTo("SECOND");
                assertThat(row.getAmount()).isEqualByComparingTo("-2.00");
            });
    }

    // -- all-or-nothing guards ------------------------------------------------

    @Test
    void queueSync_refusesTheWholeSnapshotWhenOneAccountIsIncomplete() {
        Fixture f = new Fixture();
        LbpSession session = arrangeCommittableSync(f.current(), f.incomplete(f.livretA()));

        service.queueSync(MEMBER);

        assertFailedWithNothingWritten(session, SidecarErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    @Test
    void queueSync_refusesTheWholeSnapshotOnDuplicateExternalIds() {
        LbpSession session = arrangeCommittableSync(new Fixture().current(), new Fixture().current());

        service.queueSync(MEMBER);

        assertFailedWithNothingWritten(session, SidecarErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    @Test
    void queueSync_refusesTheWholeSnapshotOnAnUnknownKind() {
        Fixture f = new Fixture();
        LbpSession session = arrangeCommittableSync(f.current(), f.withKind(f.livretA(), "PER"));

        service.queueSync(MEMBER);

        assertFailedWithNothingWritten(session, SidecarErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    @Test
    void queueSync_refusesTheWholeSnapshotOnANonEuroAccount() {
        Fixture f = new Fixture();
        LbpSession session = arrangeCommittableSync(f.current(), f.withCurrency(f.livretA(), "USD"));

        service.queueSync(MEMBER);

        assertFailedWithNothingWritten(session, SidecarErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    @Test
    void queueSync_refusesTheWholeSnapshotOnANonEuroTransaction() {
        Fixture f = new Fixture();
        LbpAdapter.Transaction usd = new LbpAdapter.Transaction("t9", LocalDate.of(2026, 11, 1), null,
            new BigDecimal("-5.00"), "USD", "SHOP", null);
        LbpSession session = arrangeCommittableSync(f.with(f.current(), List.of(usd)));

        service.queueSync(MEMBER);

        assertFailedWithNothingWritten(session, SidecarErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    @Test
    void queueSync_refusesTheWholeSnapshotOnAnEmptyAccountList() {
        LbpSession session = arrangeCommittableSync(
            new LbpAdapter.AccountsSnapshot(List.of(), List.of()));

        service.queueSync(MEMBER);

        assertFailedWithNothingWritten(session, SidecarErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    @Test
    void queueSync_refusesACardWithAPositiveOutstanding() {
        // A card balance is its outstanding: money owed, never a credit.
        Fixture f = new Fixture();
        LbpSession session = arrangeCommittableSync(f.current(), f.withBalance(f.card(), "12.00"));

        service.queueSync(MEMBER);

        assertFailedWithNothingWritten(session, SidecarErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    @Test
    void queueSync_refusesACardWithAnUnknownNature() {
        Fixture f = new Fixture();
        LbpSession session = arrangeCommittableSync(f.current(), f.withNature(f.card(), "PREPAID"));

        service.queueSync(MEMBER);

        assertFailedWithNothingWritten(session, SidecarErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    @Test
    void queueSync_refusesATransactionWithAnIdTooLongForTheColumn() {
        Fixture f = new Fixture();
        LbpSession session = arrangeCommittableSync(f.with(f.current(),
            List.of(f.tx("x".repeat(120), "2026-11-01", "-1.00", "LONG"))));

        service.queueSync(MEMBER);

        assertFailedWithNothingWritten(session, SidecarErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    // -- unsupported products -------------------------------------------------

    @Test
    void queueSync_refusesTheWholeSnapshotOnAMalformedUnsupportedEntry() {
        LbpSession session = arrangeCommittableSync(new LbpAdapter.AccountsSnapshot(
            List.of(new Fixture().current()),
            List.of(new LbpAdapter.Unsupported("9001", null))));

        service.queueSync(MEMBER);

        assertFailedWithNothingWritten(session, SidecarErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    @Test
    void queueSync_truncatesTheUnsupportedListAtOneHundredWithoutFailingTheImport() {
        List<LbpAdapter.Unsupported> overflow = new ArrayList<>();
        for (int i = 0; i < 105; i++) {
            overflow.add(new LbpAdapter.Unsupported("9" + i, "7"));
        }
        LbpSession session = arrangeCommittableSync(new LbpAdapter.AccountsSnapshot(
            List.of(new Fixture().current()), overflow));

        LbpSyncService.SessionStatusResponse result = service.queueSync(MEMBER);

        assertThat(session.getSyncStatus()).isEqualTo(LbpSyncStatus.SUCCESS);
        assertThat(result.syncStatus()).isEqualTo(LbpSyncStatus.SUCCESS);
        assertThat(result.unsupportedCount()).isEqualTo(100);
        verify(accountRepository, times(1)).save(any(Account.class));
    }

    // -- soft-delete, IBAN, savings wiring ------------------------------------

    @Test
    void queueSync_doesNotResurrectAnAccountTheUserDeleted() {
        LbpSession session = arrangeCommittableSync(new Fixture().current());
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("lbp_1001", MEMBER))
            .thenReturn(true);

        LbpSyncService.SessionStatusResponse result = service.queueSync(MEMBER);

        assertThat(result.syncStatus()).isEqualTo(LbpSyncStatus.SUCCESS);
        assertThat(session.getSyncStatus()).isEqualTo(LbpSyncStatus.SUCCESS);
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).saveAllAndFlush(any());
        verify(accountService, never()).upsertSnapshot(any(), any(), any());
    }

    // -- closed accounts ------------------------------------------------------

    @Test
    void queueSync_closesAnAccountTheSnapshotNoLongerListsAtZero() {
        Fixture f = new Fixture();
        arrangeCommittableSync(f.current());
        Account closedLivret = Account.builder()
            .id(12L)
            .externalAccountId("lbp_9001")
            .provider("La Banque Postale")
            .type(AccountType.LIVRET_A)
            .currentBalance(new BigDecimal("800.00"))
            .build();
        Account listedCurrent = Account.builder()
            .id(11L)
            .externalAccountId("lbp_1001")
            .provider("La Banque Postale")
            .type(AccountType.CHECKING)
            .currentBalance(new BigDecimal("1200.00"))
            .build();
        when(accountRepository.findAllByMemberIdAndProvider(MEMBER, "La Banque Postale"))
            .thenReturn(List.of(closedLivret, listedCurrent));
        when(accountRepository.findByExternalAccountIdAndMemberId("lbp_1001", MEMBER))
            .thenReturn(Optional.of(listedCurrent));

        LbpSyncService.SessionStatusResponse result = service.queueSync(MEMBER);

        assertThat(result.syncStatus()).isEqualTo(LbpSyncStatus.SUCCESS);
        assertThat(closedLivret.getCurrentBalance()).isEqualByComparingTo("0");
        assertThat(closedLivret.getDeletedAt()).isNull();
        assertThat(closedLivret.getLastSyncedAt()).isNotNull();
        verify(accountService).upsertSnapshot(eq(closedLivret), eq(BigDecimal.ZERO), eq(TODAY));
        // The still-listed account only goes through the regular upsert.
        assertThat(listedCurrent.getCurrentBalance()).isEqualByComparingTo("1234.56");
        verify(accountService, never()).upsertSnapshot(eq(listedCurrent), eq(BigDecimal.ZERO), any(LocalDate.class));
    }

    @Test
    void queueSync_closesACardTheSnapshotNoLongerListsAndClearsWhatWasDue() {
        Fixture f = new Fixture();
        arrangeCommittableSync(f.current());
        Account closedCard = Account.builder()
            .id(12L)
            .externalAccountId("lbp_2001")
            .provider("La Banque Postale")
            .type(AccountType.CREDIT_CARD)
            .cardNature(CardNature.DEFERRED_DEBIT)
            .currentBalance(new BigDecimal("-87.10"))
            .paymentDueAmount(new BigDecimal("87.10"))
            .paymentDueDate(LocalDate.of(2026, 11, 30))
            .build();
        when(accountRepository.findAllByMemberIdAndProvider(MEMBER, "La Banque Postale"))
            .thenReturn(List.of(closedCard));

        service.queueSync(MEMBER);

        assertThat(closedCard.getCurrentBalance()).isEqualByComparingTo("0");
        assertThat(closedCard.getPaymentDueAmount()).isEqualByComparingTo("0");
        assertThat(closedCard.getPaymentDueDate()).isNull();
        verify(accountService).upsertSnapshot(eq(closedCard), eq(BigDecimal.ZERO), eq(TODAY));
    }

    @Test
    void queueSync_leavesAnAlreadyZeroUnlistedAccountAlone() {
        arrangeCommittableSync(new Fixture().current());
        Account alreadyClosed = Account.builder()
            .id(12L)
            .externalAccountId("lbp_9001")
            .provider("La Banque Postale")
            .type(AccountType.LIVRET_A)
            .currentBalance(BigDecimal.ZERO)
            .build();
        when(accountRepository.findAllByMemberIdAndProvider(MEMBER, "La Banque Postale"))
            .thenReturn(List.of(alreadyClosed));

        service.queueSync(MEMBER);

        verify(accountService, never()).upsertSnapshot(eq(alreadyClosed), any(), any());
        // Only the listed account is saved.
        verify(accountRepository, times(1)).save(any(Account.class));
    }

    @Test
    void queueSync_ignoresStoredAccountsOutsideTheLbpScope() {
        arrangeCommittableSync(new Fixture().current());
        Account foreign = Account.builder()
            .id(12L)
            .externalAccountId("eb_123")
            .provider("La Banque Postale")
            .type(AccountType.CHECKING)
            .currentBalance(new BigDecimal("50.00"))
            .build();
        Account withoutId = Account.builder()
            .id(13L)
            .provider("La Banque Postale")
            .type(AccountType.CHECKING)
            .currentBalance(new BigDecimal("60.00"))
            .build();
        when(accountRepository.findAllByMemberIdAndProvider(MEMBER, "La Banque Postale"))
            .thenReturn(List.of(foreign, withoutId));

        service.queueSync(MEMBER);

        assertThat(foreign.getCurrentBalance()).isEqualByComparingTo("50.00");
        assertThat(withoutId.getCurrentBalance()).isEqualByComparingTo("60.00");
        verify(accountService, never()).upsertSnapshot(eq(foreign), any(), any());
        verify(accountService, never()).upsertSnapshot(eq(withoutId), any(), any());
    }

    @Test
    void queueSync_aFailedFetchClosesNoAccount() {
        Fixture f = new Fixture();
        LbpSession session = arrangeCommittableSync(f.current(), f.incomplete(f.livretA()));

        service.queueSync(MEMBER);

        assertThat(session.getSyncStatus()).isEqualTo(LbpSyncStatus.FAILED);
        verify(accountRepository, never()).findAllByMemberIdAndProvider(any(), any());
    }

    @Test
    void queueSync_neverStoresTheIbanNorALocalOnlyLink() {
        arrangeCommittableSync(new Fixture().current());

        service.queueSync(MEMBER);

        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getIban()).isNull();
        assertThat(accountCaptor.getValue().getParentAccountId()).isNull();
    }

    @Test
    void queueSync_wiresTheSavingsAutoConfigOfRegulatedBooksOnFirstImport() {
        Fixture f = new Fixture();
        arrangeCommittableSync(f.livretA(), f.lep());

        service.queueSync(MEMBER);

        verify(accountRepository, times(2)).save(accountCaptor.capture());
        List<Account> saved = accountCaptor.getAllValues();
        verify(savingsAutoConfig).configureIfMissing(saved.get(0), AccountType.LIVRET_A);
        verify(savingsAutoConfig).configureIfMissing(saved.get(1), AccountType.LEP);
    }

    // -- atomicity ------------------------------------------------------------

    @Test
    void queueSync_recordsARetryableFailureWhenTheStartUpdateFails() {
        Fixture f = new Fixture();
        LbpSession session = arrangeCommittableSync(f.current());
        when(sessionRepository.findByIdAndMemberIdForUpdate(session.getId(), MEMBER))
            .thenThrow(new IllegalStateException("db down"))
            .thenReturn(Optional.of(session));

        service.queueSync(MEMBER);

        assertThat(session.getSyncStatus()).isEqualTo(LbpSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(SidecarErrorCode.INTERNAL_ERROR);
        assertThat(session.getLastSyncCompletedAt()).isNotNull();
        assertThat(session.isActive()).isTrue();
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void queueSync_withNoSessionRow_failsAsExpiredAndPersistsNothing() {
        when(sessionRepository.findByMemberIdForUpdate(MEMBER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.queueSync(MEMBER))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo("SESSION_EXPIRED"));

        verify(sessionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).saveAllAndFlush(any());
        verify(accountService, never()).upsertSnapshot(any(), any(), any());
        verify(savingsAutoConfig, never()).configureIfMissing(any(), any());
        verify(adapter, never()).fetchAccounts(any());
        verify(encryption, never()).decrypt(any());
    }

    @Test
    void queueSync_withAnInactiveSession_failsAsExpiredAndPersistsNothing() {
        FamilyMember member = owner();
        LbpSession session = LbpSession.builder()
            .id(3L)
            .member(member)
            .sessionState("encrypted")
            .active(false)
            .syncStatus(LbpSyncStatus.IDLE)
            .build();
        when(sessionRepository.findByMemberIdForUpdate(MEMBER)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.queueSync(MEMBER))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo("SESSION_EXPIRED"));

        assertThat(session.getSyncStatus()).isEqualTo(LbpSyncStatus.IDLE);
        assertThat(session.isActive()).isFalse();
        verify(sessionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).saveAllAndFlush(any());
        verify(accountService, never()).upsertSnapshot(any(), any(), any());
        verify(adapter, never()).fetchAccounts(any());
    }

    @Test
    void queueSync_whileASyncIsInFlight_returnsTheCurrentStatusWithoutQueuingAnotherJob() {
        FamilyMember member = owner();
        LbpSession session = LbpSession.builder()
            .id(3L)
            .member(member)
            .sessionState("encrypted")
            .active(true)
            .syncStatus(LbpSyncStatus.QUEUED)
            .build();
        LbpSyncService inFlightService = serviceWith(txTemplate, executor);
        when(sessionRepository.findByMemberIdForUpdate(MEMBER)).thenReturn(Optional.of(session));

        LbpSyncService.SessionStatusResponse result = inFlightService.queueSync(MEMBER);

        assertThat(result.syncStatus()).isEqualTo(LbpSyncStatus.QUEUED);
        assertThat(result.isActive()).isTrue();
        verify(executor, never()).execute(any());
        verify(adapter, never()).fetchAccounts(any());
        verify(sessionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).saveAllAndFlush(any());
        verify(accountService, never()).upsertSnapshot(any(), any(), any());
    }

    @Test
    void queueSync_whenTheStoredSessionIsUnreadable_failsAsInvalidAndDeactivatesTheSession() {
        FamilyMember member = owner();
        LbpSession session = activeSession(member);
        when(sessionRepository.findByMemberIdForUpdate(MEMBER)).thenReturn(Optional.of(session));
        when(encryption.decrypt("encrypted")).thenThrow(new IllegalStateException("wrong key"));

        assertThatThrownBy(() -> service.queueSync(MEMBER))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo("INVALID_SESSION_STATE"));

        assertThat(session.getSyncStatus()).isEqualTo(LbpSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(SidecarErrorCode.INVALID_SESSION_STATE);
        assertThat(session.isActive()).isFalse();
        assertThat(session.getLastSyncCompletedAt()).isNotNull();
        verify(sessionRepository).save(session);
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).saveAllAndFlush(any());
        verify(accountService, never()).upsertSnapshot(any(), any(), any());
        verify(savingsAutoConfig, never()).configureIfMissing(any(), any());
        verify(adapter, never()).fetchAccounts(any());
    }

    @Test
    void recoverInterruptedSyncs_failsJobsARestartLeftInFlight() {
        service.recoverInterruptedSyncs();

        verify(sessionRepository).markInterruptedSyncsFailed(
            eq(List.of(LbpSyncStatus.QUEUED, LbpSyncStatus.RUNNING)),
            eq(LbpSyncStatus.FAILED),
            any(),
            eq(SidecarErrorCode.INTERNAL_ERROR));
    }

    // -- helpers --------------------------------------------------------------

    private void assertFailedWithNothingWritten(LbpSession session, SidecarErrorCode code) {
        assertThat(session.getSyncStatus()).isEqualTo(LbpSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(code);
        assertThat(session.getLastSyncCompletedAt()).isNotNull();
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).saveAllAndFlush(any());
        verify(accountService, never()).upsertSnapshot(any(), any(), any());
        verify(savingsAutoConfig, never()).configureIfMissing(any(), any());
    }

    private LbpSession arrangeCommittableSync(LbpAdapter.AccountData... accounts) {
        return arrangeCommittableSync(new LbpAdapter.AccountsSnapshot(List.of(accounts), List.of()));
    }

    private LbpSession arrangeCommittableSync(LbpAdapter.AccountsSnapshot snapshot) {
        FamilyMember member = owner();
        LbpSession session = activeSession(member);
        arrangeQueuedSession(session);
        lenient().when(adapter.fetchAccounts("plain-state")).thenReturn(snapshot);
        lenient().when(memberRepository.findById(MEMBER)).thenReturn(Optional.of(member));
        lenient().when(accountRepository.findByExternalAccountIdAndMemberId(any(), eq(MEMBER)))
            .thenReturn(Optional.empty());
        lenient().when(transactionRepository.findByAccountIdAndIsManualFalse(any()))
            .thenReturn(List.of());
        AtomicLong ids = new AtomicLong(10);
        lenient().when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            if (account.getId() == null) {
                account.setId(ids.incrementAndGet());
            }
            return account;
        });
        return session;
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

    private LbpSyncService serviceWith(TransactionTemplate template, Executor executor) {
        return new LbpSyncService(
            adapter, sessionRepository, accountRepository, transactionRepository,
            memberRepository, accountService, savingsAutoConfig, encryption,
            template, executor, CLOCK);
    }

    private void arrangeQueuedSession(LbpSession session) {
        lenient().when(sessionRepository.findByMemberIdForUpdate(MEMBER)).thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByIdAndMemberIdForUpdate(session.getId(), MEMBER))
            .thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByMemberId(MEMBER)).thenReturn(Optional.of(session));
        lenient().when(encryption.decrypt("encrypted")).thenReturn("plain-state");
    }

    private LbpSession activeSession(FamilyMember member) {
        return LbpSession.builder()
            .id(3L)
            .member(member)
            .sessionState("encrypted")
            .active(true)
            .syncStatus(LbpSyncStatus.IDLE)
            .build();
    }

    private static <T> List<T> toList(Iterable<T> iterable) {
        List<T> list = new ArrayList<>();
        iterable.forEach(list::add);
        return list;
    }

    /** Synthetic payloads only: invented ids, labels and amounts. */
    private static final class Fixture {
        LbpAdapter.Transaction tx(String id, String date, String amount, String label) {
            return new LbpAdapter.Transaction(id, LocalDate.parse(date), null,
                new BigDecimal(amount), "EUR", label, null);
        }

        LbpAdapter.AccountData account(String externalId, String kind, String name, String balance,
                List<LbpAdapter.Transaction> txs) {
            return new LbpAdapter.AccountData(externalId, kind, name, new BigDecimal(balance), "EUR",
                "FR7600000000000000000000099", null, null, null, null, null, null, null, txs, true);
        }

        LbpAdapter.AccountData current() {
            return with(account("1001", "CURRENT_ACCOUNT", "COMPTE COURANT", "1234.56", List.of()),
                List.of(tx("t1", "2026-11-01", "-12.30", "SUPERMARCHE")));
        }

        LbpAdapter.AccountData livretA() {
            return account("1002", "LIVRET_A", "LIVRET A", "5000.00", List.of());
        }

        LbpAdapter.AccountData ldds() {
            return account("1003", "LDDS", "LIVRET DEV DURABLE", "3000.00", List.of());
        }

        LbpAdapter.AccountData lep() {
            return account("1004", "LEP", "LEP", "7734.00", List.of());
        }

        LbpAdapter.AccountData csl() {
            return account("1005", "CSL", "COMPTE SUR LIVRET", "1200.00", List.of());
        }

        LbpAdapter.AccountData jeune() {
            return account("1006", "LIVRET_JEUNE", "LIVRET JEUNE", "1600.00", List.of());
        }

        LbpAdapter.AccountData pel() {
            return account("1007", "PEL", "PLAN EPARGNE LOGEMENT", "14000.00", List.of());
        }

        LbpAdapter.AccountData cel() {
            return account("1008", "CEL", "COMPTE EPARGNE LOGEMENT", "8000.00", List.of());
        }

        LbpAdapter.AccountData card() {
            return new LbpAdapter.AccountData("2001", "CARD", null, new BigDecimal("-87.10"), "EUR",
                null, null, null, null, null, "DEFERRED_DEBIT", null,
                LocalDate.of(2026, 11, 30),
                List.of(new LbpAdapter.Transaction("c1", LocalDate.of(2026, 11, 2), null,
                    new BigDecimal("-87.10"), "EUR", "RESTAURANT", "04")),
                true);
        }

        LbpAdapter.AccountData loan() {
            return account("3001", "LOAN", "PRET IMMOBILIER", "9000.00", List.of());
        }

        LbpAdapter.AccountData with(LbpAdapter.AccountData a, List<LbpAdapter.Transaction> txs) {
            return new LbpAdapter.AccountData(a.externalId(), a.kind(), a.name(), a.balance(), a.currency(),
                a.iban(), a.authorizedOverdraft(), a.ceiling(), a.remainingDepositCapacity(),
                a.fillingRatio(), a.cardNature(), a.parentExternalId(), a.nextDueDate(), txs,
                a.snapshotComplete());
        }

        LbpAdapter.AccountData withKind(LbpAdapter.AccountData a, String kind) {
            return new LbpAdapter.AccountData(a.externalId(), kind, a.name(), a.balance(), a.currency(),
                a.iban(), a.authorizedOverdraft(), a.ceiling(), a.remainingDepositCapacity(),
                a.fillingRatio(), a.cardNature(), a.parentExternalId(), a.nextDueDate(), a.transactions(),
                a.snapshotComplete());
        }

        LbpAdapter.AccountData withName(LbpAdapter.AccountData a, String name) {
            return new LbpAdapter.AccountData(a.externalId(), a.kind(), name, a.balance(), a.currency(),
                a.iban(), a.authorizedOverdraft(), a.ceiling(), a.remainingDepositCapacity(),
                a.fillingRatio(), a.cardNature(), a.parentExternalId(), a.nextDueDate(), a.transactions(),
                a.snapshotComplete());
        }

        LbpAdapter.AccountData withBalance(LbpAdapter.AccountData a, String balance) {
            return new LbpAdapter.AccountData(a.externalId(), a.kind(), a.name(), new BigDecimal(balance),
                a.currency(), a.iban(), a.authorizedOverdraft(), a.ceiling(), a.remainingDepositCapacity(),
                a.fillingRatio(), a.cardNature(), a.parentExternalId(), a.nextDueDate(), a.transactions(),
                a.snapshotComplete());
        }

        LbpAdapter.AccountData withCurrency(LbpAdapter.AccountData a, String currency) {
            return new LbpAdapter.AccountData(a.externalId(), a.kind(), a.name(), a.balance(), currency,
                a.iban(), a.authorizedOverdraft(), a.ceiling(), a.remainingDepositCapacity(),
                a.fillingRatio(), a.cardNature(), a.parentExternalId(), a.nextDueDate(), a.transactions(),
                a.snapshotComplete());
        }

        LbpAdapter.AccountData withNature(LbpAdapter.AccountData a, String nature) {
            return new LbpAdapter.AccountData(a.externalId(), a.kind(), a.name(), a.balance(), a.currency(),
                a.iban(), a.authorizedOverdraft(), a.ceiling(), a.remainingDepositCapacity(),
                a.fillingRatio(), nature, a.parentExternalId(), a.nextDueDate(), a.transactions(),
                a.snapshotComplete());
        }

        LbpAdapter.AccountData incomplete(LbpAdapter.AccountData a) {
            return new LbpAdapter.AccountData(a.externalId(), a.kind(), a.name(), a.balance(), a.currency(),
                a.iban(), a.authorizedOverdraft(), a.ceiling(), a.remainingDepositCapacity(),
                a.fillingRatio(), a.cardNature(), a.parentExternalId(), a.nextDueDate(), a.transactions(),
                false);
        }
    }
}
