package com.picsou.service;

import com.picsou.dto.FamilyDashboardResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.Goal;
import com.picsou.model.SharedResource;
import com.picsou.model.SharingLevel;
import com.picsou.model.SharingSettings;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.GoalManualContributionRepository;
import com.picsou.repository.GoalRepository;
import com.picsou.repository.SharedResourceRepository;
import com.picsou.repository.SharingSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FamilyViewServiceTest {

    @Mock FamilyMemberRepository memberRepository;
    @Mock SharingSettingsRepository sharingSettingsRepository;
    @Mock SharedResourceRepository sharedResourceRepository;
    @Mock AccountRepository accountRepository;
    @Mock GoalRepository goalRepository;
    @Mock AccountService accountService;
    @Mock GoalManualContributionRepository contributionRepository;

    @Mock AccountAccessResolver accessResolver;

    @InjectMocks FamilyViewService familyViewService;

    @BeforeEach
    void stubOwnershipShares() {
        // Mirrors the batch resolver: every fixture account is wholly owned, so weighting is the
        // identity and these tests keep measuring what they were written to measure.
        lenient().when(accessResolver.sharesFor(any(), any())).thenAnswer(inv -> {
            java.util.Collection<com.picsou.model.Account> accounts = inv.getArgument(0);
            java.util.Map<Long, java.math.BigDecimal> shares = new java.util.HashMap<>();
            for (com.picsou.model.Account a : accounts) {
                shares.put(a.getId(), new java.math.BigDecimal("100"));
            }
            return shares;
        });
    }

    @Test
    void getFamilyDashboard_manualAccounts_usesMemberScopedRepository() {
        FamilyMember viewer = FamilyMember.builder().id(1L).displayName("Viewer").build();
        FamilyMember owner = FamilyMember.builder().id(2L).displayName("Owner").build();
        Account account = Account.builder()
            .id(10L)
            .name("Shared account")
            .type(AccountType.SAVINGS)
            .currency("EUR")
            .currentBalance(new BigDecimal("123"))
            .build();
        SharedResource resource = SharedResource.builder()
            .ownerMember(owner)
            .resourceType("ACCOUNT")
            .resourceId(10L)
            .build();

        when(memberRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(viewer, owner));
        when(sharingSettingsRepository.findByMemberIdAndResourceType(2L, "ACCOUNT"))
            .thenReturn(Optional.of(new SharingSettings(null, owner, "ACCOUNT", SharingLevel.MANUAL)));
        when(sharingSettingsRepository.findByMemberIdAndResourceType(2L, "GOAL"))
            .thenReturn(Optional.empty());
        when(sharedResourceRepository.findAllByOwnerMemberIdAndResourceType(2L, "ACCOUNT"))
            .thenReturn(List.of(resource));
        when(accountRepository.findByIdInAndMemberId(List.of(10L), 2L)).thenReturn(List.of(account));
        when(accountService.signedLiveBalanceEur(account)).thenReturn(new BigDecimal("123"));

        FamilyDashboardResponse response = familyViewService.getFamilyDashboard(1L);

        assertThat(response.sharedAccounts()).hasSize(1);
        assertThat(response.totalSharedNetWorth()).isEqualByComparingTo("123");
        verify(accountRepository).findByIdInAndMemberId(List.of(10L), 2L);
        verify(accountRepository, never()).findAllById(any());
    }

    @Test
    void getFamilyDashboard_manualGoals_usesMemberScopedRepository() {
        FamilyMember viewer = FamilyMember.builder().id(1L).displayName("Viewer").build();
        FamilyMember owner = FamilyMember.builder().id(2L).displayName("Owner").build();
        Goal goal = Goal.builder()
            .id(20L)
            .name("Shared goal")
            .targetAmount(new BigDecimal("1200"))
            .deadline(LocalDate.now().plusMonths(6))
            .accounts(List.of())
            .member(owner)
            .build();
        SharedResource resource = SharedResource.builder()
            .ownerMember(owner)
            .resourceType("GOAL")
            .resourceId(20L)
            .build();

        when(memberRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(viewer, owner));
        when(sharingSettingsRepository.findByMemberIdAndResourceType(2L, "ACCOUNT"))
            .thenReturn(Optional.empty());
        when(sharingSettingsRepository.findByMemberIdAndResourceType(2L, "GOAL"))
            .thenReturn(Optional.of(new SharingSettings(null, owner, "GOAL", SharingLevel.MANUAL)));
        when(sharedResourceRepository.findAllByOwnerMemberIdAndResourceType(2L, "GOAL"))
            .thenReturn(List.of(resource));
        when(goalRepository.findByIdInAndMemberId(List.of(20L), 2L)).thenReturn(List.of(goal));

        FamilyDashboardResponse response = familyViewService.getFamilyDashboard(1L);

        assertThat(response.sharedGoals()).hasSize(1);
        assertThat(response.sharedGoals().getFirst().id()).isEqualTo(20L);
        verify(goalRepository).findByIdInAndMemberId(List.of(20L), 2L);
        verify(goalRepository, never()).findAllById(any());
    }

    // ─── Loan sign convention: shared loans reduce family net worth ──────────

    @Test
    void getFamilyDashboard_sharedLoan_countsNegativelyInNetWorth() {
        FamilyMember viewer = FamilyMember.builder().id(1L).displayName("Viewer").build();
        FamilyMember owner = FamilyMember.builder().id(2L).displayName("Owner").build();
        Account checking = Account.builder()
            .id(10L)
            .name("Checking")
            .type(AccountType.CHECKING)
            .currency("EUR")
            .currentBalance(new BigDecimal("2000"))
            .build();
        Account loan = Account.builder()
            .id(11L)
            .name("Mortgage")
            .type(AccountType.LOAN)
            .currency("EUR")
            .currentBalance(new BigDecimal("10000"))
            .build();

        when(memberRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(viewer, owner));
        when(sharingSettingsRepository.findByMemberIdAndResourceType(2L, "ACCOUNT"))
            .thenReturn(Optional.of(new SharingSettings(null, owner, "ACCOUNT", SharingLevel.ALL)));
        when(sharingSettingsRepository.findByMemberIdAndResourceType(2L, "GOAL"))
            .thenReturn(Optional.empty());
        when(accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(2L))
            .thenReturn(List.of(checking, loan));
        when(accountService.signedLiveBalanceEur(checking)).thenReturn(new BigDecimal("2000"));
        when(accountService.signedLiveBalanceEur(loan)).thenReturn(new BigDecimal("-10000"));

        FamilyDashboardResponse response = familyViewService.getFamilyDashboard(1L);

        assertThat(response.sharedAccounts()).hasSize(2);
        FamilyDashboardResponse.SharedAccountInfo loanInfo = response.sharedAccounts().stream()
            .filter(a -> a.id().equals(11L))
            .findFirst()
            .orElseThrow();
        // The shared loan row shows the debt as negative...
        assertThat(loanInfo.balanceEur()).isEqualByComparingTo("-10000");
        // ...so the family net worth is assets minus debts: 2000 − 10000.
        assertThat(response.totalSharedNetWorth()).isEqualByComparingTo("-8000");
    }

    @Test
    void getFamilyDashboard_sharedGoalWithLoan_progressCountsLoanNegatively() {
        FamilyMember viewer = FamilyMember.builder().id(1L).displayName("Viewer").build();
        FamilyMember owner = FamilyMember.builder().id(2L).displayName("Owner").build();
        Account checking = Account.builder()
            .id(10L)
            .name("Checking")
            .type(AccountType.CHECKING)
            .currency("EUR")
            .currentBalance(new BigDecimal("2000"))
            .build();
        Account loan = Account.builder()
            .id(11L)
            .name("Mortgage")
            .type(AccountType.LOAN)
            .currency("EUR")
            .currentBalance(new BigDecimal("10000"))
            .build();
        Goal goal = Goal.builder()
            .id(20L)
            .name("House")
            .targetAmount(new BigDecimal("50000"))
            .deadline(LocalDate.now().plusMonths(6))
            .accounts(List.of(checking, loan))
            .member(owner)
            .build();

        when(memberRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(viewer, owner));
        when(sharingSettingsRepository.findByMemberIdAndResourceType(2L, "ACCOUNT"))
            .thenReturn(Optional.empty());
        when(sharingSettingsRepository.findByMemberIdAndResourceType(2L, "GOAL"))
            .thenReturn(Optional.of(new SharingSettings(null, owner, "GOAL", SharingLevel.ALL)));
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(2L)).thenReturn(List.of(goal));
        when(accountService.signedLiveBalanceEur(checking)).thenReturn(new BigDecimal("2000"));
        when(accountService.signedLiveBalanceEur(loan)).thenReturn(new BigDecimal("-10000"));

        FamilyDashboardResponse response = familyViewService.getFamilyDashboard(1L);

        assertThat(response.sharedGoals()).hasSize(1);
        // Goal progress nets the linked loan against the linked asset: 2000 − 10000.
        assertThat(response.sharedGoals().getFirst().currentTotal()).isEqualByComparingTo("-8000");
    }

    @Test
    void getFamilyDashboard_resolvesSharesInOneQueryPerMember() {
        // shareFor issues a query per account, and this runs inside a loop over every family
        // member, so the per-account form costs members x accounts round trips to answer what
        // one IN clause answers. Nothing else fails if it regresses -- only this.
        FamilyMember viewer = FamilyMember.builder().id(1L).displayName("Viewer").build();
        FamilyMember owner = FamilyMember.builder().id(2L).displayName("Owner").build();
        List<Account> accounts = List.of(
            Account.builder().id(10L).name("A").type(AccountType.SAVINGS).currency("EUR")
                .currentBalance(new BigDecimal("100")).build(),
            Account.builder().id(11L).name("B").type(AccountType.SAVINGS).currency("EUR")
                .currentBalance(new BigDecimal("200")).build(),
            Account.builder().id(12L).name("C").type(AccountType.SAVINGS).currency("EUR")
                .currentBalance(new BigDecimal("300")).build());

        when(memberRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(viewer, owner));
        when(sharingSettingsRepository.findByMemberIdAndResourceType(2L, "ACCOUNT"))
            .thenReturn(Optional.of(new SharingSettings(null, owner, "ACCOUNT", SharingLevel.ALL)));
        when(sharingSettingsRepository.findByMemberIdAndResourceType(2L, "GOAL"))
            .thenReturn(Optional.empty());
        when(accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(2L)).thenReturn(accounts);
        for (Account a : accounts) {
            when(accountService.signedLiveBalanceEur(a)).thenReturn(a.getCurrentBalance());
        }

        FamilyDashboardResponse response = familyViewService.getFamilyDashboard(1L);

        assertThat(response.sharedAccounts()).hasSize(3);
        assertThat(response.totalSharedNetWorth()).isEqualByComparingTo("600");
        verify(accessResolver, times(1)).sharesFor(any(), eq(2L));
        verify(accessResolver, never()).shareFor(any(), any());
    }
}
