package com.picsou.service;

import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountOwnership;
import com.picsou.repository.AccountOwnershipRepository;
import com.picsou.repository.AccountRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * The single place that answers "which accounts may this member see, and for how much of them".
 *
 * <p>{@code backend/CLAUDE.md} makes member scoping non-negotiable: never query a repository
 * without a member filter. Co-ownership is a deliberate exception to that rule, so it is
 * confined here instead of being spread across services as ad-hoc queries — there is exactly
 * one place to audit, and exactly one place to get it wrong.
 *
 * <p>Two invariants worth stating plainly:
 *
 * <ul>
 *   <li><b>Reading is broader than writing.</b> A co-owner may read a co-owned account and
 *       its share; only the owning member may modify anything. Callers on a write path use
 *       {@link #requireOwner}, never {@link #requireReadable}.
 *   <li><b>Shares weight reads, never writes.</b> Snapshots and balances are always stored at
 *       100% of the account's value; the share is applied when a total is read. Storing
 *       weighted values would mean re-writing history every time a split changes.
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class AccountAccessResolver {

    private static final BigDecimal FULL_SHARE = new BigDecimal("100");

    private final AccountRepository accountRepository;
    private final AccountOwnershipRepository ownershipRepository;

    public AccountAccessResolver(AccountRepository accountRepository,
                                 AccountOwnershipRepository ownershipRepository) {
        this.accountRepository = accountRepository;
        this.ownershipRepository = ownershipRepository;
    }

    // ─── Visibility ──────────────────────────────────────────────────────────

    /**
     * Accounts the member owns, plus those they hold a share of.
     *
     * <p>Co-owned ids go back through {@link AccountRepository} rather than being trusted
     * straight from the ownership rows, so {@code Account}'s {@code @SQLRestriction} still
     * hides soft-deleted accounts.
     */
    public List<Account> readableAccounts(Long memberId) {
        requireMemberId(memberId);
        List<Account> owned = accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId);

        List<Long> coOwnedIds = ownershipRepository.findAccountIdsByMemberId(memberId);
        if (coOwnedIds.isEmpty()) {
            return owned;
        }

        Set<Long> ownedIds = new HashSet<>();
        for (Account a : owned) {
            ownedIds.add(a.getId());
        }
        List<Long> extra = coOwnedIds.stream().filter(id -> !ownedIds.contains(id)).toList();
        if (extra.isEmpty()) {
            return owned;
        }

        List<Account> result = new ArrayList<>(owned);
        result.addAll(accountRepository.findAllById(extra));
        result.sort(Comparator.comparing(Account::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Account::getId));
        return result;
    }

    public List<Long> readableAccountIds(Long memberId) {
        return readableAccounts(memberId).stream().map(Account::getId).toList();
    }

    // ─── Shares ──────────────────────────────────────────────────────────────

    /**
     * The member's percentage of this account.
     *
     * <p>No ownership rows at all means the owning member holds 100% — that default is what
     * lets this feature ship without backfilling a single existing account.
     *
     * @return a percentage in [0, 100]; zero when the member holds nothing
     */
    public BigDecimal shareFor(Account account, Long memberId) {
        requireMemberId(memberId);
        List<AccountOwnership> rows = ownershipRepository.findByAccountId(account.getId());
        return shareFrom(rows, account, memberId);
    }

    /** Batch form of {@link #shareFor}, one query for the whole set. */
    public Map<Long, BigDecimal> sharesFor(Collection<Account> accounts, Long memberId) {
        requireMemberId(memberId);
        if (accounts.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = accounts.stream().map(Account::getId).toList();
        Map<Long, List<AccountOwnership>> byAccount = new HashMap<>();
        for (AccountOwnership row : ownershipRepository.findByAccountIdIn(ids)) {
            byAccount.computeIfAbsent(row.getAccount().getId(), k -> new ArrayList<>()).add(row);
        }
        Map<Long, BigDecimal> shares = new HashMap<>();
        for (Account account : accounts) {
            List<AccountOwnership> rows = byAccount.getOrDefault(account.getId(), List.of());
            shares.put(account.getId(), shareFrom(rows, account, memberId));
        }
        return shares;
    }

    private BigDecimal shareFrom(List<AccountOwnership> rows, Account account, Long memberId) {
        if (rows.isEmpty()) {
            return account.getMember() != null && memberId.equals(account.getMember().getId())
                ? FULL_SHARE
                : BigDecimal.ZERO;
        }
        for (AccountOwnership row : rows) {
            if (memberId.equals(row.getMember().getId())) {
                return row.getSharePercent();
            }
        }
        // An explicit split that omits this member means they hold none of it — even if they
        // are the account's administrative owner (they may have transferred their whole share).
        return BigDecimal.ZERO;
    }

    /**
     * Applies a share to an amount.
     *
     * <p>Kept here so every caller rounds identically; a per-call-site {@code divide} would
     * drift and make totals disagree between the dashboard and the history chart.
     */
    public static BigDecimal weigh(BigDecimal amount, BigDecimal sharePercent) {
        if (amount == null || amount.signum() == 0) {
            return BigDecimal.ZERO;
        }
        if (sharePercent == null || sharePercent.signum() == 0) {
            return BigDecimal.ZERO;
        }
        if (FULL_SHARE.compareTo(sharePercent) == 0) {
            return amount;
        }
        return amount.multiply(sharePercent).divide(FULL_SHARE, 8, RoundingMode.HALF_UP);
    }

    // ─── Guards ──────────────────────────────────────────────────────────────

    /** Read guard: owner or co-owner. Use on any path that only returns data. */
    public Account requireReadable(Long accountId, Long memberId) {
        requireMemberId(memberId);
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> ResourceNotFoundException.account(accountId));
        if (isOwner(account, memberId)) {
            return account;
        }
        if (shareFor(account, memberId).signum() > 0) {
            return account;
        }
        // Not readable at all: 404 rather than 403, so the response cannot be used to probe
        // which account ids exist on other members.
        throw ResourceNotFoundException.account(accountId);
    }

    /**
     * Write guard: the administrative owner only.
     *
     * <p>Holding a share never confers the right to edit, revalue or delete — otherwise one
     * co-owner could silently rewrite the other's net worth.
     */
    public Account requireOwner(Long accountId, Long memberId) {
        requireMemberId(memberId);
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> ResourceNotFoundException.account(accountId));
        if (isOwner(account, memberId)) {
            return account;
        }
        if (shareFor(account, memberId).signum() > 0) {
            throw new AccessDeniedException("Only the owning member can modify this account");
        }
        throw ResourceNotFoundException.account(accountId);
    }

    public boolean isReadable(Long accountId, Long memberId) {
        return accountRepository.findById(accountId)
            .map(a -> isOwner(a, memberId) || shareFor(a, memberId).signum() > 0)
            .orElse(false);
    }

    private boolean isOwner(Account account, Long memberId) {
        return account.getMember() != null && memberId.equals(account.getMember().getId());
    }

    private void requireMemberId(Long memberId) {
        if (memberId == null) {
            throw new IllegalArgumentException("memberId is required for member-scoped access");
        }
    }
}
