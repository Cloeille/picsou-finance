package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.ScpiFundOutcome;
import com.picsou.model.ScpiPosition;
import com.picsou.model.ScpiProvider;
import com.picsou.model.ScpiProviderFund;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.ScpiPositionRepository;
import com.picsou.repository.ScpiProviderFundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * GH issue #232: a sync creates or adopts the account of a fund it has not seen,
 * never duplicates one, never brings back an ignored one and never deletes one.
 * The repositories are backed by in-memory lists so a second sync sees what the
 * first one wrote, the way the database would.
 */
@ExtendWith(MockitoExtension.class)
class ScpiFundReconcilerTest {

    private static final long MEMBER = 7L;
    private static final Instant NOW = Instant.parse("2026-10-16T08:00:00Z");

    @Mock ScpiPositionRepository positionRepository;
    @Mock AccountRepository accountRepository;
    @Mock ScpiProviderFundRepository fundRepository;
    @Mock FamilyMemberRepository memberRepository;

    final List<Account> accounts = new ArrayList<>();
    final List<ScpiPosition> positions = new ArrayList<>();
    final List<ScpiProviderFund> funds = new ArrayList<>();
    final AtomicLong ids = new AtomicLong(100);
    final FamilyMember member = FamilyMember.builder().id(MEMBER).build();

    ScpiFundReconciler reconciler;

    @BeforeEach
    void setUp() {
        lenient().when(memberRepository.getReferenceById(MEMBER)).thenReturn(member);
        lenient().when(accountRepository.save(any())).thenAnswer(inv -> {
            Account account = inv.getArgument(0);
            if (account.getId() == null) {
                account.setId(ids.incrementAndGet());
            }
            if (!accounts.contains(account)) {
                accounts.add(account);
            }
            return account;
        });
        lenient().when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER))
            .thenAnswer(inv -> accounts.stream().filter(a -> a.getDeletedAt() == null).toList());
        lenient().when(positionRepository.save(any())).thenAnswer(inv -> {
            ScpiPosition position = inv.getArgument(0);
            if (!positions.contains(position)) {
                positions.add(position);
            }
            return position;
        });
        lenient().when(positionRepository.findByAccountIdAndMemberId(anyLong(), eq(MEMBER)))
            .thenAnswer(inv -> positions.stream()
                .filter(p -> p.getAccount().getId().equals(inv.getArgument(0))).findFirst());
        lenient().when(positionRepository.findByMemberIdAndCorumFundCode(eq(MEMBER), anyString()))
            .thenAnswer(inv -> positions.stream()
                .filter(p -> inv.getArgument(1).equals(p.getCorumFundCode())).findFirst());
        lenient().when(positionRepository.findByMemberIdAndSofidyFundCode(eq(MEMBER), anyString()))
            .thenAnswer(inv -> positions.stream()
                .filter(p -> inv.getArgument(1).equals(p.getSofidyFundCode())).findFirst());
        lenient().when(positionRepository.findByAccountMemberIdAndCorumFundCodeIsNotNull(MEMBER))
            .thenAnswer(inv -> positions.stream().filter(p -> p.getCorumFundCode() != null).toList());
        lenient().when(fundRepository.save(any())).thenAnswer(inv -> {
            ScpiProviderFund row = inv.getArgument(0);
            if (!funds.contains(row)) {
                funds.add(row);
            }
            return row;
        });
        lenient().when(fundRepository.findByMemberIdAndProviderAndFundCode(eq(MEMBER), any(), anyString()))
            .thenAnswer(inv -> funds.stream()
                .filter(f -> f.getProvider() == inv.getArgument(1) && f.getFundCode().equals(inv.getArgument(2)))
                .findFirst());
        lenient().when(fundRepository.findByMemberIdAndProvider(eq(MEMBER), any()))
            .thenAnswer(inv -> funds.stream().filter(f -> f.getProvider() == inv.getArgument(1)).toList());

        ScpiPositionService positionService =
            new ScpiPositionService(accountRepository, positionRepository, mock(ScpiFundService.class));
        reconciler = new ScpiFundReconciler(
            positionRepository, positionService, accountRepository, fundRepository, memberRepository);
    }

    private static ScpiFundReconciler.SyncedFund fund(String code, String label, String shares, String withdrawal) {
        return new ScpiFundReconciler.SyncedFund(
            code, label, new BigDecimal(shares), null, withdrawal == null ? null : new BigDecimal(withdrawal));
    }

    private Account manualScpi(String name) {
        Account account = Account.builder().id(ids.incrementAndGet()).member(member).name(name)
            .type(AccountType.SCPI).currency("EUR").currentBalance(BigDecimal.ZERO).isManual(true).build();
        accounts.add(account);
        return account;
    }

    @Test
    void firstSyncCreatesOneAccountPerFundWithSharesAndWithdrawalValue() {
        Map<String, ScpiFundOutcome> outcomes = reconciler.reconcile(MEMBER, ScpiProvider.CORUM, List.of(
            fund("US", "CORUM USA", "10", "180"),
            fund("XL", "CORUM XL", "4.5", "160")
        ), NOW);

        assertThat(outcomes).containsExactly(
            Map.entry("US", ScpiFundOutcome.CREATED), Map.entry("XL", ScpiFundOutcome.CREATED));
        assertThat(accounts).hasSize(2);
        Account usa = accounts.get(0);
        assertThat(usa.getName()).isEqualTo("CORUM USA");
        assertThat(usa.getType()).isEqualTo(AccountType.SCPI);
        assertThat(usa.getProvider()).isEqualTo("CORUM");
        assertThat(usa.isManual()).isTrue();
        assertThat(usa.getCurrentBalance()).isEqualByComparingTo("1800");
        assertThat(positions).extracting(ScpiPosition::getCorumFundCode).containsExactly("US", "XL");
        assertThat(funds).extracting(ScpiProviderFund::getLastOutcome)
            .containsOnly(ScpiFundOutcome.CREATED);
    }

    @Test
    void secondSyncCreatesNothingAndUpdatesTheShares() {
        reconciler.reconcile(MEMBER, ScpiProvider.SOFIDY, List.of(fund("DY", "SOFIDYNAMIC", "2", "300")), NOW);

        Map<String, ScpiFundOutcome> outcomes = reconciler.reconcile(
            MEMBER, ScpiProvider.SOFIDY, List.of(fund("DY", "SOFIDYNAMIC", "3", "300")), NOW);

        assertThat(outcomes).containsExactly(Map.entry("DY", ScpiFundOutcome.UPDATED));
        assertThat(accounts).hasSize(1);
        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).getShareCount()).isEqualByComparingTo("3");
        assertThat(accounts.get(0).getCurrentBalance()).isEqualByComparingTo("900");
    }

    @Test
    void aManualAccountWithTheSameNormalizedNameIsLinkedNotDuplicated() {
        Account manual = manualScpi("Corum-XL ");

        Map<String, ScpiFundOutcome> outcomes = reconciler.reconcile(
            MEMBER, ScpiProvider.CORUM, List.of(fund("XL", "CORUM XL", "4", "160")), NOW);

        assertThat(outcomes).containsExactly(Map.entry("XL", ScpiFundOutcome.LINKED));
        assertThat(accounts).containsExactly(manual);
        assertThat(positions).singleElement().satisfies(p -> {
            assertThat(p.getAccount()).isSameAs(manual);
            assertThat(p.getCorumFundCode()).isEqualTo("XL");
        });
        assertThat(manual.getCurrentBalance()).isEqualByComparingTo("640");
    }

    @Test
    void twoMatchingManualAccountsAreAmbiguousAndNothingIsCreated() {
        manualScpi("CORUM XL");
        manualScpi("corum xl");

        Map<String, ScpiFundOutcome> outcomes = reconciler.reconcile(
            MEMBER, ScpiProvider.CORUM, List.of(fund("XL", "CORUM XL", "4", "160")), NOW);

        assertThat(outcomes).containsExactly(Map.entry("XL", ScpiFundOutcome.AMBIGUOUS));
        assertThat(accounts).hasSize(2);
        assertThat(positions).isEmpty();
    }

    @Test
    void anAccountLinkedToTheOtherProviderIsNeverAdopted() {
        reconciler.reconcile(MEMBER, ScpiProvider.SOFIDY, List.of(fund("IM", "IMMORENTE", "1", "250")), NOW);

        Map<String, ScpiFundOutcome> outcomes = reconciler.reconcile(
            MEMBER, ScpiProvider.CORUM, List.of(fund("IM", "Immorente", "1", "250")), NOW);

        assertThat(outcomes).containsExactly(Map.entry("IM", ScpiFundOutcome.CREATED));
        assertThat(accounts).hasSize(2);
    }

    @Test
    void anIgnoredFundIsNotRecreated() {
        funds.add(ScpiProviderFund.builder().member(member).provider(ScpiProvider.CORUM)
            .fundCode("US").ignored(true).build());

        Map<String, ScpiFundOutcome> outcomes = reconciler.reconcile(
            MEMBER, ScpiProvider.CORUM, List.of(fund("US", "CORUM USA", "10", "180")), NOW);

        assertThat(outcomes).containsExactly(Map.entry("US", ScpiFundOutcome.IGNORED));
        assertThat(accounts).isEmpty();
        assertThat(positions).isEmpty();
    }

    @Test
    void aFundNoLongerListedIsReportedAndItsAccountKept() {
        reconciler.reconcile(MEMBER, ScpiProvider.CORUM, List.of(
            fund("US", "CORUM USA", "10", "180"), fund("XL", "CORUM XL", "4", "160")), NOW);

        Map<String, ScpiFundOutcome> outcomes = reconciler.reconcile(
            MEMBER, ScpiProvider.CORUM, List.of(fund("US", "CORUM USA", "10", "180")), NOW);

        assertThat(outcomes).containsEntry("XL", ScpiFundOutcome.MISSING_AT_PROVIDER);
        assertThat(accounts).hasSize(2);
        assertThat(accounts.get(1).getCurrentBalance()).isEqualByComparingTo("640");
        assertThat(accounts.get(1).getDeletedAt()).isNull();
    }

    @Test
    void aLinkedFundWithoutWithdrawalPriceIsPriceIncomplete() {
        reconciler.reconcile(MEMBER, ScpiProvider.CORUM, List.of(fund("US", "CORUM USA", "10", "180")), NOW);

        Map<String, ScpiFundOutcome> outcomes = reconciler.reconcile(
            MEMBER, ScpiProvider.CORUM, List.of(fund("US", "CORUM USA", "11", null)), NOW);

        assertThat(outcomes).containsExactly(Map.entry("US", ScpiFundOutcome.PRICE_INCOMPLETE));
        assertThat(accounts.get(0).getCurrentBalance()).isEqualByComparingTo("1800");
    }

    @Test
    void normalizeIgnoresCaseAccentsAndPunctuationOnly() {
        assertThat(ScpiFundReconciler.normalize("Épargne  Pierre!")).isEqualTo("epargne pierre");
        assertThat(ScpiFundReconciler.normalize("CORUM-XL")).isEqualTo(ScpiFundReconciler.normalize("corum xl"));
        assertThat(ScpiFundReconciler.normalize("CORUM XL")).isNotEqualTo(ScpiFundReconciler.normalize("CORUM XL 2"));
        assertThat(Optional.ofNullable(ScpiFundReconciler.normalize(null))).contains("");
    }
}
