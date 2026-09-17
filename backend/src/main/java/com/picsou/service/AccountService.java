package com.picsou.service;

import com.picsou.dto.AccountRequest;
import com.picsou.dto.AccountResponse;
import com.picsou.dto.DebtRequest;
import com.picsou.dto.DebtResponse;
import com.picsou.dto.HoldingResponse;
import com.picsou.dto.RealEstateMetadataRequest;
import com.picsou.dto.RealEstateMetadataResponse;
import com.picsou.dto.SavingsConfigDto;
import com.picsou.dto.SnapshotRequest;
import com.picsou.dto.TransactionResponse;
import com.picsou.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.Debt;
import com.picsou.model.FamilyMember;
import com.picsou.model.PropertyValuation;
import com.picsou.model.RealEstateMetadata;
import com.picsou.model.ValuationMode;
import com.picsou.port.BankConnectorPort;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.DebtRepository;
import com.picsou.repository.PropertyValuationRepository;
import com.picsou.repository.RealEstateMetadataRepository;
import com.picsou.repository.SavingsInterestConfigRepository;
import com.picsou.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    /**
     * Providers that report an authoritative EUR valuation for every holding.
     * Yahoo cannot quote some of their instruments -- and never quotes Amundi's
     * FCPE units -- so a partial live total would understate these accounts,
     * for épargne salariale all the way down to zero. See {@link #liveBalanceEur}.
     *
     * <p>BoursoBank belongs here for a different reason: its trading board
     * exposes only its own instrument symbol, so a line whose ISIN cannot be
     * resolved is unpriceable by construction rather than by accident.
     */
    private static final Set<String> PROVIDER_VALUED = Set.of(
        BourseDirectSyncService.PROVIDER,
        AmundiSyncService.PROVIDER,
        BoursoSyncService.PROVIDER
    );

    private final AccountRepository accountRepository;
    private final BalanceSnapshotRepository snapshotRepository;
    private final AccountHoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;
    private final RealEstateMetadataRepository realEstateMetadataRepository;
    private final PropertyValuationRepository propertyValuationRepository;
    private final DebtRepository debtRepository;
    private final SavingsInterestConfigRepository savingsInterestConfigRepository;
    private final PriceService priceService;
    private final LoanAmortizationService loanAmortizationService;
    private final AccountAccessResolver accessResolver;
    private final BankLogoResolver bankLogoResolver;

    public AccountService(
        AccountRepository accountRepository,
        BalanceSnapshotRepository snapshotRepository,
        AccountHoldingRepository holdingRepository,
        TransactionRepository transactionRepository,
        RealEstateMetadataRepository realEstateMetadataRepository,
        PropertyValuationRepository propertyValuationRepository,
        DebtRepository debtRepository,
        SavingsInterestConfigRepository savingsInterestConfigRepository,
        PriceService priceService,
        LoanAmortizationService loanAmortizationService,
        AccountAccessResolver accessResolver,
        BankLogoResolver bankLogoResolver
    ) {
        this.accountRepository = accountRepository;
        this.snapshotRepository = snapshotRepository;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.realEstateMetadataRepository = realEstateMetadataRepository;
        this.propertyValuationRepository = propertyValuationRepository;
        this.debtRepository = debtRepository;
        this.savingsInterestConfigRepository = savingsInterestConfigRepository;
        this.priceService = priceService;
        this.loanAmortizationService = loanAmortizationService;
        this.accessResolver = accessResolver;
        this.bankLogoResolver = bankLogoResolver;
    }

    /**
     * Every account the member can see, co-owned ones included.
     *
     * <p>Balances here are the account's <em>full</em> value, with {@code sharePercent}
     * alongside — a half-owned house is still a €400k house, and the edit form must load the
     * real figure. Weighting belongs to the places that total things up (dashboard, history,
     * real-estate summary), not to the listing.
     */
    public List<AccountResponse> findAll(Long memberId) {
        return findAll(memberId, false);
    }

    /**
     * @param includeHidden false (default) excludes hidden accounts, matching every other
     *                       user-facing account list. true is used only by the /sync visibility
     *                       tab, which must be able to see (and re-show) hidden accounts.
     */
    public List<AccountResponse> findAll(Long memberId, boolean includeHidden) {
        List<Account> accounts = accessResolver.readableAccounts(memberId);
        if (!includeHidden) {
            accounts = accounts.stream().filter(a -> !a.isHidden()).toList();
        }
        Map<Long, BigDecimal> shares = accessResolver.sharesFor(accounts, memberId);
        return accounts.stream()
            .map(a -> toResponse(a, shares.get(a.getId()), memberId))
            .toList();
    }

    public AccountResponse findById(Long id, Long memberId) {
        Account account = accessResolver.requireReadable(id, memberId);
        return toResponse(account, accessResolver.shareFor(account, memberId), memberId);
    }

    @Transactional
    public AccountResponse create(AccountRequest req, FamilyMember member) {
        Account account = Account.builder()
            .member(member)
            .name(req.name())
            .type(req.type())
            .provider(req.provider())
            .currency(req.currency())
            .currentBalance(req.currentBalance() != null ? req.currentBalance() : BigDecimal.ZERO)
            .isManual(req.isManual())
            .color(req.color() != null ? req.color() : "#6366f1")
            .ticker(req.ticker())
            // Nothing stored yet, so nothing survives normalization: a logo key is only ever
            // seeded by WalletSyncService, which builds the wallet's row itself.
            .logoKey(normalizeLogoKey(req.logoKey(), null, req.type()))
            // A hand-entered account has no connector to ask, so the bank it names is looked up
            // in the institution catalog instead — the only logo source open to it.
            .logoUrl(req.isManual() ? bankLogoUrl(req.provider(), req.institutionId()) : null)
            .openedAt(req.openedAt())
            .build();

        account = accountRepository.save(account);

        // Create initial snapshot if balance is provided
        if (account.getCurrentBalance().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal invested = calculateInvestedAmount(account);
            createSnapshot(account, account.getCurrentBalance(), invested, LocalDate.now());
        }

        return toResponse(account);
    }

    @Transactional
    public AccountResponse update(Long id, AccountRequest req, Long memberId) {
        Account account = getOrThrow(id, memberId);

        boolean nameChanged = !req.name().equals(account.getName());
        String previousProvider = account.getProvider();

        account.setName(req.name());
        account.setType(req.type());
        account.setProvider(req.provider());
        account.setCurrency(req.currency());
        refreshBankLogo(account, previousProvider, req.institutionId());
        account.setColor(req.color() != null ? req.color() : account.getColor());
        account.setTicker(req.ticker());
        // Kept when absent, like color rather than like ticker: the logo picker only offers
        // wallets a choice between concrete keys, so a null here means "this client doesn't
        // know about logos" (the MCP update_account tool, an older frontend) rather than
        // "clear it" -- and silently dropping a Ledger back to the generic wallet icon on an
        // unrelated rename would be a surprise.
        account.setLogoKey(normalizeLogoKey(req.logoKey(), account.getLogoKey(), account.getType()));
        // Kept when absent, like logoKey rather than like ticker, and for the same reason: the
        // MCP update_account tool has no such parameter and sends null on every call, so
        // treating null as "clear it" would wipe a PEA's opening date the first time an agent
        // renamed the account. The cost is that the form can change the date but not blank it.
        if (req.openedAt() != null) {
            account.setOpenedAt(req.openedAt());
        }

        // For manual accounts, allow balance update
        if (account.isManual() && req.currentBalance() != null) {
            BigDecimal oldBalance = account.getCurrentBalance();
            account.setCurrentBalance(req.currentBalance());
            if (req.currentBalance().compareTo(oldBalance) != 0) {
                upsertSnapshot(account, req.currentBalance(), LocalDate.now());
            }
        }

        AccountResponse response = toResponse(accountRepository.save(account));

        // When a Revolut pocket is renamed, propagate the new name as merchantLabel on its
        // transactions (mirror legs in the pocket) and on the corresponding wallet-side debits.
        if (nameChanged && account.getParentAccountId() != null
                && account.getExternalAccountId() != null) {
            transactionRepository.updateMerchantLabelByAccountId(account.getId(), req.name());
            transactionRepository.updateMerchantLabelForPocketWalletSide(
                account.getParentAccountId(), account.getExternalAccountId(), req.name());
        }

        return response;
    }

    @Transactional
    public AccountResponse setHidden(Long id, Long memberId, boolean hidden) {
        Account account = getOrThrow(id, memberId);
        account.setHidden(hidden);
        return toResponse(accountRepository.save(account));
    }

    /**
     * Re-resolves a manual account's bank logo when there is a reason to: the bank it names
     * changed, or it never had one. An account whose provider and logo both already hold is
     * left alone, so renaming an account or editing its balance costs no catalog call.
     *
     * <p>Manual accounts only. Every other account's logo belongs to whatever synced it — an
     * Enable Banking account gets its own from the requisition it was created under, and a
     * connector-named one (Trade Republic, BoursoBank...) resolves a bundled asset from
     * {@code provider} client-side. Letting a free-text field overwrite either would bury a
     * brand mark under whatever the catalog happened to match.
     */
    private void refreshBankLogo(Account account, String previousProvider, String institutionId) {
        if (!account.isManual()) return;
        String provider = account.getProvider();
        if (provider == null || provider.isBlank()) {
            account.setLogoUrl(null);
            return;
        }
        boolean providerChanged = !provider.equals(previousProvider);
        if (!providerChanged && account.getLogoUrl() != null) return;
        account.setLogoUrl(bankLogoUrl(provider, institutionId));
    }

    /**
     * The catalog logo for the bank a manual account names, or null when there is nothing to
     * look up, no match, or no connector configured to ask.
     *
     * <p>Falls back to {@link BankConnectorPort#DEFAULT_COUNTRY} when the request carries no
     * institution id to read a country off — a hand-typed name, or the MCP tools. An unfiltered
     * search would be a multi-megabyte fetch on a path that runs on every account edit, so the
     * cost of missing a hand-typed foreign bank is preferred to paying that every time.
     */
    private String bankLogoUrl(String provider, String institutionId) {
        if (provider == null || provider.isBlank()) return null;
        String country = BankLogoResolver.countryOf(institutionId);
        return bankLogoResolver.logoUrlOrNull(
            country != null ? country : BankConnectorPort.DEFAULT_COUNTRY, institutionId, provider);
    }

    @Transactional
    public void delete(Long id, Long memberId) {
        Account account = getOrThrow(id, memberId);
        account.setDeletedAt(Instant.now());
        accountRepository.save(account);
    }

    @Transactional
    public BalanceSnapshot addManualSnapshot(Long accountId, Long memberId, SnapshotRequest req) {
        Account account = getOrThrow(accountId, memberId);

        // Update current balance if this is the most recent snapshot
        Optional<BalanceSnapshot> latest = snapshotRepository.findLatestByAccountId(accountId);
        if (latest.isEmpty() || !req.date().isBefore(latest.get().getDate())) {
            account.setCurrentBalance(req.balance());
            account.setLastSyncedAt(Instant.now());
            accountRepository.save(account);
        }

        return upsertSnapshot(account, req.balance(), req.date());
    }

    public List<BalanceSnapshot> getHistory(Long accountId, Long memberId, LocalDate from, LocalDate to) {
        getOrThrow(accountId, memberId); // validate account exists
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusMonths(12);
        return snapshotRepository.findByAccountIdAndDateBetweenOrderByDateAsc(accountId, effectiveFrom, effectiveTo);
    }

    public List<HoldingResponse> getHoldings(Long accountId, Long memberId) {
        Account account = getOrThrow(accountId, memberId); // validate account exists
        List<AccountHolding> holdings = holdingRepository.findByAccountIdOrderByCurrentPriceDesc(accountId);
        Map<String, PriceService.Quote> quotes = quotesFor(account, holdings);
        return holdings.stream()
            .map(holding -> toHoldingResponse(holding, quotes))
            .toList();
    }

    public List<TransactionResponse> getTransactions(Long accountId, Long memberId) {
        getOrThrow(accountId, memberId); // validate account exists
        return transactionRepository.findByAccountIdOrderByDateDesc(accountId).stream()
            .map(TransactionResponse::from)
            .toList();
    }

    @Transactional
    public AccountHolding upsertHolding(Long accountId, Long memberId, String ticker, String name,
                                         BigDecimal quantity, BigDecimal currentPriceEur) {
        Account account = getOrThrow(accountId, memberId);
        Optional<AccountHolding> existing = holdingRepository.findByAccountIdAndTicker(accountId, ticker);
        AccountHolding holding;
        if (existing.isPresent()) {
            holding = existing.get();
            holding.setQuantity(quantity);
            holding.setCurrentPrice(currentPriceEur);
            holding.setLastSyncedAt(Instant.now());
            // Keep averageBuyIn unchanged — it's the cost basis from first sync
        } else {
            holding = AccountHolding.builder()
                .account(account)
                .ticker(ticker)
                .name(name)
                .quantity(quantity)
                .averageBuyIn(currentPriceEur) // baseline: no PnL at first sync
                .currentPrice(currentPriceEur)
                .lastSyncedAt(Instant.now())
                .build();
        }
        return holdingRepository.save(holding);
    }

    /**
     * Removes holdings of {@code account} whose ticker is not in {@code keepTickers}
     * — i.e. assets the latest sync no longer reports as <em>held</em> (keyed on the
     * balances the adapter returned, never on which prices happened to resolve, so a
     * transient price outage cannot delete a still-held asset). Without this, a sold
     * or moved-out holding lingers at its last quantity and inflates the account's
     * live balance ({@link #liveBalanceEur}) and invested basis forever. An empty
     * {@code keepTickers} clears all holdings (the wallet holds nothing priced/known).
     *
     * <p>Takes the already-resolved {@link Account} (the caller has just loaded and
     * member-scoped it), so no extra ownership lookup is issued on the sync path.
     */
    @Transactional
    public void pruneHoldings(Account account, Set<String> keepTickers) {
        if (keepTickers.isEmpty()) {
            holdingRepository.deleteByAccountId(account.getId());
        } else {
            holdingRepository.deleteByAccountIdAndTickerNotIn(account.getId(), keepTickers);
        }
    }

    // ─── Package-private helpers used by other services ──────────────────────

    /**
     * Calculate the invested amount (cost basis) for an account.
     * For accounts with holdings: SUM(quantity × averageBuyIn), excluding assets that could
     * not be valued at all. For cash accounts: same as the current balance.
     */
    public BigDecimal calculateInvestedAmount(Account account) {
        return valuation(account).investedEur();
    }

    BalanceSnapshot upsertSnapshot(Account account, BigDecimal balance, LocalDate date) {
        BigDecimal invested = calculateInvestedAmount(account);
        return upsertSnapshot(account, balance, invested, date);
    }

    BalanceSnapshot upsertSnapshot(Account account, BigDecimal balance, BigDecimal investedAmount, LocalDate date) {
        Optional<BalanceSnapshot> existing = snapshotRepository.findByAccountIdAndDate(account.getId(), date);
        if (existing.isPresent()) {
            BalanceSnapshot snap = existing.get();
            snap.setBalance(balance);
            snap.setInvestedAmount(investedAmount);
            return snapshotRepository.save(snap);
        }
        return createSnapshot(account, balance, investedAmount, date);
    }

    private BalanceSnapshot createSnapshot(Account account, BigDecimal balance, BigDecimal investedAmount, LocalDate date) {
        return snapshotRepository.save(BalanceSnapshot.builder()
            .account(account)
            .date(date)
            .balance(balance)
            .investedAmount(investedAmount)
            .build());
    }

    /**
     * A bundled logo key only means something on an on-chain wallet: it is seeded by
     * {@link WalletSyncService} at account creation and the picker only offers wallet marks.
     *
     * <p>The stored key is what answers "is this a wallet?", the same test the picker uses —
     * a client can swap one wallet mark for another ({@code stored != null}), never attach one
     * to an account that has none. CRYPTO alone wouldn't do: it also covers exchange accounts,
     * which could then hide their own brand mark behind a Ledger.
     *
     * <p>Enforced here rather than trusted from the client because the key otherwise outlives
     * the reason it exists — retyping a wallet to CHECKING would leave a blockchain mark on it
     * for good, since the picker has no "none" option and {@code update} keeps a key the
     * request omits.
     *
     * @param requested the key the client sent, or {@code null} if it sent none
     * @param stored    the key already on the account, {@code null} on create
     */
    private static String normalizeLogoKey(String requested, String stored, AccountType type) {
        if (stored == null || type != AccountType.CRYPTO) return null;
        return requested != null ? requested : stored;
    }

    Account getOrThrow(Long id, Long memberId) {
        return accountRepository.findByIdAndMemberId(id, memberId)
            .orElseThrow(() -> ResourceNotFoundException.account(id));
    }

    /**
     * Returns the live balance in EUR for an account.
     * For accounts with holdings, computes live total from current prices.
     * For cash accounts, returns the stored balance converted to EUR.
     */
    public BigDecimal liveBalanceEur(Account account) {
        return valuation(account).liveEur();
    }

    /**
     * What an account is worth and what it cost, derived <em>together</em> from one set of
     * prices.
     *
     * @param liveEur     current value; assets that could not be priced at all are left out
     * @param investedEur cost basis over exactly the same assets
     * @param allPriced   false when at least one held asset had no price of any age
     * @param anyPriced   false only when the account holds assets and <em>none</em> could be
     *                    valued. {@code liveEur} is then not a small balance, it is a blank one —
     *                    callers that persist a valuation must refuse rather than record it
     * @param anyStale    true when at least one price is a recorded one rather than a live quote
     */
    public record Valuation(BigDecimal liveEur, BigDecimal investedEur,
                            boolean allPriced, boolean anyPriced, boolean anyStale) {}

    /**
     * Values an account and computes its cost basis in a single pass.
     *
     * <p>The two figures used to be computed independently, and disagreed: the value dropped an
     * asset it could not price while the cost basis kept that asset's full purchase cost. The
     * account then reported a loss the size of the missing position — one rate-limited morning
     * showed -85% on an account that had not moved. Whatever is excluded from one side is now
     * excluded from the other by construction.
     *
     * <p>Prices come from one batched call rather than one lookup per holding. That is not only
     * faster: a per-holding fan-out during an outage means a request per holding <em>per page
     * render</em>, which is how a brief rate-limit sustains itself.
     */
    public Valuation valuation(Account account) {
        if (account.getType() == AccountType.LOAN) {
            BigDecimal outstanding = debtRepository.findByAccountId(account.getId())
                .map(debt -> loanAmortizationService.computeRemainingBalance(debt, LocalDate.now()))
                .orElseGet(() -> priceService.toEur(
                    account.getCurrentBalance(), account.getCurrency(), account.getTicker()));
            return new Valuation(outstanding, account.getCurrentBalance(), true, true, false);
        }

        List<AccountHolding> holdings = holdingRepository.findByAccount_Id(account.getId());
        if (holdings.isEmpty()) {
            BigDecimal cash = priceService.toEur(
                account.getCurrentBalance(), account.getCurrency(), account.getTicker());
            return new Valuation(cash, account.getCurrentBalance(), true, true, false);
        }

        Map<String, PriceService.Quote> quotes = quotesFor(account, holdings);

        BigDecimal cashBalance = account.getCashBalance() != null
            ? account.getCashBalance() : BigDecimal.ZERO;
        BigDecimal liveValue = cashBalance;
        BigDecimal invested = cashBalance;
        // Cost basis over *every* holding, priced or not. Only the provider-valued override below
        // uses it, and only because that override replaces the value with a total covering every
        // holding too — pairing it with the partial basis would invent a gain the size of the
        // positions Yahoo failed to price.
        BigDecimal investedOverAllHoldings = cashBalance;
        boolean allHoldingsPriced = true;
        boolean anyHoldingPriced = false;
        boolean anyStale = false;
        boolean anyCostBasisUnknown = false;

        for (AccountHolding h : holdings) {
            BigDecimal qty = h.getQuantity();
            boolean hasTicker = h.getTicker() != null && !h.getTicker().isBlank();
            PriceService.Quote quote = hasTicker
                ? quotes.get(h.getTicker().toUpperCase(Locale.ROOT)) : null;

            investedOverAllHoldings = investedOverAllHoldings.add(costBasisOf(h));

            if (quote != null) {
                anyHoldingPriced = true;
                if (!quote.live()) anyStale = true;
                liveValue = liveValue.add(qty.multiply(quote.price()));
            } else if (hasTicker && h.getProviderValueEur() != null) {
                // A ticker we could not price at any age, but the connector reported this line's
                // own EUR value (Trade Republic, Bourse Direct). Use it rather than dropping the
                // line. The lookup did fail, so allHoldingsPriced goes false — that is what makes
                // the provider-valued override below prefer the provider's own total. But the
                // line itself is valued, so it counts as priced and its cost basis is counted
                // below: adding the value while dropping the cost is the mismatch that reports a
                // gain the size of the position, the -85% incident with the sign flipped.
                allHoldingsPriced = false;
                liveValue = liveValue.add(h.getProviderValueEur());
                anyHoldingPriced = true;
            } else if (hasTicker) {
                allHoldingsPriced = false;
                // Skipping is deliberate -- a held-but-unpriced asset must not be valued at a
                // guess -- but it is not free: the balance (and any snapshot taken from it)
                // silently shrinks by whatever those holdings were worth. Log it so the dip is
                // explicable rather than mysterious. Note this is now the residual case only:
                // PriceService falls back to the last recorded price first, so reaching here
                // means the asset has no price of any age -- typically a coin with no CoinGecko
                // mapping.
                // signum() != 0 (not > 0): omitting an unpriced SHORT overstates the
                // balance — a liability valued at 0 — which deserves the trace at least
                // as much as the understated long.
                if (qty != null && qty.signum() != 0) {
                    log.warn("No EUR price for holding {} (account {}) -- excluding it from both "
                        + "the live balance and the cost basis", h.getTicker(), account.getId());
                }
                // And out of the cost basis too. Keeping it there while its value is gone is
                // what reported an untouched account as an 85% loss: the whole cost of the
                // positions that failed to price stayed in the denominator.
                continue;
            } else if (h.getProviderValueEur() != null) {
                // No ticker to price, but the connector reported the line's EUR value itself
                // (Bourse Direct is the only one that does). That is a valuation, so count it and
                // its cost basis below, and treat the account as priced: there was no lookup to
                // fail here, and leaving the flag false turned dailySnapshots' one-outage refusal
                // into a permanent one — such an account simply stopped receiving snapshots.
                liveValue = liveValue.add(h.getProviderValueEur());
                anyHoldingPriced = true;
            } else {
                // No ticker *and* no reported value: nothing can put a number on this line. Drop
                // it from both sides, exactly as for a ticker the providers could not price —
                // keeping its cost while its value is missing is the asymmetry that reported an
                // untouched account as an 85% loss, and calling the account priced anyway would
                // let dailySnapshots engrave that gap.
                allHoldingsPriced = false;
                continue;
            }

            BigDecimal costBasis = providerCostBasisEur(h);
            if (costBasis == null && h.getAverageBuyIn() != null) {
                costBasis = h.getAverageBuyIn().multiply(qty);
            }
            if (costBasis == null) {
                anyCostBasisUnknown = true;
            } else {
                invested = invested.add(costBasis);
            }
        }

        if (anyCostBasisUnknown) {
            // A partial cost basis creates a fictitious gain. Until every
            // position is known, use the account value as a neutral baseline.
            invested = account.getCurrentBalance();
            investedOverAllHoldings = account.getCurrentBalance();
        }
        // Some providers report an authoritative total in EUR. If Yahoo/OpenFIGI cannot
        // price even one instrument, prefer that last successful provider valuation over a
        // misleading partial total (cash + only the symbols Yahoo happened to resolve).
        if (!allHoldingsPriced && isProviderValued(account)) {
            // Both sides move together. The provider's total covers every position, so the cost
            // basis must too: pairing it with the partial basis reports a gain the size of the
            // unpriced positions' cost — the same value/cost mismatch as the -85% incident, with
            // the sign flipped, and dailySnapshots would write it into balance_snapshot.
            liveValue = account.getCurrentBalance();
            invested = investedOverAllHoldings;
            // The provider valued them; only our price lookup failed.
            anyHoldingPriced = true;
        }
        return new Valuation(liveValue, invested, allHoldingsPriced, anyHoldingPriced, anyStale);
    }

    /**
     * A holding's EUR cost basis: the provider's own figure when it reports one, else
     * {@code averageBuyIn × quantity}, else zero. Zero rather than null because the only caller
     * is the all-holdings total, whose alternative — dropping the line — is what it exists to
     * avoid; the null case is handled separately by {@code anyCostBasisUnknown}.
     */
    private BigDecimal costBasisOf(AccountHolding holding) {
        BigDecimal costBasis = providerCostBasisEur(holding);
        if (costBasis == null && holding.getAverageBuyIn() != null) {
            costBasis = holding.getAverageBuyIn().multiply(holding.getQuantity());
        }
        return costBasis == null ? BigDecimal.ZERO : costBasis;
    }

    /**
     * Quotes for an account's holdings, in one call.
     *
     * <p>A {@code CRYPTO} account is resolved crypto-only: dozens of coins share a symbol with a
     * listed equity, and the generic route would hand an unmapped one to Yahoo Finance and value
     * it at that company's share price. {@code CryptoExchangeSyncService} has always taken that
     * care on the write side; taking it here too means the read side can no longer disagree with
     * what was synced.
     */
    private Map<String, PriceService.Quote> quotesFor(Account account, List<AccountHolding> holdings) {
        Set<String> tickers = holdings.stream()
            .map(AccountHolding::getTicker)
            .filter(t -> t != null && !t.isBlank())
            .map(t -> t.toUpperCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
        if (tickers.isEmpty()) return Map.of();
        return account.getType() == AccountType.CRYPTO
            ? priceService.getCryptoQuotes(tickers)
            : priceService.getQuotes(tickers);
    }

    /** Null-safe: {@code Set.of(...)} throws on a null lookup, and most accounts have no provider. */
    private boolean isProviderValued(Account account) {
        return account.getProvider() != null && PROVIDER_VALUED.contains(account.getProvider());
    }

    /**
     * Live balance in EUR with liability sign applied: LOAN accounts return a
     * NEGATIVE value (outstanding debt), all other types return liveBalanceEur as-is.
     * Use this for any net-worth-style summation.
     */
    public BigDecimal signedLiveBalanceEur(Account account) {
        BigDecimal value = liveBalanceEur(account);
        return account.getType() == AccountType.LOAN ? value.negate() : value;
    }

    AccountResponse toResponse(Account account) {
        return toResponse(account, null, null);
    }

    /**
     * @param sharePercent the viewer's stake; anything but a full 100% is reported so the UI
     *                     can badge the account as co-owned
     * @param viewerId     who is asking, used to say whether they administer the account
     */
    AccountResponse toResponse(Account account, BigDecimal sharePercent, Long viewerId) {
        BigDecimal balanceEur = liveBalanceEur(account);
        AccountResponse response = AccountResponse.from(account, balanceEur);

        BigDecimal reportedShare =
            sharePercent != null && sharePercent.compareTo(new BigDecimal("100")) != 0
                ? sharePercent
                : null;
        Boolean isOwner = viewerId != null && account.getMember() != null
            ? viewerId.equals(account.getMember().getId())
            : null;
        if (reportedShare != null || isOwner != null) {
            response = response.withViewer(reportedShare, isOwner);
        }

        if (account.getType() == AccountType.REAL_ESTATE) {
            Optional<RealEstateMetadataResponse> meta = realEstateMetadataRepository.findByAccountId(account.getId())
                .map(m -> RealEstateMetadataResponse.from(m, lastValuedAt(account.getId())));
            if (meta.isPresent()) {
                response = response.withRealEstate(meta.get());
            }
        }

        if (account.getType() == AccountType.LOAN) {
            Optional<DebtResponse> debt = debtRepository.findByAccountId(account.getId())
                .map(DebtResponse::from);
            if (debt.isPresent()) {
                response = response.withDebt(debt.get());
            }
        }

        Optional<SavingsConfigDto> savings = savingsInterestConfigRepository.findByAccountId(account.getId())
            .map(SavingsConfigDto::from);
        if (savings.isPresent()) {
            response = response.withSavingsConfig(savings.get());
        }

        return response;
    }

    @Transactional
    public HoldingResponse updateHolding(Long accountId, Long memberId, String ticker,
            BigDecimal quantity, BigDecimal averageBuyIn) {
        Account account = getOrThrow(accountId, memberId);
        AccountHolding h = holdingRepository.findByAccountIdAndTicker(accountId, ticker)
            .orElseThrow(() -> new ResourceNotFoundException("Holding not found"));
        h.setQuantity(quantity);
        if (averageBuyIn != null) h.setAverageBuyIn(averageBuyIn);
        // A user edit invalidates broker-derived valuation/P&L as a coherent
        // pair. A subsequent provider sync will repopulate both fields.
        h.setProviderValueEur(null);
        h.setProviderPnlEur(null);
        holdingRepository.save(h);
        return toHoldingResponse(h, quotesFor(account, List.of(h)));
    }

    @Transactional
    public void deleteHolding(Long accountId, Long memberId, String ticker) {
        getOrThrow(accountId, memberId);
        AccountHolding h = holdingRepository.findByAccountIdAndTicker(accountId, ticker)
            .orElseThrow(() -> new ResourceNotFoundException("Holding not found"));
        holdingRepository.delete(h);
    }

    @Transactional
    public RealEstateMetadataResponse updateRealEstateMetadata(Long accountId, Long memberId, RealEstateMetadataRequest req) {
        Account account = getOrThrow(accountId, memberId);

        RealEstateMetadata metadata = realEstateMetadataRepository.findByAccountId(accountId)
            // member is NOT NULL (V22); building without it violated the constraint on the
            // very first save. Never surfaced because no client called this endpoint until now.
            .orElseGet(() -> RealEstateMetadata.builder()
                .account(account)
                .member(account.getMember())
                .build());

        // Acquisition
        metadata.setPurchasePrice(req.purchasePrice());
        metadata.setPurchaseDate(req.purchaseDate());
        metadata.setAgencyFees(req.agencyFees());
        metadata.setNotaryFees(req.notaryFees());
        metadata.setWorksCost(req.worksCost());

        // Classification
        metadata.setPropertyType(req.propertyType());
        metadata.setCategory(req.category());
        metadata.setDescription(req.description());

        // Address — geocoding is derived from these, so it is invalidated below if they move.
        boolean addressChanged = addressChanged(metadata, req);
        metadata.setAddress(req.address());
        metadata.setPostalCode(req.postalCode());
        metadata.setCity(req.city());
        metadata.setCountry(req.country() != null ? req.country().toUpperCase(Locale.ROOT) : "FR");
        if (addressChanged) {
            // Clearing the INSEE code is what makes the next valuation re-geocode. Keeping a
            // stale code would silently value the new address against the old commune.
            metadata.setInseeCode(null);
            metadata.setLatitude(null);
            metadata.setLongitude(null);
            metadata.setBanId(null);
            metadata.setGeocodeScore(null);
            metadata.setGeocodedAt(null);
        }

        // Characteristics
        metadata.setSurfaceArea(req.surfaceArea());
        metadata.setLandArea(req.landArea());
        metadata.setConstructionYear(req.constructionYear());
        metadata.setRooms(req.rooms());
        metadata.setBedrooms(req.bedrooms());
        metadata.setBathrooms(req.bathrooms());
        metadata.setFloorNumber(req.floorNumber());
        metadata.setFloorsTotal(req.floorsTotal());
        metadata.setHasElevator(req.hasElevator());
        metadata.setGarageCount(req.garageCount() != null ? req.garageCount() : 0);
        metadata.setParkingCount(req.parkingCount() != null ? req.parkingCount() : 0);
        metadata.setHasGarden(Boolean.TRUE.equals(req.hasGarden()));
        metadata.setHasTerrace(Boolean.TRUE.equals(req.hasTerrace()));
        metadata.setHasBalcony(Boolean.TRUE.equals(req.hasBalcony()));
        metadata.setEnergyClass(req.energyClass());

        // Valuation & income
        metadata.setValuationMode(req.valuationMode() != null ? req.valuationMode() : ValuationMode.ESTIMATED);
        metadata.setRentalIncome(req.rentalIncome() != null ? req.rentalIncome() : BigDecimal.ZERO);

        // A property described but never valued would otherwise sit at 0 € and report a 100%
        // loss against its own purchase price. What the user paid is the honest starting
        // point; the first successful estimate replaces it.
        BigDecimal costBasis = metadata.costBasis();
        if (account.getCurrentBalance() == null || account.getCurrentBalance().signum() == 0) {
            if (costBasis.signum() > 0) {
                account.setCurrentBalance(costBasis);
                accountRepository.save(account);
            }
        }

        return RealEstateMetadataResponse.from(
            realEstateMetadataRepository.save(metadata), lastValuedAt(accountId));
    }

    /**
     * When the property was last valued, or null if it never was.
     *
     * <p>Read from {@code property_valuation} rather than tracked on the account, so a
     * property valued before this was surfaced reports its real date instead of waiting for
     * the next monthly refresh. Manual accounts have no {@code lastSyncedAt} to fall back on
     * and would otherwise show no date at all.
     */
    private LocalDate lastValuedAt(Long accountId) {
        return propertyValuationRepository.findFirstByAccountIdOrderByValuedAtDesc(accountId)
            .map(PropertyValuation::getValuedAt)
            .orElse(null);
    }

    /** Whether any component the geocoder consumes differs from what is stored. */
    private static boolean addressChanged(RealEstateMetadata metadata, RealEstateMetadataRequest req) {
        return !java.util.Objects.equals(metadata.getAddress(), req.address())
            || !java.util.Objects.equals(metadata.getPostalCode(), req.postalCode())
            || !java.util.Objects.equals(metadata.getCity(), req.city());
    }

    @Transactional
    public DebtResponse updateDebtMetadata(Long accountId, Long memberId, DebtRequest req) {
        Account account = getOrThrow(accountId, memberId);

        Debt debt = debtRepository.findByAccountId(accountId)
            .orElseGet(() -> Debt.builder()
                .account(account)
                .member(account.getMember())
                .build());

        if (req.linkedAccountId() != null) {
            // Member-scope the linked account like every other lookup in this service —
            // never resolve a request-supplied account id without the member filter.
            Account linked = getOrThrow(req.linkedAccountId(), memberId);
            debt.setLinkedAccount(linked);
        } else {
            debt.setLinkedAccount(null);
        }

        debt.setBorrowedAmount(req.borrowedAmount());
        debt.setInterestRate(req.interestRate());
        debt.setMonthlyPayment(req.monthlyPayment());
        debt.setLenderName(req.lenderName());
        debt.setStartDate(req.startDate());
        debt.setEndDate(req.endDate());
        debt.setInsuranceMonthly(req.insuranceMonthly());
        debt.setFileFees(req.fileFees());

        return DebtResponse.from(debtRepository.save(debt));
    }

    public LoanAmortizationService.LoanScheduleResponse getLoanSummary(Long accountId, Long memberId) {
        Account account = getOrThrow(accountId, memberId);
        if (account.getType() != AccountType.LOAN) {
            throw new IllegalArgumentException("Account is not a loan");
        }
        Debt debt = debtRepository.findByAccountId(accountId)
            .orElseThrow(() -> new ResourceNotFoundException("Debt details not set for account"));
        return loanAmortizationService.compute(debt, LocalDate.now());
    }

    private HoldingResponse toHoldingResponse(AccountHolding holding, Map<String, PriceService.Quote> quotes) {
        BigDecimal currentPrice = holding.getCurrentPrice();
        BigDecimal currentPriceEur = null;
        Instant priceUpdatedAt = null;
        LocalDate priceAsOf = null;
        boolean priceStale = false;

        // Only PriceService (Yahoo/CoinGecko, FX-converted) is trusted as a
        // source of EUR-denominated prices. holding.currentPrice may have been
        // stored by a broker adapter (TR/Bourso) in the security's native
        // currency without conversion — using it as a fallback would silently
        // produce native-as-EUR values. Better to return null and surface
        // "price unknown" than to invent a wrong number.
        if (holding.getTicker() != null && !holding.getTicker().isBlank()) {
            PriceService.Quote quote = quotes.get(holding.getTicker().toUpperCase(Locale.ROOT));
            if (quote != null) {
                currentPriceEur = quote.price();
                priceAsOf = quote.asOf();
                priceStale = !quote.live();
            }
            priceUpdatedAt = holding.getLastSyncedAt();
        }

        BigDecimal quantity = holding.getQuantity();
        BigDecimal averageBuyIn = holding.getAverageBuyIn();
        BigDecimal costBasis = providerCostBasisEur(holding);
        if (costBasis == null && averageBuyIn != null) {
            costBasis = averageBuyIn.multiply(quantity);
        }
        BigDecimal currentValueEur = currentPriceEur != null
            ? currentPriceEur.multiply(quantity)
            : holding.getProviderValueEur();
        BigDecimal pnlEur = currentValueEur != null && costBasis != null
            ? currentValueEur.subtract(costBasis)
            : holding.getProviderPnlEur();
        // abs(): a short position has a negative cost basis, and dividing by it would
        // flip the sign — a winning short would display as a loss. The percentage must
        // carry the sign of the P&L itself, the denominator is only a magnitude.
        // The null check on costBasis is required since pnlEur can now fall back to the
        // provider-reported P&L, which is available even when no cost basis is known.
        BigDecimal pnlPercent = (pnlEur != null && costBasis != null && costBasis.signum() != 0)
            ? pnlEur.divide(costBasis.abs(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
            : null;

        return new HoldingResponse(
            holding.getTicker(),
            holding.getName(),
            quantity,
            averageBuyIn,
            currentPrice,
            holding.getQuoteCurrency(),
            currentValueEur,
            costBasis,
            pnlEur,
            pnlPercent,
            priceUpdatedAt,
            priceAsOf,
            priceStale
        );
    }

    private BigDecimal providerCostBasisEur(AccountHolding holding) {
        if (holding.getProviderValueEur() == null || holding.getProviderPnlEur() == null) {
            return null;
        }
        return holding.getProviderValueEur().subtract(holding.getProviderPnlEur());
    }
}
