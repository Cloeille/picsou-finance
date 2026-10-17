package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.ScpiFundOutcome;
import com.picsou.model.ScpiPosition;
import com.picsou.model.ScpiProvider;
import com.picsou.model.ScpiProviderFund;
import com.picsou.model.ScpiValuationStatus;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.ScpiPositionRepository;
import com.picsou.repository.ScpiProviderFundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Maps the funds of a CORUM or Sofidy snapshot onto SCPI accounts (GH issue #232).
 *
 * <p>For each fund, in this order:
 * <ol>
 *   <li>an ignored fund (the member deleted or unlinked its account) writes nothing;</li>
 *   <li>a fund already linked to an account updates it;</li>
 *   <li>otherwise a manual SCPI account with no provider link whose name matches the
 *       fund label is adopted -- only when exactly one matches;</li>
 *   <li>otherwise, if no account matched, a new SCPI account is created.</li>
 * </ol>
 * Every write goes through {@link ScpiPositionService#applySyncedPosition}, so the
 * withdrawal-price rule keeps one implementation. Nothing is ever deleted: a fund the
 * provider no longer lists is only reported as {@code MISSING_AT_PROVIDER}.
 *
 * <p>The caller runs this inside the sync's own transaction, after locking the member
 * row, so two syncs of one member cannot both create the same account. The per-member
 * unique indexes on the fund codes are the last line behind that lock.
 */
@Service
public class ScpiFundReconciler {

    private static final Logger log = LoggerFactory.getLogger(ScpiFundReconciler.class);
    private static final int ACCOUNT_NAME_MAX = 100;
    private static final int LABEL_MAX = 200;
    /** Same colour the manual SCPI form uses. */
    private static final String SCPI_COLOR = "#7c3aed";

    private final ScpiPositionRepository positionRepository;
    private final ScpiPositionService positionService;
    private final AccountRepository accountRepository;
    private final ScpiProviderFundRepository fundRepository;
    private final FamilyMemberRepository memberRepository;

    public ScpiFundReconciler(
        ScpiPositionRepository positionRepository,
        ScpiPositionService positionService,
        AccountRepository accountRepository,
        ScpiProviderFundRepository fundRepository,
        FamilyMemberRepository memberRepository
    ) {
        this.positionRepository = positionRepository;
        this.positionService = positionService;
        this.accountRepository = accountRepository;
        this.fundRepository = fundRepository;
        this.memberRepository = memberRepository;
    }

    /**
     * One fund line of a snapshot, already validated by the caller. A null
     * {@code subscriptionPrice} leaves the stored one alone; a null
     * {@code withdrawalPrice} keeps the previous balance.
     */
    public record SyncedFund(
        String fundCode,
        String label,
        BigDecimal quantity,
        BigDecimal subscriptionPrice,
        BigDecimal withdrawalPrice
    ) {}

    /**
     * @return the outcome per fund code, in snapshot order, then the missing funds
     */
    @Transactional
    public Map<String, ScpiFundOutcome> reconcile(
        Long memberId,
        ScpiProvider provider,
        List<SyncedFund> funds,
        Instant syncedAt
    ) {
        Map<String, ScpiFundOutcome> outcomes = new LinkedHashMap<>();
        Set<String> listed = new HashSet<>();
        // Accounts adopted or created in this run must not be adopted again by a
        // later fund with the same name.
        Set<Long> claimedAccounts = new HashSet<>();
        for (SyncedFund fund : funds) {
            listed.add(fund.fundCode());
            ScpiProviderFund row = fundRow(memberId, provider, fund.fundCode(), fund.label());
            row.setLastSeenAt(syncedAt);
            ScpiFundOutcome outcome = applyFund(memberId, provider, fund, row, claimedAccounts);
            row.setLastOutcome(outcome);
            row.setLastSyncedAt(syncedAt);
            fundRepository.save(row);
            outcomes.put(fund.fundCode(), outcome);
        }
        reportMissing(memberId, provider, listed, syncedAt, outcomes);
        return outcomes;
    }

    private ScpiFundOutcome applyFund(
        Long memberId,
        ScpiProvider provider,
        SyncedFund fund,
        ScpiProviderFund row,
        Set<Long> claimedAccounts
    ) {
        if (row.isIgnored()) {
            return ScpiFundOutcome.IGNORED;
        }

        Optional<ScpiPosition> linked = findLinked(memberId, provider, fund.fundCode());
        if (linked.isPresent()) {
            apply(linked.get(), fund);
            claimedAccounts.add(linked.get().getAccount().getId());
            return linked.get().getValuationStatus() == ScpiValuationStatus.PRICE_INCOMPLETE
                ? ScpiFundOutcome.PRICE_INCOMPLETE
                : ScpiFundOutcome.UPDATED;
        }

        List<Account> candidates = adoptionCandidates(memberId, fund.label(), claimedAccounts);
        if (candidates.size() > 1) {
            log.info("Several SCPI accounts match this {} fund, none adopted (member={}; fund={}; matches={})",
                provider, memberId, fund.fundCode(), candidates.size());
            return ScpiFundOutcome.AMBIGUOUS;
        }
        if (candidates.size() == 1) {
            Account account = candidates.get(0);
            ScpiPosition position = positionRepository.findByAccountIdAndMemberId(account.getId(), memberId)
                .orElseGet(() -> newPosition(account, provider, fund));
            setFundCode(position, provider, fund.fundCode());
            apply(position, fund);
            claimedAccounts.add(account.getId());
            return ScpiFundOutcome.LINKED;
        }

        Account account = Account.builder()
            .member(memberRepository.getReferenceById(memberId))
            .name(truncate(fund.label() != null && !fund.label().isBlank()
                ? fund.label().trim()
                : provider.displayName() + " " + fund.fundCode(), ACCOUNT_NAME_MAX))
            .type(AccountType.SCPI)
            .provider(provider.displayName())
            .currency("EUR")
            .currentBalance(BigDecimal.ZERO)
            // A SCPI is always manual: its balance is written from the share, never
            // replaced wholesale by a bank feed. See AccountService.create.
            .isManual(true)
            .color(SCPI_COLOR)
            .build();
        accountRepository.save(account);
        ScpiPosition position = newPosition(account, provider, fund);
        setFundCode(position, provider, fund.fundCode());
        apply(position, fund);
        claimedAccounts.add(account.getId());
        return ScpiFundOutcome.CREATED;
    }

    private void apply(ScpiPosition position, SyncedFund fund) {
        positionService.applySyncedPosition(
            position, fund.quantity(), fund.subscriptionPrice(), fund.withdrawalPrice(), null);
    }

    private ScpiPosition newPosition(Account account, ScpiProvider provider, SyncedFund fund) {
        return ScpiPosition.builder()
            .account(account)
            .member(account.getMember())
            .managementCompany(provider.displayName())
            .shareCount(fund.quantity())
            .build();
    }

    /**
     * Manual SCPI accounts of the member, linked to no provider, whose normalized
     * name equals the normalized fund label. An account linked to the other
     * provider is never a candidate: it already mirrors a fund.
     */
    private List<Account> adoptionCandidates(Long memberId, String label, Set<Long> claimedAccounts) {
        String wanted = normalize(label);
        if (wanted.isEmpty()) {
            return List.of();
        }
        List<Account> matches = new ArrayList<>();
        for (Account account : accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId)) {
            if (account.getType() != AccountType.SCPI || claimedAccounts.contains(account.getId())) {
                continue;
            }
            if (!wanted.equals(normalize(account.getName()))) {
                continue;
            }
            Optional<ScpiPosition> position = positionRepository.findByAccountIdAndMemberId(account.getId(), memberId);
            if (position.isPresent()
                    && (position.get().getCorumFundCode() != null || position.get().getSofidyFundCode() != null)) {
                continue;
            }
            matches.add(account);
        }
        return matches;
    }

    /**
     * Reports, without touching any balance, every fund this member had from the
     * provider that the snapshot no longer lists. Linked positions count even when
     * they predate this table, so a link made by hand is reported too.
     */
    private void reportMissing(
        Long memberId,
        ScpiProvider provider,
        Set<String> listed,
        Instant syncedAt,
        Map<String, ScpiFundOutcome> outcomes
    ) {
        Map<String, String> known = new LinkedHashMap<>();
        for (ScpiProviderFund row : fundRepository.findByMemberIdAndProvider(memberId, provider)) {
            if (!row.isIgnored()) {
                known.put(row.getFundCode(), row.getLabel());
            }
        }
        for (ScpiPosition position : linkedPositions(memberId, provider)) {
            known.putIfAbsent(fundCode(position, provider), position.getAccount().getName());
        }
        for (Map.Entry<String, String> entry : known.entrySet()) {
            if (listed.contains(entry.getKey())) {
                continue;
            }
            ScpiProviderFund row = fundRow(memberId, provider, entry.getKey(), entry.getValue());
            row.setLastOutcome(ScpiFundOutcome.MISSING_AT_PROVIDER);
            row.setLastSyncedAt(syncedAt);
            fundRepository.save(row);
            outcomes.put(entry.getKey(), ScpiFundOutcome.MISSING_AT_PROVIDER);
        }
    }

    private ScpiProviderFund fundRow(Long memberId, ScpiProvider provider, String fundCode, String label) {
        ScpiProviderFund row = fundRepository
            .findByMemberIdAndProviderAndFundCode(memberId, provider, fundCode)
            .orElseGet(() -> ScpiProviderFund.builder()
                .member(memberRepository.getReferenceById(memberId))
                .provider(provider)
                .fundCode(fundCode)
                .build());
        if (label != null && !label.isBlank()) {
            row.setLabel(truncate(label.trim(), LABEL_MAX));
        }
        return row;
    }

    private Optional<ScpiPosition> findLinked(Long memberId, ScpiProvider provider, String fundCode) {
        return switch (provider) {
            case CORUM -> positionRepository.findByMemberIdAndCorumFundCode(memberId, fundCode);
            case SOFIDY -> positionRepository.findByMemberIdAndSofidyFundCode(memberId, fundCode);
        };
    }

    private List<ScpiPosition> linkedPositions(Long memberId, ScpiProvider provider) {
        return switch (provider) {
            case CORUM -> positionRepository.findByAccountMemberIdAndCorumFundCodeIsNotNull(memberId);
            case SOFIDY -> positionRepository.findByAccountMemberIdAndSofidyFundCodeIsNotNull(memberId);
        };
    }

    static String fundCode(ScpiPosition position, ScpiProvider provider) {
        return switch (provider) {
            case CORUM -> position.getCorumFundCode();
            case SOFIDY -> position.getSofidyFundCode();
        };
    }

    static void setFundCode(ScpiPosition position, ScpiProvider provider, String fundCode) {
        switch (provider) {
            case CORUM -> position.setCorumFundCode(fundCode);
            case SOFIDY -> position.setSofidyFundCode(fundCode);
        }
    }

    /**
     * Case, accents and punctuation do not distinguish two fund names: "Corum XL",
     * "CORUM-XL" and "corum  xl" are the same vehicle. Nothing looser than that --
     * a fuzzy match is how a phantom link happens.
     */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
