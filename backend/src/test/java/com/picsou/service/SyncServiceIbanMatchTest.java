package com.picsou.service;

import com.picsou.dto.AccountResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.Requisition;
import com.picsou.model.RequisitionStatus;
import com.picsou.port.BankConnectorPort;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Verifies SyncService IBAN-first account matching behaviour introduced to survive
 * Enable Banking v0.16.4 identification-hash rotation (Boursorama case).
 *
 * <p>Three scenarios:
 * <ol>
 *   <li>Account matched by IBAN even when the EB uid changed → no duplicate created, uid refreshed.</li>
 *   <li>Account soft-deleted and recognised by IBAN → not resurrected.</li>
 *   <li>Null currency in AccountData → account created with "EUR" default.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SyncServiceIbanMatchTest {

    @Mock BankConnectorPort bankConnector;
    @Mock AccountRepository accountRepository;
    @Mock RequisitionRepository requisitionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock TransactionRepository transactionRepository;
    @Mock CategorizationService categorizationService;
    @Mock RecurringDetectionService recurringDetectionService;

    @InjectMocks SyncService syncService;

    private static final Long MEMBER_ID = 42L;
    private static final String OLD_EB_UID = "old-hash-with-currency";
    private static final String NEW_EB_UID = "new-hash-account-number-only";
    private static final String IBAN = "FR7630006000011234567890189";
    private static final String SESSION_ID = "sess-bourso";

    private FamilyMember member;
    private Requisition linkedReq;

    @BeforeEach
    void setUp() {
        member = FamilyMember.builder().id(MEMBER_ID).build();
        linkedReq = Requisition.builder()
            .id(1L).member(member).requisitionId(SESSION_ID)
            .institutionName("Boursorama").status(RequisitionStatus.LINKED)
            .build();
    }

    /**
     * The canonical Boursorama uid-rotation scenario (EB v0.16.4):
     * the stored account has OLD_EB_UID; Enable Banking now returns NEW_EB_UID.
     * SyncService must find the account by IBAN and update its uid — not create a duplicate.
     */
    @Test
    void resyncAll_ibanMatchesExistingAccount_noNewAccountCreated_uidRefreshed() {
        Account existing = Account.builder()
            .id(10L).member(member).name("Compte Courant")
            .type(AccountType.CHECKING).provider("Boursorama")
            .currency("EUR").currentBalance(BigDecimal.valueOf(1000))
            .externalAccountId(OLD_EB_UID).iban(IBAN)
            .build();

        BankConnectorPort.AccountData data = new BankConnectorPort.AccountData(
            NEW_EB_UID, "Compte Courant", IBAN, "EUR", BigDecimal.valueOf(1500));

        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(
                RequisitionStatus.LINKED, MEMBER_ID))
            .thenReturn(List.of(linkedReq));
        when(bankConnector.fetchBalances(SESSION_ID)).thenReturn(List.of(data));

        // IBAN lookup finds the existing account (stored with old uid)
        when(accountRepository.findByIbanAndMemberId(IBAN, MEMBER_ID))
            .thenReturn(Optional.of(existing));

        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        when(accountService.toResponse(any())).thenReturn(mock(AccountResponse.class));

        syncService.resyncAll(MEMBER_ID);

        // The existing account was updated in-place
        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(saved.capture());
        Account savedAccount = saved.getValue();

        assertThat(savedAccount.getId()).isEqualTo(10L);                      // same entity — no duplicate
        assertThat(savedAccount.getExternalAccountId()).isEqualTo(NEW_EB_UID); // uid refreshed
        assertThat(savedAccount.getCurrentBalance()).isEqualByComparingTo("1500"); // balance updated
        // IBAN matched first: externalAccountId fallback lookup was never needed
        verify(accountRepository, never())
            .findByExternalAccountIdAndMemberId(NEW_EB_UID, MEMBER_ID);
    }

    /**
     * A soft-deleted Boursorama account recognised by IBAN must not be resurrected,
     * even when Enable Banking now uses a different uid for it.
     */
    @Test
    void resyncAll_softDeletedByIban_accountNotResurrected() {
        BankConnectorPort.AccountData data = new BankConnectorPort.AccountData(
            NEW_EB_UID, "Compte Courant", IBAN, "EUR", BigDecimal.valueOf(1500));

        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(
                RequisitionStatus.LINKED, MEMBER_ID))
            .thenReturn(List.of(linkedReq));
        when(bankConnector.fetchBalances(SESSION_ID)).thenReturn(List.of(data));

        // IBAN lookup: no active account
        when(accountRepository.findByIbanAndMemberId(IBAN, MEMBER_ID)).thenReturn(Optional.empty());
        // externalAccountId lookup: no active account
        when(accountRepository.findByExternalAccountIdAndMemberId(NEW_EB_UID, MEMBER_ID))
            .thenReturn(Optional.empty());
        // Soft-delete guard: found by IBAN → skip resurrection
        when(accountRepository.existsSoftDeletedByIbanAndMemberId(IBAN, MEMBER_ID)).thenReturn(true);

        syncService.resyncAll(MEMBER_ID);

        // No account saved — resurrection blocked
        verify(accountRepository, never()).save(any());
    }

    /**
     * When Enable Banking returns null currency (Boursorama post-EB v0.16.4) the account
     * must be created with "EUR" — not with a null currency that would violate the DB constraint.
     */
    @Test
    void resyncAll_nullCurrencyInAccountData_newAccountCreatedWithEurDefault() {
        BankConnectorPort.AccountData data = new BankConnectorPort.AccountData(
            NEW_EB_UID, "Compte Boursorama", IBAN, null /* null currency */, BigDecimal.valueOf(800));

        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(
                RequisitionStatus.LINKED, MEMBER_ID))
            .thenReturn(List.of(linkedReq));
        when(bankConnector.fetchBalances(SESSION_ID)).thenReturn(List.of(data));

        // No existing account by IBAN or externalId
        when(accountRepository.findByIbanAndMemberId(IBAN, MEMBER_ID)).thenReturn(Optional.empty());
        when(accountRepository.findByExternalAccountIdAndMemberId(NEW_EB_UID, MEMBER_ID))
            .thenReturn(Optional.empty());
        when(accountRepository.existsSoftDeletedByIbanAndMemberId(IBAN, MEMBER_ID)).thenReturn(false);
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(NEW_EB_UID, MEMBER_ID))
            .thenReturn(false);

        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            if (a.getId() == null) a.setId(99L);
            return a;
        });
        when(accountService.toResponse(any())).thenReturn(mock(AccountResponse.class));

        syncService.resyncAll(MEMBER_ID);

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(saved.capture());
        assertThat(saved.getValue().getCurrency()).isEqualTo("EUR");
        assertThat(saved.getValue().getIban()).isEqualTo(IBAN);
    }
}
