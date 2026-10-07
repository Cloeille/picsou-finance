package com.picsou.service;

import com.picsou.dto.ContributionBreakdownResponse;
import com.picsou.dto.FamilyDashboardResponse;
import com.picsou.dto.FamilyDashboardResponse.*;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.*;
import com.picsou.repository.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class FamilyViewService {

    private final FamilyMemberRepository memberRepository;
    private final SharingSettingsRepository sharingSettingsRepository;
    private final SharedResourceRepository sharedResourceRepository;
    private final AccountRepository accountRepository;
    private final GoalRepository goalRepository;
    private final AccountService accountService;
    private final GoalManualContributionRepository contributionRepository;
    private final AccountAccessResolver accessResolver;

    public FamilyViewService(
        FamilyMemberRepository memberRepository,
        SharingSettingsRepository sharingSettingsRepository,
        SharedResourceRepository sharedResourceRepository,
        AccountRepository accountRepository,
        GoalRepository goalRepository,
        AccountService accountService,
        GoalManualContributionRepository contributionRepository,
        AccountAccessResolver accessResolver
    ) {
        this.memberRepository = memberRepository;
        this.sharingSettingsRepository = sharingSettingsRepository;
        this.sharedResourceRepository = sharedResourceRepository;
        this.accountRepository = accountRepository;
        this.goalRepository = goalRepository;
        this.accountService = accountService;
        this.contributionRepository = contributionRepository;
        this.accessResolver = accessResolver;
    }

    public FamilyDashboardResponse getFamilyDashboard(Long viewerMemberId) {
        List<SharedAccountInfo> sharedAccounts = new ArrayList<>();
        List<SharedGoalInfo> sharedGoals = new ArrayList<>();

        List<FamilyMember> allMembers = memberRepository.findAllByOrderByCreatedAtAsc();

        for (FamilyMember member : allMembers) {
            // Skip self — you see your own data on the regular dashboard
            if (member.getId().equals(viewerMemberId)) continue;

            String ownerName = member.getDisplayName();

            // ─── Accounts ───────────────────────────────────────────
            SharingSettings accountSettings = sharingSettingsRepository
                .findByMemberIdAndResourceType(member.getId(), "ACCOUNT")
                .orElse(null);

            if (accountSettings != null && accountSettings.getSharingLevel() != SharingLevel.NONE) {
                List<Account> accounts;
                if (accountSettings.getSharingLevel() == SharingLevel.ALL) {
                    accounts = accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(member.getId());
                } else {
                    // MANUAL — only specific shared resources
                    List<Long> sharedIds = sharedResourceRepository
                        .findAllByOwnerMemberIdAndResourceType(member.getId(), "ACCOUNT").stream()
                        .map(SharedResource::getResourceId)
                        .toList();
                    accounts = accountRepository.findByIdInAndMemberId(sharedIds, member.getId());
                }

                // One query for the whole set. shareFor issues one per account, and this runs
                // inside a loop over every family member -- members x accounts round trips to
                // answer a question a single IN clause answers.
                Map<Long, BigDecimal> accountShares = accessResolver.sharesFor(accounts, member.getId());

                for (Account acc : accounts) {
                    // Signed: LOAN accounts count negatively so totalNetWorth below is correct.
                    //
                    // Weighted by the OWNER's share, not the viewer's: this list is "what other
                    // members hold". On a house split 50/50 between the viewer and this member,
                    // the viewer's own half already shows on their personal dashboard, so
                    // counting the full value here would report the property twice.
                    BigDecimal balanceEur = AccountAccessResolver.weigh(
                        accountService.signedLiveBalanceEur(acc),
                        accountShares.get(acc.getId()));
                    sharedAccounts.add(new SharedAccountInfo(
                        acc.getId(),
                        ownerName,
                        acc.getName(),
                        acc.getType().name(),
                        acc.getCurrency(),
                        acc.getCurrentBalance(),
                        balanceEur
                    ));
                }
            }

            // ─── Goals ─────────────────────────────────────────────
            SharingSettings goalSettings = sharingSettingsRepository
                .findByMemberIdAndResourceType(member.getId(), "GOAL")
                .orElse(null);

            if (goalSettings != null && goalSettings.getSharingLevel() != SharingLevel.NONE) {
                List<Goal> goals;
                if (goalSettings.getSharingLevel() == SharingLevel.ALL) {
                    goals = goalRepository.findAllByMemberIdOrderByCreatedAtAsc(member.getId());
                } else {
                    List<Long> sharedIds = sharedResourceRepository
                        .findAllByOwnerMemberIdAndResourceType(member.getId(), "GOAL").stream()
                        .map(SharedResource::getResourceId)
                        .toList();
                    goals = goalRepository.findByIdInAndMemberId(sharedIds, member.getId());
                }

                // Resolved across every goal's accounts at once rather than per goal, since
                // the same account commonly backs several of them.
                Set<Account> goalAccounts = new LinkedHashSet<>();
                for (Goal goal : goals) {
                    goalAccounts.addAll(goal.getAccounts());
                }
                Map<Long, BigDecimal> goalShares = accessResolver.sharesFor(goalAccounts, member.getId());

                for (Goal goal : goals) {
                    // Same reasoning as the accounts above: a goal backed by a co-owned
                    // account counts only the goal owner's share of it.
                    BigDecimal currentTotal = goal.getAccounts().stream()
                        .map(a -> AccountAccessResolver.weigh(
                            accountService.signedLiveBalanceEur(a),
                            goalShares.get(a.getId())))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                    // Build contributions per member (from manual contributions)
                    List<ContributionInfo> contributions = new ArrayList<>();
                    for (FamilyMember contributor : allMembers) {
                        BigDecimal total = contributionRepository
                            .findByGoalIdAndMemberId(goal.getId(), contributor.getId()).stream()
                            .map(GoalManualContribution::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                        if (total.compareTo(BigDecimal.ZERO) > 0) {
                            contributions.add(new ContributionInfo(contributor.getDisplayName(), total));
                        }
                    }

                    sharedGoals.add(new SharedGoalInfo(
                        goal.getId(),
                        ownerName,
                        goal.getName(),
                        goal.getTargetAmount(),
                        currentTotal,
                        contributions
                    ));
                }
            }
        }

        BigDecimal totalNetWorth = sharedAccounts.stream()
            .map(SharedAccountInfo::balanceEur)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new FamilyDashboardResponse(sharedAccounts, sharedGoals, totalNetWorth);
    }

    public List<ContributionBreakdownResponse> getGoalContributions(Long goalId, Long viewerMemberId, List<FamilyMember> allMembers) {
        Goal goal = goalRepository.findById(goalId).orElseThrow(() -> ResourceNotFoundException.goal(goalId));
        Long ownerId = goal.getMember().getId();
        if (!ownerId.equals(viewerMemberId) && !isGoalVisibleTo(goal, ownerId)) {
            throw new AccessDeniedException("Goal not accessible");
        }
        List<ContributionBreakdownResponse> result = new ArrayList<>();
        for (FamilyMember member : allMembers) {
            BigDecimal total = contributionRepository
                .findByGoalIdAndMemberId(goalId, member.getId()).stream()
                .map(GoalManualContribution::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.compareTo(BigDecimal.ZERO) > 0) {
                result.add(new ContributionBreakdownResponse(member.getDisplayName(), total));
            }
        }
        return result;
    }

    private boolean isGoalVisibleTo(Goal goal, Long ownerMemberId) {
        SharingSettings settings = sharingSettingsRepository
            .findByMemberIdAndResourceType(ownerMemberId, "GOAL")
            .orElse(null);
        if (settings == null || settings.getSharingLevel() == SharingLevel.NONE) {
            return false;
        }
        if (settings.getSharingLevel() == SharingLevel.ALL) {
            return true;
        }
        return sharedResourceRepository
            .existsByOwnerMemberIdAndResourceTypeAndResourceId(ownerMemberId, "GOAL", goal.getId());
    }
}
