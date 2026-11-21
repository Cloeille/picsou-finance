package com.picsou.service;

import com.picsou.dto.AccountResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.CardNature;
import com.picsou.model.FamilyMember;
import com.picsou.model.Requisition;
import com.picsou.model.RequisitionStatus;
import com.picsou.port.BankConnectorPort;
import com.picsou.port.BankConnectorPort.AccountData;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.RequisitionRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import com.picsou.service.budget.RecurringDetectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Enable Banking card accounts (#664): a new card is created as one, and an account an earlier
 * release imported as a current account is brought up to date without overriding the user's choices.
 * Synthetic data only.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SyncServiceCardAccountTest {

    private static final long MEMBER_ID = 7L;
    private static final String SESSION = "session-cards";

    @Mock BankConnectorPort bankConnector;
    @Mock AccountRepository accountRepository;
    @Mock RequisitionRepository requisitionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock TransactionRepository transactionRepository;
    @Mock CategorizationService categorizationService;
    @Mock RecurringDetectionService recurringDetectionService;
    @Mock RequisitionLifecycleWriter requisitionLifecycleWriter;
    @Mock BankTransactionImportService bankTransactionImportService;

    private SyncService syncService;
    private Requisition requisition;

    @BeforeEach
    void setUp() {
        syncService = new SyncService(bankConnector, accountRepository, requisitionRepository,
            familyMemberRepository, accountService, transactionRepository, categorizationService,
            recurringDetectionService, requisitionLifecycleWriter, new BankLogoResolver(bankConnector),
            bankTransactionImportService);
        FamilyMember member = FamilyMember.builder().id(MEMBER_ID).displayName("Owner").build();
        requisition = Requisition.builder()
            .id(70L)
            .member(member)
            .requisitionId(SESSION)
            .institutionId("Synthetic Bank::FR::personal")
            .institutionName("Synthetic Bank")
            .logoUrl("https://logos.example/synthetic.png")
            .logoBackfillAttemptedAt(Instant.EPOCH)
            .status(RequisitionStatus.LINKED)
            .build();
        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.LINKED, MEMBER_ID))
            .thenReturn(List.of(requisition));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountService.toResponse(any(Account.class))).thenAnswer(invocation ->
            AccountResponse.from(invocation.getArgument(0), BigDecimal.ZERO));
    }

    @Test
    void newCard_isCreatedAsCardWithItsNature() {
        AccountData card = deferredCard();
        when(bankConnector.fetchBalances(SESSION)).thenReturn(List.of(card));
        when(accountRepository.findByExternalAccountIdAndMemberId(card.externalId(), MEMBER_ID))
            .thenReturn(Optional.empty());

        syncService.resyncAll(MEMBER_ID);

        Account saved = savedAccount();
        assertThat(saved.getType()).isEqualTo(AccountType.CREDIT_CARD);
        assertThat(saved.getCardNature()).isEqualTo(CardNature.DEFERRED_DEBIT);
        assertThat(saved.getName()).isEqualTo("Carte Visa Synthetic Holder");
    }

    @Test
    void newCurrentAccount_staysCheckingWithoutNature() {
        AccountData current = new AccountData("uid-current", "Synthetic current account", "FR00SYNTHETIC",
            "EUR", new BigDecimal("826.80"));
        when(bankConnector.fetchBalances(SESSION)).thenReturn(List.of(current));
        when(accountRepository.findByExternalAccountIdAndMemberId("uid-current", MEMBER_ID))
            .thenReturn(Optional.empty());

        syncService.resyncAll(MEMBER_ID);

        Account saved = savedAccount();
        assertThat(saved.getType()).isEqualTo(AccountType.CHECKING);
        assertThat(saved.getCardNature()).isNull();
    }

    @Test
    void cardImportedAsCurrentAccount_becomesCardAndLosesItsTechnicalName() {
        Account stored = storedAccount(AccountType.CHECKING, "delayed_debit");
        AccountData card = deferredCard();
        when(bankConnector.fetchBalances(SESSION)).thenReturn(List.of(card));
        when(accountRepository.findByExternalAccountIdAndMemberId(card.externalId(), MEMBER_ID))
            .thenReturn(Optional.of(stored));

        syncService.resyncAll(MEMBER_ID);

        Account saved = savedAccount();
        assertThat(saved.getType()).isEqualTo(AccountType.CREDIT_CARD);
        assertThat(saved.getCardNature()).isEqualTo(CardNature.DEFERRED_DEBIT);
        assertThat(saved.getName()).isEqualTo("Carte Visa Synthetic Holder");
    }

    @Test
    void userChoices_areKept() {
        Account stored = storedAccount(AccountType.OTHER, "My card");
        AccountData card = deferredCard();
        when(bankConnector.fetchBalances(SESSION)).thenReturn(List.of(card));
        when(accountRepository.findByExternalAccountIdAndMemberId(card.externalId(), MEMBER_ID))
            .thenReturn(Optional.of(stored));

        syncService.resyncAll(MEMBER_ID);

        Account saved = savedAccount();
        assertThat(saved.getType()).isEqualTo(AccountType.OTHER);
        assertThat(saved.getCardNature()).isNull();
        assertThat(saved.getName()).isEqualTo("My card");
    }

    @Test
    void technicalName_isReplacedEvenWhenTheUserChangedTheType() {
        Account stored = storedAccount(AccountType.OTHER, "delayed_debit");
        AccountData card = deferredCard();
        when(bankConnector.fetchBalances(SESSION)).thenReturn(List.of(card));
        when(accountRepository.findByExternalAccountIdAndMemberId(card.externalId(), MEMBER_ID))
            .thenReturn(Optional.of(stored));

        syncService.resyncAll(MEMBER_ID);

        Account saved = savedAccount();
        assertThat(saved.getType()).isEqualTo(AccountType.OTHER);
        assertThat(saved.getName()).isEqualTo("Carte Visa Synthetic Holder");
    }

    @Test
    void cardWithoutReadableBalance_keepsItsStoredBalanceAndWritesNoSnapshot() {
        Account stored = storedAccount(AccountType.CHECKING, "delayed_debit");
        AccountData card = cardWithoutBalance();
        when(bankConnector.fetchBalances(SESSION)).thenReturn(List.of(card));
        when(accountRepository.findByExternalAccountIdAndMemberId(card.externalId(), MEMBER_ID))
            .thenReturn(Optional.of(stored));

        syncService.resyncAll(MEMBER_ID);

        Account saved = savedAccount();
        assertThat(saved.getType()).isEqualTo(AccountType.CREDIT_CARD);
        assertThat(saved.getCurrentBalance()).isEqualByComparingTo("-474.87");
        verify(accountService, never()).upsertSnapshotFromNative(any(), any(), any());
        verify(bankTransactionImportService).importFor(saved, SESSION);
    }

    @Test
    void newCardWithoutReadableBalance_startsAtZero() {
        AccountData card = cardWithoutBalance();
        when(bankConnector.fetchBalances(SESSION)).thenReturn(List.of(card));
        when(accountRepository.findByExternalAccountIdAndMemberId(card.externalId(), MEMBER_ID))
            .thenReturn(Optional.empty());

        syncService.resyncAll(MEMBER_ID);

        assertThat(savedAccount().getCurrentBalance()).isEqualByComparingTo("0");
        verify(accountService, never()).upsertSnapshotFromNative(any(), any(), any());
    }

    @Test
    void knownCardNature_isNotOverwritten() {
        Account stored = storedAccount(AccountType.CREDIT_CARD, "Carte Visa Synthetic Holder");
        stored.setCardNature(CardNature.CREDIT);
        AccountData card = deferredCard();
        when(bankConnector.fetchBalances(SESSION)).thenReturn(List.of(card));
        when(accountRepository.findByExternalAccountIdAndMemberId(card.externalId(), MEMBER_ID))
            .thenReturn(Optional.of(stored));

        syncService.resyncAll(MEMBER_ID);

        assertThat(savedAccount().getCardNature()).isEqualTo(CardNature.CREDIT);
    }

    private static AccountData deferredCard() {
        return new AccountData("uid-card", "Carte Visa Synthetic Holder", null, "EUR", new BigDecimal("-581.81"),
            AccountType.CREDIT_CARD, CardNature.DEFERRED_DEBIT, "delayed_debit");
    }

    private static AccountData cardWithoutBalance() {
        return new AccountData("uid-card", "Carte Visa Synthetic Holder", null, "EUR", null,
            AccountType.CREDIT_CARD, CardNature.DEFERRED_DEBIT, "delayed_debit");
    }

    private Account storedAccount(AccountType type, String name) {
        return Account.builder()
            .id(700L)
            .member(requisition.getMember())
            .name(name)
            .type(type)
            .currency("EUR")
            .currentBalance(new BigDecimal("-474.87"))
            .externalAccountId("uid-card")
            .isManual(false)
            .requisitionId(requisition.getId())
            .build();
    }

    /** Also checks the sync itself succeeded: {@code resyncAll} swallows a failure into FAILED. */
    private Account savedAccount() {
        assertThat(requisition.getStatus()).isEqualTo(RequisitionStatus.LINKED);
        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        return captor.getValue();
    }
}
