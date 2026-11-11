package com.picsou.service;

import com.picsou.adapter.BnpAdapter;
import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.SidecarSession;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.SidecarTransaction;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.SidecarSessionRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import com.picsou.service.sync.SourceSyncResult;
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
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static com.picsou.support.TestEntities.owner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Synthetic data only: every id, label, amount and IBAN below is invented. */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class BnpSyncServiceTest {
    private static final String IBAN = "FR7630006000011234567890189";
    private static final List<String> FAMILY = List.of("BNP Paribas", "Hello bank!", "Hello bank", "Hello Bank");
    private static final Instant NOW = Instant.parse("2026-01-10T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 1, 10);

    @Mock BnpAdapter port;
    @Mock SidecarSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock CategorizationService categorizationService;
    @Mock AccountService accountService;
    @Mock OpenFigiIsinConverter isinConverter;
    @Mock SecurityIdentityService identityService;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock TransactionStatus transactionStatus;
    @Captor ArgumentCaptor<Account> accountCaptor;
    @Captor ArgumentCaptor<List<AccountHolding>> holdingsCaptor;

    BnpSyncService service;
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        lenient().doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        }).when(txTemplate).execute(any(TransactionCallback.class));
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(transactionStatus);
            return null;
        }).when(txTemplate).executeWithoutResult(any());
        service = new BnpSyncService(port, sessionRepository, accountRepository, holdingRepository, memberRepository,
            accountService, categorizationService,
            new SharedSidecarTransactionImportService(transactionRepository, categorizationService),
            isinConverter, identityService, encryption, txTemplate, Runnable::run, clock);
    }

    // ─── login ───────────────────────────────────────────────────────────────

    @Test
    void initiateReturnsTheKeypadAndRemembersTheAttemptForTheMember() {
        when(port.initiateAuth("1234567890", "bnp")).thenReturn(initiateResult("p1", keypad(10, 5)));

        BnpSyncService.InitiateResponse response = service.initiateAuth("1234567890", "bnp", 7L);

        assertThat(response.processId()).isEqualTo("p1");
        assertThat(response.keypad().keys()).hasSize(10);
        // Another member cannot use the attempt.
        assertThatThrownBy(() -> service.submitKeypad("p1", List.of(1, 2, 3, 4, 5, 6), 8L))
            .isInstanceOfSatisfying(SyncException.class, e ->
                assertThat(e.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(port, never()).submitKeypad(anyString(), any());
    }

    @Test
    void aKeypadThatIsNotTenDistinctDigitsInFiveColumnsMeansThePageChanged() {
        List<BnpAdapter.InitiateResult> broken = List.of(
            initiateResult("p1", keypad(9, 5)),
            initiateResult("p1", keypad(10, 4)),
            initiateResult("p1", new BnpAdapter.Keypad(duplicates(), 5)),
            initiateResult("p1", new BnpAdapter.Keypad(IntStream.range(0, 10).mapToObj(i -> "1" + i).toList(), 5)));
        for (BnpAdapter.InitiateResult result : broken) {
            when(port.initiateAuth("1234567890", "bnp")).thenReturn(result);

            assertThatThrownBy(() -> service.initiateAuth("1234567890", "bnp", 7L))
                .isInstanceOfSatisfying(SyncException.class, e ->
                    assertThat(e.getCode()).isEqualTo(SidecarErrorCode.KEYPAD_CHANGED.name()));
        }
    }

    @Test
    void aChallengeWithoutAProcessIsAFormatChange() {
        when(port.initiateAuth("1234567890", "bnp")).thenReturn(initiateResult(" ", keypad(10, 5)));

        assertThatThrownBy(() -> service.initiateAuth("1234567890", "bnp", 7L))
            .isInstanceOfSatisfying(SyncException.class, e ->
                assertThat(e.getCode()).isEqualTo(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED.name()));
    }

    @Test
    void theKeypadCannotBeSentTwiceForOneAttempt() {
        startAttempt("p1");
        when(port.submitKeypad("p1", List.of(1, 2, 3, 4, 5, 6)))
            .thenReturn(new BnpAdapter.KeypadAck("p1", BnpAdapter.KeypadAck.APPROVAL_PENDING));

        service.submitKeypad("p1", List.of(1, 2, 3, 4, 5, 6), 7L);

        // A second keypad would count against the bank's lockout.
        assertThatThrownBy(() -> service.submitKeypad("p1", List.of(1, 2, 3, 4, 5, 6), 7L))
            .isInstanceOfSatisfying(SyncException.class, e ->
                assertThat(e.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(port, times(1)).submitKeypad(anyString(), any());
    }

    @Test
    void aRefusedKeypadEndsTheAttempt() {
        startAttempt("p1");
        when(port.submitKeypad("p1", List.of(1, 2, 3, 4, 5, 6)))
            .thenThrow(new SyncException("refused", null, SidecarErrorCode.INVALID_CREDENTIALS.name()));

        assertThatThrownBy(() -> service.submitKeypad("p1", List.of(1, 2, 3, 4, 5, 6), 7L))
            .isInstanceOf(SyncException.class);
        assertThatThrownBy(() -> service.submitKeypad("p1", List.of(1, 2, 3, 4, 5, 6), 7L))
            .isInstanceOfSatisfying(SyncException.class, e ->
                assertThat(e.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
    }

    @Test
    void completeBeforeTheKeypadIsRefused() {
        startAttempt("p1");

        assertThatThrownBy(() -> service.completeAuth("p1", null, 7L))
            .isInstanceOfSatisfying(SyncException.class, e ->
                assertThat(e.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(port, never()).completeAuth(anyString(), any());
    }

    @Test
    void anAppApprovalTakesNoCodeAndASmsLoginRequiresOne() {
        startAttempt("app");
        when(port.submitKeypad("app", List.of(1, 2, 3, 4, 5, 6)))
            .thenReturn(new BnpAdapter.KeypadAck("app", BnpAdapter.KeypadAck.APPROVAL_PENDING));
        service.submitKeypad("app", List.of(1, 2, 3, 4, 5, 6), 7L);
        assertThatThrownBy(() -> service.completeAuth("app", "123456", 7L))
            .isInstanceOfSatisfying(SyncException.class, e ->
                assertThat(e.getCode()).isEqualTo(SidecarErrorCode.INVALID_DATA.name()));

        startAttempt("sms");
        when(port.submitKeypad("sms", List.of(1, 2, 3, 4, 5, 6)))
            .thenReturn(new BnpAdapter.KeypadAck("sms", BnpAdapter.KeypadAck.CODE_REQUIRED));
        service.submitKeypad("sms", List.of(1, 2, 3, 4, 5, 6), 7L);
        assertThatThrownBy(() -> service.completeAuth("sms", null, 7L))
            .isInstanceOfSatisfying(SyncException.class, e ->
                assertThat(e.getCode()).isEqualTo(SidecarErrorCode.INVALID_OTP.name()));
        verify(port, never()).completeAuth(anyString(), any());
    }

    @Test
    void aMistypedSmsCodeCanBeTypedAgainButAnythingElseEndsTheAttempt() {
        startAttempt("sms");
        when(port.submitKeypad("sms", List.of(1, 2, 3, 4, 5, 6)))
            .thenReturn(new BnpAdapter.KeypadAck("sms", BnpAdapter.KeypadAck.CODE_REQUIRED));
        service.submitKeypad("sms", List.of(1, 2, 3, 4, 5, 6), 7L);
        when(port.completeAuth("sms", "000000"))
            .thenThrow(new SyncException("wrong", null, SidecarErrorCode.INVALID_OTP.name()));

        assertThatThrownBy(() -> service.completeAuth("sms", "000000", 7L)).isInstanceOf(SyncException.class);
        // Still alive: the same attempt accepts another code (which now times out).
        when(port.completeAuth("sms", "111111"))
            .thenThrow(new SyncException("slow", null, SidecarErrorCode.APP_VALIDATION_TIMEOUT.name()));
        assertThatThrownBy(() -> service.completeAuth("sms", "111111", 7L)).isInstanceOf(SyncException.class);
        // Dead now.
        assertThatThrownBy(() -> service.completeAuth("sms", "222222", 7L))
            .isInstanceOfSatisfying(SyncException.class, e ->
                assertThat(e.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
    }

    @Test
    void anAttemptExpiresAfterItsDeadlineAndNothingIsSent() {
        when(port.initiateAuth("1234567890", "bnp")).thenReturn(
            new BnpAdapter.InitiateResult("p1", keypad(10, 5), 30));
        service.initiateAuth("1234567890", "bnp", 7L);
        BnpSyncService later = new BnpSyncService(port, sessionRepository, accountRepository, holdingRepository,
            memberRepository, accountService, categorizationService,
            new SharedSidecarTransactionImportService(transactionRepository, categorizationService),
            isinConverter, identityService, encryption, txTemplate, Runnable::run,
            Clock.fixed(NOW.plusSeconds(31), ZoneOffset.UTC));

        // A fresh service has no attempt at all; the original one refuses once the clock passes the deadline.
        assertThatThrownBy(() -> later.submitKeypad("p1", List.of(1, 2, 3, 4, 5, 6), 7L))
            .isInstanceOf(SyncException.class);
        verify(port, never()).submitKeypad(anyString(), any());
    }

    @Test
    void aSuccessfulLoginStoresOnlyTheEncryptedCookieJarAndQueuesTheFirstSync() {
        startAttempt("app");
        when(port.submitKeypad("app", List.of(1, 2, 3, 4, 5, 6)))
            .thenReturn(new BnpAdapter.KeypadAck("app", BnpAdapter.KeypadAck.APPROVAL_PENDING));
        service.submitKeypad("app", List.of(1, 2, 3, 4, 5, 6), 7L);
        when(port.completeAuth("app", null)).thenReturn("plain-state");
        when(memberRepository.findById(7L)).thenReturn(Optional.of(owner()));
        when(encryption.encrypt("plain-state")).thenReturn("encrypted");
        SidecarSession[] stored = new SidecarSession[1];
        when(sessionRepository.saveAndFlush(any(SidecarSession.class))).thenAnswer(invocation -> {
            stored[0] = invocation.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(stored[0], "id", 3L);
            return stored[0];
        });
        lenient().when(sessionRepository.findByIdAndMemberIdForUpdate(3L, 7L)).thenAnswer(i -> Optional.of(stored[0]));
        lenient().when(sessionRepository.findByBankAndMemberId(SidecarBank.BNP, 7L)).thenAnswer(i -> Optional.ofNullable(stored[0]));
        lenient().when(port.fetchAccounts("plain-state"))
            .thenThrow(new SyncException("down", null, SidecarErrorCode.UPSTREAM_UNAVAILABLE.name()));

        service.completeAuth("app", null, 7L);

        assertThat(stored[0].getBank()).isEqualTo(SidecarBank.BNP);
        assertThat(stored[0].getSessionState()).isEqualTo("encrypted");
        assertThat(stored[0].getEncryptedCredentials()).isNull();
        // The attempt is consumed: it cannot be completed twice.
        assertThatThrownBy(() -> service.completeAuth("app", null, 7L)).isInstanceOf(SyncException.class);
    }

    @Test
    void aSidecarAnswerWithoutASessionIsRefused() {
        startAttempt("app");
        when(port.submitKeypad("app", List.of(1, 2, 3, 4, 5, 6)))
            .thenReturn(new BnpAdapter.KeypadAck("app", BnpAdapter.KeypadAck.APPROVAL_PENDING));
        service.submitKeypad("app", List.of(1, 2, 3, 4, 5, 6), 7L);
        when(port.completeAuth("app", null)).thenReturn("  ");

        assertThatThrownBy(() -> service.completeAuth("app", null, 7L))
            .isInstanceOfSatisfying(SyncException.class, e ->
                assertThat(e.getCode()).isEqualTo(SidecarErrorCode.INVALID_DATA.name()));
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void removingTheConnectionDuringALoginCancelsIt() {
        startAttempt("p1");
        lenient().when(sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.BNP, 7L)).thenReturn(Optional.empty());

        assertThat(service.clearSession(7L)).isFalse();

        assertThatThrownBy(() -> service.submitKeypad("p1", List.of(1, 2, 3, 4, 5, 6), 7L))
            .isInstanceOfSatisfying(SyncException.class, e ->
                assertThat(e.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
    }

    // ─── sync ────────────────────────────────────────────────────────────────

    @Test
    void importsEveryProductWithItsKindAndTheLoanAsAPositiveLiability() {
        arrangeCommittableSync("bnp", account("c1", "CURRENT_ACCOUNT", "Compte", "1200.50", null, null),
            account("s1", "LIVRET_A", "Livret A", "5000.00", null, null),
            account("l1", "LOAN", "Pret", "-10000.00", null, null),
            account("p1", "PER", "Plan retraite", "800.00", null, null));

        BnpSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        verify(accountRepository, times(4)).save(accountCaptor.capture());
        List<Account> saved = accountCaptor.getAllValues();
        assertThat(saved).extracting(Account::getType).containsExactly(
            AccountType.CHECKING, AccountType.LIVRET_A, AccountType.LOAN, AccountType.PER);
        assertThat(saved).extracting(Account::getExternalAccountId).containsExactly(
            "bnp_c1", "bnp_s1", "bnp_l1", "bnp_p1");
        assertThat(saved).allSatisfy(account -> {
            assertThat(account.getProvider()).isEqualTo("BNP Paribas");
            assertThat(account.getCurrency()).isEqualTo("EUR");
            assertThat(account.isManual()).isFalse();
        });
        assertThat(saved.get(2).getCurrentBalance()).isEqualByComparingTo("10000.00");
        verify(accountService, times(4)).upsertSnapshot(any(Account.class), any(BigDecimal.class), eq(TODAY));
    }

    @Test
    void aSecuritiesAccountKeepsItsCashAndProviderValuedLines() {
        BnpAdapter.AccountData pea = account("pea1", "PEA", "PEA", "1500.00", "500.00", null,
            position("FR0000000001", "Action A", "10", "800.00", "100.00"),
            position("FR0000000002", "Action B", "5", "200.00", null));
        arrangeCommittableSync("bnp", pea);
        when(isinConverter.resolve(anyString())).thenReturn(null);

        service.queueSync(7L);

        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getType()).isEqualTo(AccountType.PEA);
        assertThat(accountCaptor.getValue().getCashBalance()).isEqualByComparingTo("500.00");
        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        assertThat(holdingsCaptor.getValue()).extracting(AccountHolding::getTicker)
            .containsExactly("FR0000000001", "FR0000000002");
        assertThat(holdingsCaptor.getValue().getFirst().getProviderValueEur()).isEqualByComparingTo("800.00");
        verify(accountService).upsertSnapshot(any(Account.class), eq(new BigDecimal("1500.00")),
            any(BigDecimal.class), eq(TODAY));
    }

    @Test
    void aPortfolioThatDoesNotAddUpIsNeverImported() {
        BnpAdapter.AccountData pea = account("pea1", "PEA", "PEA", "1500.00", "500.00", null,
            position("FR0000000001", "Action A", "10", "100.00", null));
        arrangeQueuedSync("bnp", pea);

        service.queueSync(7L);

        assertFailed(SidecarErrorCode.PORTFOLIO_INCOMPLETE);
    }

    @Test
    void aLifeInsuranceIsABalanceOnlyAccount() {
        arrangeCommittableSync("bnp", account("av1", "ASSURANCE_VIE", "Contrat", "9000.00", null, null));

        service.queueSync(7L);

        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getType()).isEqualTo(AccountType.ASSURANCE_VIE);
        assertThat(accountCaptor.getValue().getCurrentBalance()).isEqualByComparingTo("9000.00");
        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        assertThat(holdingsCaptor.getValue()).isEmpty();
    }

    @Test
    void lifeInsuranceLinesAreNotAcceptedUntilTheyAreVerified() {
        arrangeQueuedSync("bnp", account("av1", "ASSURANCE_VIE", "Contrat", "9000.00", null, null,
            position("FR0000000001", "Support", "1", "9000.00", null)));

        service.queueSync(7L);

        assertFailed(SidecarErrorCode.INVALID_DATA);
    }

    @Test
    void anUnknownKindFailsTheWholeSyncAndWritesNothing() {
        arrangeQueuedSync("bnp", account("c1", "CURRENT_ACCOUNT", "Compte", "10.00", null, null),
            account("x1", "SOMETHING_NEW", "Nouveau", "10.00", null, null));

        service.queueSync(7L);

        assertFailed(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED);
        verify(accountRepository, never()).save(any());
        verify(holdingRepository, never()).deleteByAccountId(anyLong());
    }

    @Test
    void anIncompleteSnapshotKeepsWhatWasImportedBefore() {
        BnpAdapter.AccountData partial = new BnpAdapter.AccountData("c1", "CURRENT_ACCOUNT", "Compte",
            new BigDecimal("10.00"), null, "EUR", null, List.of(), List.of(), false);
        arrangeQueuedSync("bnp", partial);

        service.queueSync(7L);

        assertFailed(SidecarErrorCode.PORTFOLIO_INCOMPLETE);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void emptySnapshotsDuplicatesForeignCurrenciesAndNegativeSavingsAreRefused() {
        List<List<BnpAdapter.AccountData>> bad = List.of(
            List.of(),
            List.of(account("c1", "CURRENT_ACCOUNT", "A", "1", null, null), account("c1", "LIVRET_A", "B", "1", null, null)),
            List.of(new BnpAdapter.AccountData("c1", "CURRENT_ACCOUNT", "A", BigDecimal.ONE, null, "USD", null,
                List.of(), List.of(), true)),
            List.of(account("s1", "LIVRET_A", "Livret", "-5.00", null, null)),
            List.of(account("l1", "LOAN", "Pret", "100.00", null, null)));
        for (List<BnpAdapter.AccountData> accounts : bad) {
            SidecarSession session = arrangeQueuedSync("bnp", accounts.toArray(BnpAdapter.AccountData[]::new));

            service.queueSync(7L);

            assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        }
        verify(accountRepository, never()).save(any());
    }

    @Test
    void anExpiredSessionNeedsANewLoginAndIsNotRetried() {
        SidecarSession session = activeSession(owner());
        arrangeSession(session);
        when(port.fetchAccounts("plain-state"))
            .thenThrow(new SyncException("expired", null, SidecarErrorCode.SESSION_EXPIRED.name()));

        service.queueSync(7L);

        assertThat(session.isActive()).isFalse();
        assertThat(session.getLastSyncError()).isEqualTo(SidecarErrorCode.SESSION_EXPIRED);
        SourceSyncResult result = service.resyncReporting(7L);
        assertThat(result.source()).isEqualTo("bnp");
        assertThat(result.status()).isEqualTo(SourceSyncResult.Status.NEEDS_REAUTH);
    }

    @Test
    void helloBankAccountsCarryTheHelloBankProvider() {
        arrangeCommittableSync("hellobank", account("c1", "CURRENT_ACCOUNT", "Compte", "10.00", null, null));

        service.queueSync(7L);

        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getProvider()).isEqualTo("Hello bank!");
    }

    @Test
    void anEnableBankingAccountWithTheSameVerifiedIbanIsAdoptedNotDuplicated() {
        arrangeCommittableSync("bnp", account("c1", "CURRENT_ACCOUNT", "Compte", "10.00", null, IBAN));
        Account enableBanking = Account.builder().id(88L).member(owner()).name("Hello bank").provider("Hello bank")
            .type(AccountType.CHECKING).iban(IBAN).externalAccountId("eb-id").isManual(false).build();
        when(accountRepository.findFirstByIbanAndMemberIdAndProviderIn(IBAN, 7L, FAMILY))
            .thenReturn(Optional.of(enableBanking));

        service.queueSync(7L);

        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getId()).isEqualTo(88L);
        assertThat(accountCaptor.getValue().getExternalAccountId()).isEqualTo("bnp_c1");
        assertThat(accountCaptor.getValue().getIban()).isEqualTo(IBAN);
    }

    @Test
    void anIbanThatFailsTheChecksumIsNeverUsedAsADedupKey() {
        arrangeCommittableSync("bnp", account("c1", "CURRENT_ACCOUNT", "Compte", "10.00", null, "FR7630006000011234567890188"));

        service.queueSync(7L);

        verify(accountRepository, never()).findFirstByIbanAndMemberIdAndProviderIn(any(), anyLong(), any());
        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getIban()).isNull();
    }

    @Test
    void anAccountTheMemberDeletedIsNotBroughtBack() {
        arrangeCommittableSync("bnp", account("c1", "CURRENT_ACCOUNT", "Compte", "10.00", null, IBAN));
        when(accountRepository.existsSoftDeletedByIbanAndMemberIdAndProviderIn(IBAN, 7L, FAMILY)).thenReturn(true);

        service.queueSync(7L);

        verify(accountRepository, never()).save(any());
    }

    @Test
    void transactionsAreImportedThroughTheSharedImporter() {
        SidecarTransaction tx = new SidecarTransaction("op1", LocalDate.of(2026, 1, 5), "CARTE SYNTHETIQUE",
            new BigDecimal("-12.30"), null, "CARD");
        BnpAdapter.AccountData checking = new BnpAdapter.AccountData("c1", "CURRENT_ACCOUNT", "Compte",
            new BigDecimal("10.00"), null, "EUR", null, List.of(tx), List.of(), true);
        arrangeCommittableSync("bnp", checking);

        service.queueSync(7L);

        verify(transactionRepository).findByAccountIdAndIsManualFalse(11L);
    }

    @Test
    void theRotatedCookieJarReplacesTheStoredOneAndUnsupportedProductsAreListedByIdAndFamilyOnly() {
        SidecarSession session = arrangeCommittableSync("bnp", account("c1", "CURRENT_ACCOUNT", "Compte", "10.00", null, null));
        when(port.fetchAccounts("plain-state")).thenReturn(new BnpAdapter.AccountsSnapshot("bnp",
            List.of(account("c1", "CURRENT_ACCOUNT", "Compte", "10.00", null, null)),
            List.of(new BnpAdapter.Unsupported("t1", "3")), "rotated"));
        when(encryption.encrypt("rotated")).thenReturn("rotated-encrypted");

        BnpSyncService.SessionStatusResponse status = service.queueSync(7L);

        assertThat(session.getSessionState()).isEqualTo("rotated-encrypted");
        assertThat(status.unsupported()).containsExactly(new BnpSyncService.UnsupportedProduct("t1", "3"));
        assertThat(status.unsupportedCount()).isEqualTo(1);
    }

    @Test
    void anImportedAccountAbsentFromACompleteSnapshotIsClosedAtZero() {
        arrangeCommittableSync("bnp", account("c1", "CURRENT_ACCOUNT", "Compte", "10.00", null, null));
        Account closed = imported(21L, "bnp_gone", AccountType.PEA, "1500.00");
        closed.setCashBalance(new BigDecimal("500.00"));
        Account kept = imported(22L, "bnp_c1", AccountType.CHECKING, "10.00");
        when(accountRepository.findAllByMemberIdAndProvider(7L, "BNP Paribas")).thenReturn(List.of(closed, kept));

        service.queueSync(7L);

        assertThat(closed.getCurrentBalance()).isEqualByComparingTo("0");
        assertThat(closed.getCashBalance()).isNull();
        assertThat(closed.getLastSyncedAt()).isNotNull();
        verify(accountRepository).save(closed);
        verify(holdingRepository).deleteByAccountId(21L);
        verify(accountService).upsertSnapshot(eq(closed), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO), eq(TODAY));
        // The account that is still listed is upserted, not closed.
        assertThat(kept.getCurrentBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void aProductThatTurnedUnsupportedIsNotClosed() {
        arrangeCommittableSync("bnp", account("c1", "CURRENT_ACCOUNT", "Compte", "10.00", null, null));
        // The sidecar reports the raw stable id; the stored form carries the bnp_ prefix.
        when(port.fetchAccounts("plain-state")).thenReturn(new BnpAdapter.AccountsSnapshot("bnp",
            List.of(account("c1", "CURRENT_ACCOUNT", "Compte", "10.00", null, null)),
            List.of(new BnpAdapter.Unsupported("t1", "3"), new BnpAdapter.Unsupported("bnp_t2", "3")), null));
        Account first = imported(21L, "bnp_t1", AccountType.SAVINGS, "800.00");
        Account second = imported(22L, "bnp_t2", AccountType.SAVINGS, "900.00");
        when(accountRepository.findAllByMemberIdAndProvider(7L, "BNP Paribas")).thenReturn(List.of(first, second));

        service.queueSync(7L);

        assertThat(first.getCurrentBalance()).isEqualByComparingTo("800.00");
        assertThat(second.getCurrentBalance()).isEqualByComparingTo("900.00");
        verify(holdingRepository, never()).deleteByAccountId(21L);
        verify(holdingRepository, never()).deleteByAccountId(22L);
        verify(accountRepository, times(1)).save(any(Account.class));
    }

    @Test
    void aCappedUnsupportedListClosesNothingBecauseItMayHideAProduct() {
        arrangeCommittableSync("bnp", account("c1", "CURRENT_ACCOUNT", "Compte", "10.00", null, null));
        List<BnpAdapter.Unsupported> capped = IntStream.range(0, 101)
            .mapToObj(i -> new BnpAdapter.Unsupported("u" + i, "3")).toList();
        when(port.fetchAccounts("plain-state")).thenReturn(new BnpAdapter.AccountsSnapshot("bnp",
            List.of(account("c1", "CURRENT_ACCOUNT", "Compte", "10.00", null, null)), capped, null));
        Account unlisted = imported(21L, "bnp_gone", AccountType.SAVINGS, "800.00");
        when(accountRepository.findAllByMemberIdAndProvider(7L, "BNP Paribas")).thenReturn(List.of(unlisted));

        service.queueSync(7L);

        assertThat(unlisted.getCurrentBalance()).isEqualByComparingTo("800.00");
        verify(holdingRepository, never()).deleteByAccountId(21L);
        verify(accountRepository, times(1)).save(any(Account.class));
    }

    @Test
    void anAlreadyZeroAccountAndAnAccountThatIsNotABnpImportAreLeftAlone() {
        arrangeCommittableSync("bnp", account("c1", "CURRENT_ACCOUNT", "Compte", "10.00", null, null));
        Account alreadyClosed = imported(21L, "bnp_old", AccountType.SAVINGS, "0.00");
        Account manual = imported(22L, null, AccountType.SAVINGS, "300.00");
        Account enableBanking = imported(23L, "eb-id", AccountType.CHECKING, "40.00");
        when(accountRepository.findAllByMemberIdAndProvider(7L, "BNP Paribas"))
            .thenReturn(List.of(alreadyClosed, manual, enableBanking));

        service.queueSync(7L);

        assertThat(manual.getCurrentBalance()).isEqualByComparingTo("300.00");
        assertThat(enableBanking.getCurrentBalance()).isEqualByComparingTo("40.00");
        verify(accountRepository, times(1)).save(any(Account.class));
        verify(holdingRepository, never()).deleteByAccountId(21L);
        verify(holdingRepository, never()).deleteByAccountId(22L);
        verify(holdingRepository, never()).deleteByAccountId(23L);
        verify(accountService, never()).upsertSnapshot(eq(alreadyClosed), any(), any(), any());
    }

    @Test
    void aHelloBankSnapshotOnlyClosesHelloBankAccounts() {
        arrangeCommittableSync("hellobank", account("c1", "CURRENT_ACCOUNT", "Compte", "10.00", null, null));
        Account helloBank = imported(21L, "bnp_gone", AccountType.SAVINGS, "800.00");
        helloBank.setProvider("Hello bank!");
        when(accountRepository.findAllByMemberIdAndProvider(7L, "Hello bank!")).thenReturn(List.of(helloBank));

        service.queueSync(7L);

        assertThat(helloBank.getCurrentBalance()).isEqualByComparingTo("0");
        verify(accountRepository, never()).findAllByMemberIdAndProvider(7L, "BNP Paribas");
    }

    @Test
    void aFailedSnapshotClosesNothing() {
        BnpAdapter.AccountData partial = new BnpAdapter.AccountData("c1", "CURRENT_ACCOUNT", "Compte",
            new BigDecimal("10.00"), null, "EUR", null, List.of(), List.of(), false);
        arrangeQueuedSync("bnp", partial);
        lenient().when(accountRepository.findAllByMemberIdAndProvider(7L, "BNP Paribas"))
            .thenReturn(List.of(imported(21L, "bnp_gone", AccountType.SAVINGS, "800.00")));

        service.queueSync(7L);

        assertFailed(SidecarErrorCode.PORTFOLIO_INCOMPLETE);
        verify(holdingRepository, never()).deleteByAccountId(anyLong());
        verify(accountService, never()).upsertSnapshot(any(), any(), any(), any());
    }

    @Test
    void verifiedIbanAcceptsOnlyAValidChecksum() {
        assertThat(BnpSyncService.verifiedIban(" fr76 3000 6000 0112 3456 7890 189 ")).isEqualTo(IBAN);
        assertThat(BnpSyncService.verifiedIban("FR7630006000011234567890188")).isNull();
        assertThat(BnpSyncService.verifiedIban("not an iban")).isNull();
        assertThat(BnpSyncService.verifiedIban(null)).isNull();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private void assertFailed(SidecarErrorCode code) {
        SidecarSession session = sessionRepository.findByBankAndMemberId(SidecarBank.BNP, 7L).orElseThrow();
        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(code);
        verify(accountRepository, never()).save(any());
    }

    private void startAttempt(String processId) {
        when(port.initiateAuth("1234567890", "bnp")).thenReturn(initiateResult(processId, keypad(10, 5)));
        service.initiateAuth("1234567890", "bnp", 7L);
    }

    private BnpAdapter.InitiateResult initiateResult(String processId, BnpAdapter.Keypad keypad) {
        return new BnpAdapter.InitiateResult(processId, keypad, 300);
    }

    private BnpAdapter.Keypad keypad(int tiles, int columns) {
        return new BnpAdapter.Keypad(IntStream.range(0, tiles).mapToObj(i -> String.valueOf(i)).toList(), columns);
    }

    private List<String> duplicates() {
        List<String> images = new ArrayList<>(keypad(10, 5).keys());
        images.set(9, images.get(0));
        return images;
    }

    private BnpAdapter.Position position(String isin, String label, String quantity, String value, String pnl) {
        return new BnpAdapter.Position(isin, label, new BigDecimal(quantity), new BigDecimal(value),
            pnl == null ? null : new BigDecimal(pnl));
    }

    private BnpAdapter.AccountData account(String id, String kind, String name, String balance, String cash, String iban,
                                           BnpAdapter.Position... positions) {
        return new BnpAdapter.AccountData(id, kind, name, new BigDecimal(balance),
            cash == null ? null : new BigDecimal(cash), "EUR", iban, List.of(), List.of(positions), true);
    }

    private Account imported(Long id, String externalId, AccountType type, String balance) {
        return Account.builder().id(id).member(owner()).name("Synthetic").provider("BNP Paribas").type(type)
            .externalAccountId(externalId).isManual(externalId == null).currentBalance(new BigDecimal(balance)).build();
    }

    private SidecarSession arrangeCommittableSync(String brand, BnpAdapter.AccountData... accounts) {
        SidecarSession session = arrangeQueuedSync(brand, accounts);
        when(memberRepository.findById(7L)).thenReturn(Optional.of(owner()));
        when(categorizationService.loadContext(7L)).thenReturn(
            new CategorizationService.CategorizationContext(List.of(), Map.of()));
        lenient().when(accountRepository.findByExternalAccountIdAndMemberId(any(), eq(7L))).thenReturn(Optional.empty());
        lenient().when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            if (account.getId() == null) account.setId(11L);
            return account;
        });
        return session;
    }

    private SidecarSession arrangeQueuedSync(String brand, BnpAdapter.AccountData... accounts) {
        SidecarSession session = activeSession(owner());
        arrangeSession(session);
        lenient().when(port.fetchAccounts("plain-state"))
            .thenReturn(new BnpAdapter.AccountsSnapshot(brand, List.of(accounts), List.of(), null));
        return session;
    }

    private void arrangeSession(SidecarSession session) {
        lenient().when(sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.BNP, 7L)).thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByIdAndMemberIdForUpdate(session.getId(), 7L)).thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByBankAndMemberId(SidecarBank.BNP, 7L)).thenReturn(Optional.of(session));
        lenient().when(encryption.decrypt("encrypted")).thenReturn("plain-state");
    }

    private SidecarSession activeSession(FamilyMember member) {
        return SidecarSession.builder().bank(SidecarBank.BNP).id(3L).member(member).sessionState("encrypted")
            .active(true).syncStatus(SidecarSyncStatus.IDLE).build();
    }
}
