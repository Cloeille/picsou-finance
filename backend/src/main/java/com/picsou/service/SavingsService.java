package com.picsou.service;

import com.picsou.dto.AccountResponse;
import com.picsou.dto.SavingsConfigDto;
import com.picsou.dto.SavingsInterestProjection;
import com.picsou.dto.SavingsSuggestionResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.RateBasis;
import com.picsou.model.SavingsInterestConfig;
import com.picsou.model.SavingsProduct;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.SavingsInterestConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Orchestration service for the savings-livret feature.
 *
 * <p>Owns the savings-config lifecycle (create / update / delete) and the interest-projection
 * read path.  The actual quinzaine interest engine lives in {@link SavingsInterestService}
 * (Stream A) — this service delegates to it and never duplicates rate logic.</p>
 *
 * <p><strong>Guardrail (inherited):</strong> no write to {@code account.current_balance} or
 * {@code balance_snapshot} anywhere in this class.</p>
 */
@Service
@Transactional(readOnly = true)
public class SavingsService {

    private final AccountService accountService;
    private final AccountRepository accountRepository;
    private final SavingsInterestConfigRepository savingsConfigRepository;
    private final SavingsInterestService savingsInterestService;
    private final SavingsBookDetector savingsBookDetector;

    public SavingsService(
        AccountService accountService,
        AccountRepository accountRepository,
        SavingsInterestConfigRepository savingsConfigRepository,
        SavingsInterestService savingsInterestService,
        SavingsBookDetector savingsBookDetector
    ) {
        this.accountService = accountService;
        this.accountRepository = accountRepository;
        this.savingsConfigRepository = savingsConfigRepository;
        this.savingsInterestService = savingsInterestService;
        this.savingsBookDetector = savingsBookDetector;
    }

    // ─── Suggestions ─────────────────────────────────────────────────────────

    /**
     * Returns savings-book suggestions for the given member.
     *
     * <p>Eligible accounts must be:</p>
     * <ul>
     *   <li>bank-synced ({@code isManual = false}),</li>
     *   <li>not yet configured (no {@link SavingsInterestConfig} row),</li>
     *   <li>matching a known savings-book name pattern.</li>
     * </ul>
     */
    public List<SavingsSuggestionResponse> getSuggestions(Long memberId) {
        return accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(memberId).stream()
            .filter(a -> !a.isManual())
            .filter(a -> savingsConfigRepository.findByAccountId(a.getId()).isEmpty())
            .flatMap(a -> savingsBookDetector.suggest(a.getName())
                .map(s -> new SavingsSuggestionResponse(
                    a.getId(),
                    a.getName(),
                    s.suggestedProduct(),
                    s.defaultAnnualRate(),
                    s.uncertain()
                ))
                .stream())
            .toList();
    }

    // ─── Config upsert ────────────────────────────────────────────────────────

    /**
     * Creates or updates the savings-interest config for an account.
     *
     * <p>Ownership is enforced via the member-scoped account lookup. Business validation
     * (e.g. regulated + GROSS) is delegated to {@link SavingsInterestService#validate} —
     * an {@link IllegalArgumentException} propagates as HTTP 400 via the
     * {@link com.picsou.exception.GlobalExceptionHandler}.</p>
     *
     * @return the updated {@link AccountResponse} with {@code savingsConfig} populated
     */
    @Transactional
    public AccountResponse upsertConfig(Long accountId, Long memberId, SavingsConfigDto req) {
        Account account = accountService.getOrThrow(accountId, memberId);

        SavingsInterestConfig config = savingsConfigRepository.findByAccountId(accountId)
            .orElseGet(() -> SavingsInterestConfig.builder().account(account).build());

        config.setProduct(req.product());
        config.setAnnualRate(req.annualRate());
        config.setRateBasis(req.rateBasis() != null ? req.rateBasis() : RateBasis.NET);
        config.setTaxRatePct(req.taxRatePct());
        config.setCeiling(req.ceiling());

        // Throws IllegalArgumentException for invalid combos (e.g. regulated + GROSS) → HTTP 400
        savingsInterestService.validate(config);
        savingsConfigRepository.save(config);

        // Setting a savings config IS the user's ratified classification of this account.
        // Reflect it on the account type so the book groups as savings everywhere (bank sync
        // creates every account as CHECKING by default). Dirty-checking flushes on commit.
        account.setType(req.product() == SavingsProduct.LEP ? AccountType.LEP : AccountType.SAVINGS);

        return accountService.toResponse(account);
    }

    // ─── Config delete ────────────────────────────────────────────────────────

    /**
     * Removes the savings-interest config for an account.
     *
     * <p>Idempotent: returns normally even if no config existed.</p>
     */
    @Transactional
    public void deleteConfig(Long accountId, Long memberId) {
        accountService.getOrThrow(accountId, memberId); // member-scope check
        savingsConfigRepository.findByAccountId(accountId)
            .ifPresent(savingsConfigRepository::delete);
    }

    // ─── Interest projection ─────────────────────────────────────────────────

    /**
     * Computes the year-to-date and full-year interest projection for an account.
     *
     * @throws ResourceNotFoundException if no savings config has been set for the account
     */
    public SavingsInterestProjection getProjection(Long accountId, Long memberId) {
        Account account = accountService.getOrThrow(accountId, memberId);
        SavingsInterestConfig config = savingsConfigRepository.findByAccountId(accountId)
            .orElseThrow(() -> new ResourceNotFoundException("No savings config for account " + accountId));
        return savingsInterestService.computeProjection(account, config, LocalDate.now());
    }
}
