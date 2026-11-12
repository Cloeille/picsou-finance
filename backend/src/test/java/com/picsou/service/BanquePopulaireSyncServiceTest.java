package com.picsou.service;

import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.SidecarSession;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.model.Debt;
import com.picsou.model.FamilyMember;
import com.picsou.model.Transaction;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.BanquePopulairePort;
import com.picsou.port.BanquePopulaireRegion;
import com.picsou.port.BankConnectorPort;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.SidecarSessionRepository;
import com.picsou.repository.DebtRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import com.picsou.service.budget.CategoryService;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The Banque Populaire binding of the shared BPCE engine: its prefix, regional provider label and
 * logo, lender, error codes, session table and regional login. The engine's own rules are covered once, through
 * {@link CaisseEpargneSyncServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class BanquePopulaireSyncServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final String IMAGE = "data:image/png;base64,iVBORw0KGgo=";
    private static final String CUSTOMER_ID = "12345678";
    private static final List<Integer> POSITIONS = List.of(7, 3, 0, 9, 4, 1);
    private static final String RIVES_DE_PARIS = "Banque Populaire Rives de Paris";
    private static final String LOGO = "https://logos.example/rives-de-paris.png";

    @Mock BanquePopulairePort port;
    @Mock SidecarSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock DebtRepository debtRepository;
    @Mock AccountService accountService;
    @Mock CategorizationService categorizationService;
    @Mock CategoryService categoryService;
    @Mock CaisseEpargnePerService perService;
    @Mock BankLogoResolver logoResolver;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock TransactionStatus transactionStatus;
    @Captor ArgumentCaptor<Account> accountCaptor;
    @Captor ArgumentCaptor<Iterable<Transaction>> transactionsCaptor;

    BanquePopulaireSyncService service;

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
        service = new BanquePopulaireSyncService(
            port, sessionRepository, accountRepository, transactionRepository, memberRepository, debtRepository,
            accountService, categorizationService, categoryService, encryption, txTemplate, Runnable::run,
            perService, logoResolver, Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
    }

    private BanquePopulaireSyncService serviceWith(BankLogoResolver resolver) {
        return new BanquePopulaireSyncService(
            port, sessionRepository, accountRepository, transactionRepository, memberRepository, debtRepository,
            accountService, categorizationService, categoryService, encryption, txTemplate, Runnable::run,
            perService, resolver, Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
    }

    // -- mapping ------------------------------------------------------------

    @Test
    void queueSync_importsEachKindUnderThePrefixAndTheRegionalBanksName() {
        arrangeCommittableSync(current(), account("1002", "LIVRET_A", "LIVRET A", "5000.00"),
            account("1003", "LIVRET_JEUNE", "LIVRET JEUNE", "1600.00"),
            account("1004", "LDDS", "LDDS", "3000.00"),
            account("1005", "SAVINGS", "COMPTE SUR LIVRET", "250.00"),
            account("1006", "PEL", "PLAN EPARGNE LOGEMENT", "15000.00"),
            account("contract:TD1", "TERM_DEPOSIT", "TIP TOP'P 3 ANS", "1234.56"),
            account("contract:CS1", "COOPERATIVE_SHARES", "PART SOCIALE BP", "250.00"),
            account("contract:PERIB123456789", "PER", "PLAN EPARGNE RETRAITE", "2500.00"),
            card());

        BanquePopulaireSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        verify(accountRepository, times(10)).save(accountCaptor.capture());
        List<Account> saved = accountCaptor.getAllValues();
        assertThat(saved).extracting(Account::getType).containsExactly(
            AccountType.CHECKING, AccountType.LIVRET_A, AccountType.LIVRET_JEUNE, AccountType.LDDS,
            AccountType.SAVINGS, AccountType.PEL, AccountType.SAVINGS, AccountType.SAVINGS,
            AccountType.PER, AccountType.CREDIT_CARD);
        assertThat(saved).extracting(Account::getExternalAccountId)
            .containsExactly("bp_1001", "bp_1002", "bp_1003", "bp_1004", "bp_1005", "bp_1006",
                "bp_contract:TD1", "bp_contract:CS1", "bp_contract:PERIB123456789", "bp_2001");
        assertThat(saved).allSatisfy(account -> {
            assertThat(account.getProvider()).isEqualTo(RIVES_DE_PARIS);
            assertThat(account.getCurrency()).isEqualTo("EUR");
            assertThat(account.isManual()).isFalse();
        });
        assertThat(BanquePopulaireSyncService.PROVIDER).isEqualTo("Banque Populaire");
    }

    @Test
    void queueSync_importsALivretJeuneWithItsTransactions() {
        BanquePopulairePort.AccountData jeune = new BanquePopulairePort.AccountData("1003", "LIVRET_JEUNE", null,
            new BigDecimal("1600.00"), "EUR", null, false, null, new BigDecimal("1600.00"), null, null,
            null, null, null, List.of(tx("j1", "2026-10-31", "1.25", "INTERETS")), true, null);
        arrangeCommittableSync(jeune);

        service.queueSync(7L);

        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getType()).isEqualTo(AccountType.LIVRET_JEUNE);
        assertThat(accountCaptor.getValue().getName()).isEqualTo("Livret Jeune");
        assertThat(accountCaptor.getValue().getColor()).isEqualTo("#f59e0b");
        verify(transactionRepository).saveAllAndFlush(transactionsCaptor.capture());
        assertThat(transactionsCaptor.getValue()).singleElement().satisfies(row -> {
            assertThat(row.getExternalId()).isEqualTo("bp_j1");
            assertThat(row.getAmount()).isEqualByComparingTo("1.25");
            assertThat(row.getDescription()).isEqualTo("INTERETS");
        });
    }

    @Test
    void queueSync_importsACompteSurLivretAndAPelWithSavingsDefaultsAndTransactions() {
        BanquePopulairePort.AccountData csl = new BanquePopulairePort.AccountData("1005", "SAVINGS", null,
            new BigDecimal("250.00"), "EUR", null, false, null, null, null, null,
            null, null, null, List.of(tx("s1", "2026-10-31", "50.00", "VERSEMENT")), true, null);
        BanquePopulairePort.AccountData pel = new BanquePopulairePort.AccountData("1006", "PEL", " ",
            new BigDecimal("15000.00"), "EUR", null, false, null, new BigDecimal("61200.00"), null, null,
            null, null, null, List.of(tx("p1", "2026-10-31", "45.00", "VERSEMENT PEL")), true, null);
        arrangeCommittableSync(csl, pel);

        service.queueSync(7L);

        verify(accountRepository, times(2)).save(accountCaptor.capture());
        List<Account> saved = accountCaptor.getAllValues();
        assertThat(saved).extracting(Account::getType).containsExactly(AccountType.SAVINGS, AccountType.PEL);
        assertThat(saved).extracting(Account::getName).containsExactly("Compte sur livret", "PEL");
        assertThat(saved).extracting(Account::getColor).containsOnly("#f59e0b");
        verify(transactionRepository, times(2)).saveAllAndFlush(transactionsCaptor.capture());
        assertThat(transactionsCaptor.getAllValues().get(0)).singleElement().satisfies(row -> {
            assertThat(row.getExternalId()).isEqualTo("bp_s1");
            assertThat(row.getAmount()).isEqualByComparingTo("50.00");
        });
        assertThat(transactionsCaptor.getAllValues().get(1)).singleElement().satisfies(row -> {
            assertThat(row.getExternalId()).isEqualTo("bp_p1");
            assertThat(row.getAmount()).isEqualByComparingTo("45.00");
        });
    }

    @Test
    void importsTermDepositAsSavingsAccount() {
        BanquePopulairePort.AccountData termDeposit = new BanquePopulairePort.AccountData(
            "contract:TD1", "TERM_DEPOSIT", null, new BigDecimal("1234.56"), "EUR", null, false,
            null, null, null, null, null, null, null, List.of(), true, null);
        arrangeCommittableSync(termDeposit);

        service.queueSync(7L);

        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getType()).isEqualTo(AccountType.SAVINGS);
        assertThat(accountCaptor.getValue().getExternalAccountId()).isEqualTo("bp_contract:TD1");
        assertThat(accountCaptor.getValue().getName()).isEqualTo("Compte à terme");
        assertThat(accountCaptor.getValue().getCurrentBalance()).isEqualByComparingTo("1234.56");
        assertThat(accountCaptor.getValue().getColor()).isEqualTo("#f59e0b");
        verify(transactionRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void importsCooperativeSharesAsSavingsAccount() {
        BanquePopulairePort.AccountData shares = new BanquePopulairePort.AccountData(
            "contract:CS1", "COOPERATIVE_SHARES", " ", new BigDecimal("250.00"), "EUR", null, false,
            null, null, null, null, null, null, null, List.of(), true, null);
        arrangeCommittableSync(shares);

        service.queueSync(7L);

        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getType()).isEqualTo(AccountType.SAVINGS);
        assertThat(accountCaptor.getValue().getExternalAccountId()).isEqualTo("bp_contract:CS1");
        assertThat(accountCaptor.getValue().getName()).isEqualTo("Parts sociales");
        assertThat(accountCaptor.getValue().getCurrentBalance()).isEqualByComparingTo("250.00");
        assertThat(accountCaptor.getValue().getColor()).isEqualTo("#f59e0b");
        verify(transactionRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void queueSync_namesUnnamedAccountsAndTransactionsAfterBanquePopulaire() {
        BanquePopulairePort.AccountData unnamed = new BanquePopulairePort.AccountData("1001", "CURRENT_ACCOUNT", " ",
            new BigDecimal("10.00"), "EUR", null, false, null, null, null, null, null, null, null,
            List.of(tx("t1", "2026-11-01", "-1.00", null)), true, null);
        arrangeCommittableSync(unnamed, card());

        service.queueSync(7L);

        verify(accountRepository, times(2)).save(accountCaptor.capture());
        assertThat(accountCaptor.getAllValues()).extracting(Account::getName)
            .containsExactly("Banque Populaire account", "Banque Populaire card");
        verify(transactionRepository, times(2)).saveAllAndFlush(transactionsCaptor.capture());
        assertThat(transactionsCaptor.getAllValues().get(0)).singleElement()
            .extracting(Transaction::getDescription).isEqualTo("Banque Populaire transaction");
    }

    @Test
    void queueSync_handlesALoanGenericallyWithBanquePopulaireAsTheLender() {
        BanquePopulairePort.LoanDetails details = new BanquePopulairePort.LoanDetails(new BigDecimal("10000.00"),
            new BigDecimal("0.045"), new BigDecimal("230.00"), null, LocalDate.of(2026, 1, 10),
            LocalDate.of(2026, 2, 5), LocalDate.of(2028, 1, 5), 24, null);
        BanquePopulairePort.AccountData loan = new BanquePopulairePort.AccountData("contract:L1", "LOAN", null,
            new BigDecimal("-9000.00"), "EUR", null, false, null, null, null, null, null, null, null,
            List.of(), true, details);
        arrangeCommittableSync(loan);
        when(debtRepository.findByAccountId(any())).thenReturn(Optional.empty());

        service.queueSync(7L);

        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getType()).isEqualTo(AccountType.LOAN);
        assertThat(accountCaptor.getValue().getExternalAccountId()).isEqualTo("bp_contract:L1");
        assertThat(accountCaptor.getValue().getName()).isEqualTo("Banque Populaire loan");
        assertThat(accountCaptor.getValue().getCurrentBalance()).isEqualByComparingTo("9000.00");
        ArgumentCaptor<Debt> debt = ArgumentCaptor.forClass(Debt.class);
        verify(debtRepository).save(debt.capture());
        assertThat(debt.getValue().getLenderName()).isEqualTo("Banque Populaire");
    }

    @Test
    void queueSync_keepsAnIdAlreadyCarryingThePrefix() {
        arrangeCommittableSync(account("bp_1002", "LIVRET_A", "LIVRET A", "1.00"));

        service.queueSync(7L);

        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getExternalAccountId()).isEqualTo("bp_1002");
    }

    // -- branding -----------------------------------------------------------

    @Test
    void queueSync_givesNewAccountsTheRegionalBanksLogoLookedUpOncePerSync() {
        when(logoResolver.logoUrlOrNull("FR", null, RIVES_DE_PARIS)).thenReturn(LOGO);
        arrangeCommittableSync(current(), account("1002", "LIVRET_A", "LIVRET A", "5000.00"));

        service.queueSync(7L);

        verify(accountRepository, times(2)).save(accountCaptor.capture());
        assertThat(accountCaptor.getAllValues()).allSatisfy(account -> {
            assertThat(account.getProvider()).isEqualTo(RIVES_DE_PARIS);
            assertThat(account.getLogoUrl()).isEqualTo(LOGO);
        });
        verify(logoResolver, times(1)).logoUrlOrNull(anyString(), any(), anyString());
    }

    @Test
    void queueSync_usesTheRegionTheSessionWasOpenedAt() {
        SidecarSession session = arrangeCommittableSync(BanquePopulaireRegion.BRED, current());

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getProvider()).isEqualTo("BRED Banque Populaire");
        verify(logoResolver).logoUrlOrNull("FR", null, "BRED Banque Populaire");
    }

    @Test
    void queueSync_updatesTheProviderOfAnAccountImportedUnderAnotherLabelAndFillsAMissingLogo() {
        when(logoResolver.logoUrlOrNull("FR", null, RIVES_DE_PARIS)).thenReturn(LOGO);
        Account stored = Account.builder().id(40L).externalAccountId("bp_1001")
            .provider("Banque Populaire").logoUrl(null).build();
        arrangeCommittableSync(current());
        when(accountRepository.findByExternalAccountIdAndMemberId("bp_1001", 7L)).thenReturn(Optional.of(stored));

        service.queueSync(7L);

        assertThat(stored.getProvider()).isEqualTo(RIVES_DE_PARIS);
        assertThat(stored.getLogoUrl()).isEqualTo(LOGO);
    }

    @Test
    void queueSync_neverOverwritesALogoAlreadySet() {
        when(logoResolver.logoUrlOrNull("FR", null, RIVES_DE_PARIS)).thenReturn(LOGO);
        Account stored = Account.builder().id(40L).externalAccountId("bp_1001")
            .provider(RIVES_DE_PARIS).logoUrl("https://logos.example/earlier.png").build();
        arrangeCommittableSync(current());
        when(accountRepository.findByExternalAccountIdAndMemberId("bp_1001", 7L)).thenReturn(Optional.of(stored));

        service.queueSync(7L);

        assertThat(stored.getLogoUrl()).isEqualTo("https://logos.example/earlier.png");
        assertThat(stored.getProvider()).isEqualTo(RIVES_DE_PARIS);
    }

    @Test
    void queueSync_importsWithoutALogoWhenTheCatalogHasNoMatch() {
        when(logoResolver.logoUrlOrNull("FR", null, RIVES_DE_PARIS)).thenReturn(null);
        SidecarSession session = arrangeCommittableSync(current());

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getProvider()).isEqualTo(RIVES_DE_PARIS);
        assertThat(accountCaptor.getValue().getLogoUrl()).isNull();
    }

    @Test
    void queueSync_succeedsWithoutALogoWhenEnableBankingIsNotConfigured() {
        BankConnectorPort unconfigured = org.mockito.Mockito.mock(BankConnectorPort.class);
        when(unconfigured.searchInstitutions(anyString(), anyString()))
            .thenThrow(new SyncException("Enable Banking Application ID is missing"));
        service = serviceWith(new BankLogoResolver(unconfigured));
        SidecarSession session = arrangeCommittableSync(current());

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getProvider()).isEqualTo(RIVES_DE_PARIS);
        assertThat(accountCaptor.getValue().getLogoUrl()).isNull();
        verify(unconfigured, times(1)).searchInstitutions(RIVES_DE_PARIS, "FR");
    }

    @Test
    void queueSync_keepsTheRegionalNameWhenTheLookupThrowsAnyway() {
        when(logoResolver.logoUrlOrNull(anyString(), any(), anyString())).thenThrow(new IllegalStateException());
        SidecarSession session = arrangeCommittableSync(current());

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getProvider()).isEqualTo(RIVES_DE_PARIS);
        assertThat(accountCaptor.getValue().getLogoUrl()).isNull();
    }

    @Test
    void aRefusedSnapshotCostsNoLogoLookup() {
        arrangeCommittableSync(current(), account("1009", "PEA", "PEA", "1.00"));

        service.queueSync(7L);

        verifyNoInteractions(logoResolver);
    }

    // -- failures -----------------------------------------------------------

    @Test
    void anExpiredSessionIsRecordedWithTheBanquePopulaireCodeAndDeactivated() {
        SidecarSession session = activeSession();
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenThrow(
            new SyncException("expired", null, SidecarErrorCode.SESSION_EXPIRED.name()));

        BanquePopulaireSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(SidecarErrorCode.SESSION_EXPIRED);
        assertThat(result.isActive()).isFalse();
        verify(accountRepository, never()).save(any());
    }

    @Test
    void anUnknownKindRefusesTheWholeSnapshot() {
        SidecarSession session = arrangeCommittableSync(current(), account("1009", "PEA", "PEA", "1.00"));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void recoverInterruptedSyncsFailsOnlyTheBanquePopulaireRows() {
        service.recoverInterruptedSyncs();

        verify(sessionRepository).markInterruptedSyncsFailed(
            eq(SidecarBank.BANQUE_POPULAIRE), eq(List.of(SidecarSyncStatus.QUEUED, SidecarSyncStatus.RUNNING)),
            eq(SidecarSyncStatus.FAILED), any(Instant.class), eq(SidecarErrorCode.INTERNAL_ERROR));
    }

    @Test
    void theStatusWithoutASessionIsInactiveAndIdle() {
        when(sessionRepository.findByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.empty());

        BanquePopulaireSyncService.SessionStatusResponse status = service.getStatus(7L);

        assertThat(status.isActive()).isFalse();
        assertThat(status.syncStatus()).isEqualTo(SidecarSyncStatus.IDLE);
        assertThat(status.unsupportedCount()).isZero();
    }

    // -- login --------------------------------------------------------------

    @Test
    void initiateAuth_passesTheRegionToTheSidecarOnceAndStoresNothing() {
        when(port.initiateAuth(CUSTOMER_ID, BanquePopulaireRegion.OCCITANE))
            .thenReturn(new BanquePopulairePort.InitiateResult("proc-1", keypad(), 90));

        BpceSyncEngine.InitiateResponse response =
            service.initiateAuth(CUSTOMER_ID, BanquePopulaireRegion.OCCITANE, 7L);

        assertThat(response.processId()).isEqualTo("proc-1");
        assertThat(response.expiresInSeconds()).isEqualTo(90);
        verify(port, times(1)).initiateAuth(anyString(), any());
        verifyNoInteractions(sessionRepository, encryption, logoResolver);
    }

    @Test
    void theFullLoginStoresTheEncryptedSessionInTheBanquePopulaireTable() {
        when(port.initiateAuth(CUSTOMER_ID, BanquePopulaireRegion.BRED))
            .thenReturn(new BanquePopulairePort.InitiateResult("proc-1", keypad(), 90));
        when(port.completeAuth("proc-1")).thenReturn("{\"cookies\":[]}");
        FamilyMember member = member();
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.empty());
        when(encryption.encrypt("{\"cookies\":[]}")).thenReturn("encrypted-state");
        when(sessionRepository.findByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.empty());

        service.initiateAuth(CUSTOMER_ID, BanquePopulaireRegion.BRED, 7L);
        service.submitKeypad("proc-1", POSITIONS, 7L);
        service.completeAuth("proc-1", 7L);

        ArgumentCaptor<SidecarSession> stored = ArgumentCaptor.forClass(SidecarSession.class);
        verify(sessionRepository).saveAndFlush(stored.capture());
        assertThat(stored.getValue().getSessionState()).isEqualTo("encrypted-state");
        assertThat(stored.getValue().isActive()).isTrue();
        assertThat(stored.getValue().getSyncStatus()).isEqualTo(SidecarSyncStatus.IDLE);
        assertThat(stored.getValue().getRegion()).isEqualTo(BanquePopulaireRegion.BRED);
        verify(port).submitKeypad("proc-1", POSITIONS);
    }

    @Test
    void aNewLoginReplacesTheSessionAndItsRegion() {
        SidecarSession earlier = activeSession();
        when(port.initiateAuth(CUSTOMER_ID, BanquePopulaireRegion.SUD))
            .thenReturn(new BanquePopulairePort.InitiateResult("proc-2", keypad(), 90));
        when(port.completeAuth("proc-2")).thenReturn("{\"cookies\":[]}");
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member()));
        when(sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.of(earlier));
        when(encryption.encrypt("{\"cookies\":[]}")).thenReturn("encrypted-state");
        when(sessionRepository.findByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.empty());

        service.initiateAuth(CUSTOMER_ID, BanquePopulaireRegion.SUD, 7L);
        service.submitKeypad("proc-2", POSITIONS, 7L);
        service.completeAuth("proc-2", 7L);

        verify(sessionRepository).delete(earlier);
        ArgumentCaptor<SidecarSession> stored = ArgumentCaptor.forClass(SidecarSession.class);
        verify(sessionRepository).saveAndFlush(stored.capture());
        assertThat(stored.getValue()).isNotSameAs(earlier);
        assertThat(stored.getValue().getRegion()).isEqualTo(BanquePopulaireRegion.SUD);
    }

    @Test
    void eachPendingLoginKeepsItsOwnRegion() {
        when(port.initiateAuth(CUSTOMER_ID, BanquePopulaireRegion.NORD))
            .thenReturn(new BanquePopulairePort.InitiateResult("proc-n", keypad(), 90));
        when(port.initiateAuth("B7654321", BanquePopulaireRegion.OCCITANE))
            .thenReturn(new BanquePopulairePort.InitiateResult("proc-o", keypad(), 90));
        when(port.completeAuth("proc-n")).thenReturn("state-n");
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member()));
        when(sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.empty());
        when(encryption.encrypt("state-n")).thenReturn("encrypted-n");
        when(sessionRepository.findByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.empty());

        service.initiateAuth(CUSTOMER_ID, BanquePopulaireRegion.NORD, 7L);
        service.initiateAuth("B7654321", BanquePopulaireRegion.OCCITANE, 7L);
        service.submitKeypad("proc-n", POSITIONS, 7L);
        service.completeAuth("proc-n", 7L);

        ArgumentCaptor<SidecarSession> stored = ArgumentCaptor.forClass(SidecarSession.class);
        verify(sessionRepository).saveAndFlush(stored.capture());
        assertThat(stored.getValue().getRegion()).isEqualTo(BanquePopulaireRegion.NORD);
    }

    @Test
    void storeSessionStoresTheRegionItIsGiven() {
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member()));
        when(sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.empty());
        when(encryption.encrypt("cookies")).thenReturn("encrypted-state");
        when(sessionRepository.findByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.empty());

        service.storeSession("cookies", BanquePopulaireRegion.VAL_DE_FRANCE, 7L);

        ArgumentCaptor<SidecarSession> stored = ArgumentCaptor.forClass(SidecarSession.class);
        verify(sessionRepository).saveAndFlush(stored.capture());
        assertThat(stored.getValue().getRegion()).isEqualTo(BanquePopulaireRegion.VAL_DE_FRANCE);
        verify(port, never()).fetchAccounts(anyString());
    }

    @Test
    void aLoginCannotStartWithoutARegion() {
        assertThatThrownBy(() -> service.initiateAuth(CUSTOMER_ID, null, 7L))
            .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(port);
    }

    @Test
    void anotherMembersProcessIdIsRefusedWithTheBanquePopulaireWording() {
        when(port.initiateAuth(CUSTOMER_ID, BanquePopulaireRegion.SUD))
            .thenReturn(new BanquePopulairePort.InitiateResult("proc-1", keypad(), 90));
        service.initiateAuth(CUSTOMER_ID, BanquePopulaireRegion.SUD, 7L);

        assertThatThrownBy(() -> service.submitKeypad("proc-1", POSITIONS, 8L))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name());
                assertThat(error.getMessage()).contains("Banque Populaire");
            });
        verify(port, never()).submitKeypad(anyString(), any());
        verify(sessionRepository, never()).findByBankAndMemberId(any(), anyLong());
    }

    @Test
    void theServiceKeepsNoFieldThatCouldHoldACredential() {
        // The pending logins, region included, live in the shared engine, so it is checked too.
        for (Class<?> owner : List.of(BanquePopulaireSyncService.class, BpceSyncEngine.class)) {
            for (var field : owner.getDeclaredFields()) {
                assertThat(field.getName().toLowerCase()).as(owner.getSimpleName() + "." + field.getName())
                    .doesNotContain("password").doesNotContain("credential")
                    .doesNotContain("customer").doesNotContain("positions");
            }
            for (var type : owner.getDeclaredClasses()) {
                for (var component : type.getRecordComponents() == null
                    ? new java.lang.reflect.RecordComponent[0] : type.getRecordComponents()) {
                    assertThat(component.getName().toLowerCase()).as(type.getSimpleName() + "." + component.getName())
                        .doesNotContain("password")
                        .doesNotContain("positions");
                    if (component.getName().toLowerCase().contains("customer")) {
                        assertThat(type.getSimpleName()).isEqualTo("StoredLogin");
                    }
                }
            }
        }
    }

    // -- fixtures -----------------------------------------------------------

    private SidecarSession arrangeCommittableSync(BanquePopulairePort.AccountData... accounts) {
        return arrangeCommittableSync(BanquePopulaireRegion.RIVES_DE_PARIS, accounts);
    }

    private SidecarSession arrangeCommittableSync(
        BanquePopulaireRegion region, BanquePopulairePort.AccountData... accounts
    ) {
        FamilyMember member = member();
        SidecarSession session = activeSession(region);
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state"))
            .thenReturn(new BanquePopulairePort.AccountsSnapshot(List.of(accounts), List.of()));
        lenient().when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        lenient().when(accountRepository.findByExternalAccountIdAndMemberId(any(), eq(7L)))
            .thenReturn(Optional.empty());
        AtomicLong ids = new AtomicLong(10);
        lenient().when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            if (account.getId() == null) account.setId(ids.incrementAndGet());
            return account;
        });
        return session;
    }

    private void arrangeQueuedSession(SidecarSession session) {
        lenient().when(sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByIdAndMemberIdForUpdate(session.getId(), 7L))
            .thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.of(session));
        lenient().when(encryption.decrypt("encrypted")).thenReturn("plain-state");
    }

    private static FamilyMember member() {
        return FamilyMember.builder().id(7L).displayName("Owner").build();
    }

    private static SidecarSession activeSession() {
        return activeSession(BanquePopulaireRegion.RIVES_DE_PARIS);
    }

    private static SidecarSession activeSession(BanquePopulaireRegion region) {
        return SidecarSession.builder()
            .id(3L)
            .bank(SidecarBank.BANQUE_POPULAIRE)
            .member(member())
            .region(region)
            .sessionState("encrypted")
            .active(true)
            .syncStatus(SidecarSyncStatus.IDLE)
            .build();
    }

    private static BanquePopulairePort.Keypad keypad() {
        return new BanquePopulairePort.Keypad(Collections.nCopies(10, IMAGE), 5);
    }

    private static BanquePopulairePort.Transaction tx(String id, String date, String amount, String label) {
        return new BanquePopulairePort.Transaction(id, LocalDate.parse(date), LocalDate.parse(date),
            new BigDecimal(amount), "EUR", label, null);
    }

    private static BanquePopulairePort.AccountData current() {
        return new BanquePopulairePort.AccountData("1001", "CURRENT_ACCOUNT", "COMPTE CHEQUES",
            new BigDecimal("1234.56"), "EUR", "FR0000000000000000000000000", false,
            new BigDecimal("500.00"), null, null, null, null, null, null,
            new ArrayList<>(List.of(tx("t1", "2026-11-01", "-12.30", "SUPERMARCHE"))), true, null);
    }

    private static BanquePopulairePort.AccountData account(String id, String kind, String name, String balance) {
        return new BanquePopulairePort.AccountData(id, kind, name, new BigDecimal(balance), "EUR", null, false,
            null, null, null, null, null, null, null, List.of(), true, null);
    }

    private static BanquePopulairePort.AccountData card() {
        return new BanquePopulairePort.AccountData("2001", "CARD", null,
            new BigDecimal("-87.10"), "EUR", null, false,
            null, null, null, null, "DEFERRED_DEBIT", "1001", LocalDate.of(2026, 11, 30),
            List.of(new BanquePopulairePort.Transaction("c1", LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 30), new BigDecimal("-87.10"), "EUR", "RESTAURANT", "04")), true, null);
    }
}
