package com.picsou.service;

import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountOwnership;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.repository.AccountOwnershipRepository;
import com.picsou.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * This class is the single exception to "never read an account without a member filter", so
 * it carries the whole weight of co-ownership authorization. The cases below are the ones
 * that would turn a sharing feature into a data leak or let one co-owner rewrite another's
 * net worth.
 */
@ExtendWith(MockitoExtension.class)
class AccountAccessResolverTest {

    private static final FamilyMember ALICE = FamilyMember.builder().id(1L).displayName("Alice").build();
    private static final FamilyMember BOB = FamilyMember.builder().id(2L).displayName("Bob").build();
    private static final FamilyMember CAROL = FamilyMember.builder().id(3L).displayName("Carol").build();

    private static final BigDecimal FULL = new BigDecimal("100");

    @Mock AccountRepository accountRepository;
    @Mock AccountOwnershipRepository ownershipRepository;

    @InjectMocks AccountAccessResolver resolver;

    private static Account house(long id, FamilyMember owner) {
        return Account.builder()
            .id(id).name("Maison").type(AccountType.REAL_ESTATE).currency("EUR")
            .currentBalance(new BigDecimal("400000")).color("#a855f7").member(owner)
            .build();
    }

    private static AccountOwnership share(Account account, FamilyMember member, String percent) {
        return AccountOwnership.builder()
            .account(account).member(member).sharePercent(new BigDecimal(percent))
            .build();
    }

    // ─── Default: no rows means the owner holds everything ───────────────────

    @Test
    void shareFor_noRows_ownerHoldsEverything() {
        Account account = house(10L, ALICE);
        when(ownershipRepository.findByAccountId(10L)).thenReturn(List.of());

        // This default is what let the feature ship without backfilling a single account.
        assertThat(resolver.shareFor(account, ALICE.getId())).isEqualByComparingTo("100");
    }

    @Test
    void shareFor_noRows_strangerHoldsNothing() {
        Account account = house(10L, ALICE);
        when(ownershipRepository.findByAccountId(10L)).thenReturn(List.of());

        assertThat(resolver.shareFor(account, BOB.getId())).isEqualByComparingTo("0");
    }

    @Test
    void shareFor_explicitSplit_returnsEachHoldersShare() {
        Account account = house(10L, ALICE);
        when(ownershipRepository.findByAccountId(10L)).thenReturn(List.of(
            share(account, ALICE, "60"), share(account, BOB, "40")));

        assertThat(resolver.shareFor(account, ALICE.getId())).isEqualByComparingTo("60");
        assertThat(resolver.shareFor(account, BOB.getId())).isEqualByComparingTo("40");
    }

    @Test
    void shareFor_explicitSplitOmittingAMember_givesThemNothing() {
        Account account = house(10L, ALICE);
        when(ownershipRepository.findByAccountId(10L)).thenReturn(List.of(
            share(account, ALICE, "50"), share(account, BOB, "50")));

        assertThat(resolver.shareFor(account, CAROL.getId())).isEqualByComparingTo("0");
    }

    @Test
    void shareFor_partialSplit_leavesTheRemainderWithNobody() {
        Account account = house(10L, ALICE);
        // 30% is held outside Picsou (indivision with a non-member). It must not be silently
        // handed to the owner, or the family's combined net worth would invent money.
        when(ownershipRepository.findByAccountId(10L)).thenReturn(List.of(
            share(account, ALICE, "40"), share(account, BOB, "30")));

        assertThat(resolver.shareFor(account, ALICE.getId())).isEqualByComparingTo("40");
        assertThat(resolver.shareFor(account, BOB.getId())).isEqualByComparingTo("30");
    }

    // ─── Visibility ──────────────────────────────────────────────────────────

    @Test
    void readableAccounts_includesCoOwnedAccountsOfOtherMembers() {
        Account own = house(10L, BOB);
        Account coOwned = house(20L, ALICE);
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(BOB.getId())).thenReturn(List.of(own));
        when(ownershipRepository.findAccountIdsByMemberId(BOB.getId())).thenReturn(List.of(20L));
        when(accountRepository.findAllById(List.of(20L))).thenReturn(List.of(coOwned));

        assertThat(resolver.readableAccountIds(BOB.getId())).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    void readableAccounts_doesNotDuplicateAnAccountYouBothOwnAndHoldAShareOf() {
        Account own = house(10L, ALICE);
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(ALICE.getId())).thenReturn(List.of(own));
        when(ownershipRepository.findAccountIdsByMemberId(ALICE.getId())).thenReturn(List.of(10L));

        assertThat(resolver.readableAccountIds(ALICE.getId())).containsExactly(10L);
    }

    @Test
    void readableAccounts_withoutAnyShares_isJustTheOwnedOnes() {
        Account own = house(10L, ALICE);
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(ALICE.getId())).thenReturn(List.of(own));
        when(ownershipRepository.findAccountIdsByMemberId(ALICE.getId())).thenReturn(List.of());

        assertThat(resolver.readableAccountIds(ALICE.getId())).containsExactly(10L);
    }

    // ─── Guards ──────────────────────────────────────────────────────────────

    @Test
    void requireReadable_allowsACoOwner() {
        Account account = house(10L, ALICE);
        when(accountRepository.findById(10L)).thenReturn(Optional.of(account));
        when(ownershipRepository.findByAccountId(10L)).thenReturn(List.of(
            share(account, ALICE, "50"), share(account, BOB, "50")));

        assertThat(resolver.requireReadable(10L, BOB.getId())).isSameAs(account);
    }

    @Test
    void requireReadable_hidesAccountsYouHoldNoShareOf() {
        Account account = house(10L, ALICE);
        when(accountRepository.findById(10L)).thenReturn(Optional.of(account));
        when(ownershipRepository.findByAccountId(10L)).thenReturn(List.of());

        // 404 rather than 403: a "forbidden" would confirm the id exists, letting someone
        // enumerate other members' accounts.
        assertThatThrownBy(() -> resolver.requireReadable(10L, BOB.getId()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void requireOwner_refusesACoOwner() {
        Account account = house(10L, ALICE);
        when(accountRepository.findById(10L)).thenReturn(Optional.of(account));
        when(ownershipRepository.findByAccountId(10L)).thenReturn(List.of(
            share(account, ALICE, "50"), share(account, BOB, "50")));

        // Holding half a house must never grant the right to edit, revalue or delete it --
        // otherwise one co-owner could silently rewrite the other's net worth.
        assertThatThrownBy(() -> resolver.requireOwner(10L, BOB.getId()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void requireOwner_allowsTheOwnerEvenOnASplitAccount() {
        Account account = house(10L, ALICE);
        when(accountRepository.findById(10L)).thenReturn(Optional.of(account));

        // No ownership stub on purpose: ownership is decided by account.member, so holding
        // only half of a property must not cost the owner their administrative rights, and
        // the check must not need the split rows to say so.
        assertThat(resolver.requireOwner(10L, ALICE.getId())).isSameAs(account);
    }

    @Test
    void requireOwner_hidesAccountsYouHaveNothingToDoWith() {
        Account account = house(10L, ALICE);
        when(accountRepository.findById(10L)).thenReturn(Optional.of(account));
        when(ownershipRepository.findByAccountId(10L)).thenReturn(List.of());

        assertThatThrownBy(() -> resolver.requireOwner(10L, CAROL.getId()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void guards_rejectNullMemberId() {
        // A null memberId is a programming error, never a "skip the check" signal.
        assertThatThrownBy(() -> resolver.requireReadable(10L, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.readableAccounts(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── Batch and weighting ─────────────────────────────────────────────────

    @Test
    void sharesFor_resolvesAWholeSetInOneQuery() {
        Account split = house(10L, ALICE);
        Account whole = house(20L, BOB);
        when(ownershipRepository.findByAccountIdIn(List.of(10L, 20L))).thenReturn(List.of(
            share(split, ALICE, "70"), share(split, BOB, "30")));

        Map<Long, BigDecimal> shares = resolver.sharesFor(List.of(split, whole), BOB.getId());

        assertThat(shares.get(10L)).isEqualByComparingTo("30");
        // No rows for 20L, and Bob owns it, so he holds all of it.
        assertThat(shares.get(20L)).isEqualByComparingTo("100");
    }

    @Test
    void weigh_appliesThePercentage() {
        assertThat(AccountAccessResolver.weigh(new BigDecimal("400000"), new BigDecimal("50")))
            .isEqualByComparingTo("200000");
        assertThat(AccountAccessResolver.weigh(new BigDecimal("400000"), new BigDecimal("33.333")))
            .isEqualByComparingTo("133332.00000000");
    }

    @Test
    void weigh_fullShareReturnsTheAmountUntouched() {
        BigDecimal amount = new BigDecimal("1234.56");
        // Identity rather than a divide-then-round, so an unsplit account never drifts.
        assertThat(AccountAccessResolver.weigh(amount, FULL)).isSameAs(amount);
    }

    @Test
    void weigh_nullOrZeroShareYieldsZero() {
        assertThat(AccountAccessResolver.weigh(new BigDecimal("400000"), null))
            .isEqualByComparingTo("0");
        assertThat(AccountAccessResolver.weigh(new BigDecimal("400000"), BigDecimal.ZERO))
            .isEqualByComparingTo("0");
    }

    @Test
    void weigh_negativeAmountKeepsItsSign() {
        // Loans arrive here already negated by the caller; halving must not flip that.
        assertThat(AccountAccessResolver.weigh(new BigDecimal("-200000"), new BigDecimal("50")))
            .isEqualByComparingTo("-100000");
    }

    @Test
    void readableAccounts_ordersDeterministically() {
        Account older = Account.builder().id(10L).name("A").type(AccountType.REAL_ESTATE)
            .currency("EUR").currentBalance(BigDecimal.ONE).color("#fff").member(ALICE)
            .build();
        Account newer = house(20L, BOB);
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(ALICE.getId())).thenReturn(List.of(older));
        when(ownershipRepository.findAccountIdsByMemberId(ALICE.getId())).thenReturn(List.of(20L));
        when(accountRepository.findAllById(List.of(20L))).thenReturn(List.of(newer));

        // Both fixtures have a null createdAt; the id tiebreak keeps the order stable rather
        // than letting it depend on how the two queries happened to come back.
        assertThat(resolver.readableAccountIds(ALICE.getId())).containsExactly(10L, 20L);
    }

    @Test
    void isReadable_reportsWithoutThrowing() {
        Account account = house(10L, ALICE);
        when(accountRepository.findById(10L)).thenReturn(Optional.of(account));
        when(ownershipRepository.findByAccountId(10L)).thenReturn(List.of());

        assertThat(resolver.isReadable(10L, ALICE.getId())).isTrue();
        assertThat(resolver.isReadable(10L, BOB.getId())).isFalse();
    }

    @Test
    void isReadable_missingAccountIsSimplyNotReadable() {
        when(accountRepository.findById(99L)).thenReturn(Optional.empty());
        assertThat(resolver.isReadable(99L, ALICE.getId())).isFalse();
    }

    @Test
    void readableAccounts_sortsByCreationWhenKnown() {
        Account first = Account.builder().id(30L).name("First").type(AccountType.REAL_ESTATE)
            .currency("EUR").currentBalance(BigDecimal.ONE).color("#fff").member(ALICE).build();
        Account second = Account.builder().id(20L).name("Second").type(AccountType.REAL_ESTATE)
            .currency("EUR").currentBalance(BigDecimal.ONE).color("#fff").member(BOB).build();
        setCreatedAt(first, Instant.parse("2024-01-01T00:00:00Z"));
        setCreatedAt(second, Instant.parse("2025-01-01T00:00:00Z"));

        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(ALICE.getId())).thenReturn(List.of(first));
        when(ownershipRepository.findAccountIdsByMemberId(ALICE.getId())).thenReturn(List.of(20L));
        when(accountRepository.findAllById(List.of(20L))).thenReturn(List.of(second));

        assertThat(resolver.readableAccountIds(ALICE.getId())).containsExactly(30L, 20L);
    }

    /** createdAt is managed by JPA auditing, so tests have to set it reflectively. */
    private static void setCreatedAt(Account account, Instant value) {
        try {
            var field = Class.forName("com.picsou.model.AuditableEntity").getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(account, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
