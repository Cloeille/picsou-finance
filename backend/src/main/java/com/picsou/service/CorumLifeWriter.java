package com.picsou.service;

import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.RecurringCadence;
import com.picsou.model.ScpiProvider;
import com.picsou.model.WealthTier;
import com.picsou.port.CorumErrorCode;
import com.picsou.port.CorumPort;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Writes the CORUM Life (assurance-vie) contracts of a CORUM snapshot, one
 * account per contract.
 *
 * <p>For each contract, in this order:
 * <ol>
 *   <li>the live account carrying its external id is updated;</li>
 *   <li>a soft-deleted one stops the sync from bringing it back, and nothing is written;</li>
 *   <li>otherwise, when the snapshot holds exactly one life contract, the one manual
 *       assurance-vie account named "CORUM Life" or filed under the CORUM provider, with no
 *       provider link and no holdings, is adopted -- a holding the user typed by hand is never
 *       wiped;</li>
 *   <li>otherwise, when such manual accounts exist but none can be adopted, nothing is
 *       written ({@link Outcome#AMBIGUOUS}): a new account next to the manual one would
 *       count the contract twice in the net worth;</li>
 *   <li>otherwise a new account is created.</li>
 * </ol>
 * The name is set on creation only, so a rename survives every later sync.
 *
 * <p>The units are written as holdings valued by CORUM itself ({@code providerValueEur}),
 * because no market feed quotes a CORUM unit; {@code AccountService.isProviderValued}
 * treats the non-manual {@link #PROVIDER} accounts as provider-valued for that reason. The euro fund is invested
 * money, so it is a holding too, counted in 1 EUR units, and the account holds no cash.
 *
 * <p>The contract's own net invested amount is the account's cost basis
 * ({@code providerInvestedEur}). CORUM's per-line costs leave out money switched between
 * funds, so a line's cost (average buy-in, PnL) is written only when the lines add up to the
 * contract's; otherwise the lines carry no cost and only the contract-level gain is shown.
 *
 * <p>A line CORUM files under {@code realEstate} is written with the {@code REAL_ESTATE}
 * provider tier; every other line keeps the account's tier. The member's own classification
 * is never written here and wins over it.
 *
 * <p>The contract's operations become its account's transactions through
 * {@link CorumLifeOperationWriter}, for the contracts written here only: an {@code AMBIGUOUS}
 * or {@code IGNORED} contract writes no operation either.
 *
 * <p>The caller validates first with {@link #validate} and then writes inside its own
 * transaction, after locking the member row, so a refused snapshot writes nothing and two
 * syncs of one member cannot both create the same account.
 */
@Service
public class CorumLifeWriter {

    static final String PROVIDER = ScpiProvider.CORUM.displayName();
    private static final String EXTERNAL_ID_PREFIX = "corum_life_";
    private static final String TICKER_PREFIX = "CORUM-LIFE-";
    private static final String ACCOUNT_NAME = "CORUM Life";
    private static final String ACCOUNT_COLOR = "#8b5cf6";
    private static final int ACCOUNT_NAME_MAX = 100;
    private static final int HOLDING_NAME_MAX = 255;
    /** Bounds of the sidecar's wire model; the type is stored in {@code transaction.type}. */
    private static final int OPERATION_ID_MAX = 40;
    private static final int OPERATION_TYPE_MAX = 60;
    /** Scale of derived per-unit figures, matching {@code SyncValues}. */
    private static final int UNIT_PRICE_SCALE = 8;
    private static final Logger log = LoggerFactory.getLogger(CorumLifeWriter.class);

    private final AccountRepository accountRepository;
    private final AccountHoldingRepository holdingRepository;
    private final FamilyMemberRepository memberRepository;
    private final AccountService accountService;
    private final CorumLifeOperationWriter operationWriter;

    public CorumLifeWriter(
        AccountRepository accountRepository,
        AccountHoldingRepository holdingRepository,
        FamilyMemberRepository memberRepository,
        AccountService accountService,
        CorumLifeOperationWriter operationWriter
    ) {
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.memberRepository = memberRepository;
        this.accountService = accountService;
        this.operationWriter = operationWriter;
    }

    /** What a sync did with one contract. {@code AMBIGUOUS} and {@code IGNORED} wrote nothing. */
    public enum Outcome { UPDATED, LINKED, CREATED, AMBIGUOUS, IGNORED }

    /**
     * Refuses a set of contracts that cannot be written as they are.
     *
     * <p>The sidecar already checks all of this; repeating it is deliberate for the
     * reason {@code CorumSyncService.checkDeclaredTotal} gives -- the sidecar is a
     * separate deployable, and a stale image is exactly what would skip its check.
     */
    public void validate(List<CorumPort.LifeContract> contracts) {
        if (contracts == null) {
            throw error(CorumErrorCode.INVALID_DATA, "CORUM returned no life contract list");
        }
        Set<String> codes = new HashSet<>();
        for (CorumPort.LifeContract contract : contracts) {
            if (contract == null || SyncValues.clean(contract.contractCode()) == null) {
                throw error(CorumErrorCode.INVALID_DATA, "CORUM returned a life contract without a code");
            }
            if (!codes.add(contract.contractCode().trim())) {
                // Two reads of one contract would write one account twice.
                throw error(CorumErrorCode.INVALID_DATA, "CORUM returned a duplicate life contract");
            }
            if (!contract.snapshotComplete()) {
                throw error(CorumErrorCode.PORTFOLIO_INCOMPLETE, "CORUM returned an incomplete life contract");
            }
            // The account is EUR and the figures are written as they come.
            if (!"EUR".equals(contract.currency())) {
                throw error(CorumErrorCode.INVALID_DATA, "CORUM returned a life contract not in euros");
            }
            validateFigures(contract);
            validateOperations(contract.operations());
        }
    }

    /**
     * Refuses an operation list that cannot be keyed or dated. Null is "not read" and passes:
     * the stored rows are kept. An operation without an id, a type, a date or an amount cannot
     * be reconciled. Two that still share a key after the sign suffix would both own one row:
     * that list is not refused but dropped by {@link #reportedOperations}, so the stored rows are
     * kept and the valuation still syncs.
     */
    private void validateOperations(List<CorumPort.LifeOperation> operations) {
        if (operations == null) {
            return;
        }
        for (CorumPort.LifeOperation operation : operations) {
            if (operation == null || SyncValues.clean(operation.operationId()) == null
                    || SyncValues.clean(operation.type()) == null
                    || operation.date() == null || operation.amountEur() == null) {
                throw error(CorumErrorCode.INVALID_DATA, "CORUM returned an incomplete life operation");
            }
            if (operation.operationId().trim().length() > OPERATION_ID_MAX
                    || operation.type().trim().length() > OPERATION_TYPE_MAX) {
                throw error(CorumErrorCode.INVALID_DATA, "CORUM returned an unreadable life operation");
            }
        }
        if (CorumLifeOperationWriter.externalIds(operations) == null) {
            // Counts only: the rows carry the member's money.
            log.warn("CORUM Life operations share a key, list not written (operations={})", operations.size());
        }
    }

    /**
     * The contract's operations as the reconciliation takes them: null ("not read", keep the
     * stored rows) when two of them still share a key, which {@link #validate} lets through.
     */
    static List<CorumPort.LifeOperation> reportedOperations(CorumPort.LifeContract contract) {
        List<CorumPort.LifeOperation> operations = contract.operations();
        return operations != null && CorumLifeOperationWriter.externalIds(operations) == null ? null : operations;
    }

    private void validateFigures(CorumPort.LifeContract contract) {
        BigDecimal declared = contract.totalValuationEur();
        if (declared == null || declared.signum() < 0) {
            throw error(CorumErrorCode.PORTFOLIO_INCOMPLETE, "CORUM returned no valid life contract total");
        }
        if (contract.netInvestedEur() != null && contract.netInvestedEur().signum() < 0
                || contract.paidInEur() != null && contract.paidInEur().signum() < 0) {
            throw error(CorumErrorCode.INVALID_DATA, "CORUM returned a negative invested amount");
        }
        if (contract.holdings() == null) {
            throw error(CorumErrorCode.INVALID_DATA, "CORUM returned a life contract without holdings");
        }
        Set<String> tickers = new HashSet<>();
        BigDecimal computed = BigDecimal.ZERO;
        for (CorumPort.LifeHolding holding : contract.holdings()) {
            if (holding == null || SyncValues.clean(holding.productCode()) == null
                    || holding.quantity() == null || holding.valueEur() == null) {
                throw error(CorumErrorCode.INVALID_DATA, "CORUM returned an incomplete life holding");
            }
            if (!tickers.add(ticker(holding))) {
                throw error(CorumErrorCode.INVALID_DATA, "CORUM returned a duplicate life holding");
            }
            if (holding.quantity().signum() < 0) {
                throw error(CorumErrorCode.INVALID_DATA, "CORUM returned a negative unit count");
            }
            if (holding.euroFund() && (holding.quantity().compareTo(holding.valueEur()) != 0
                    || holding.unitPrice() == null || holding.unitPrice().compareTo(BigDecimal.ONE) != 0)) {
                // Valued as quantity x price or as valueEur, the euro fund must come
                // to the same figure, or the balance depends on which path ran.
                throw error(CorumErrorCode.INVALID_DATA, "CORUM returned a euro fund not in 1 EUR units");
            }
            computed = computed.add(holding.valueEur());
        }
        // Fewer raw rows than holdings cannot be true: each holding is at least one row.
        Integer lines = contract.valuationLineCount();
        if (lines == null || lines < contract.holdings().size()) {
            throw error(CorumErrorCode.INVALID_DATA, "CORUM returned no valid line count for a life contract");
        }
        if (!SyncValues.linesClose(computed, declared, lines)) {
            throw error(
                CorumErrorCode.PORTFOLIO_INCOMPLETE,
                "The CORUM life lines add up to " + computed.setScale(2, RoundingMode.HALF_UP)
                    + " but the contract declares " + declared.setScale(2, RoundingMode.HALF_UP)
            );
        }
    }

    /**
     * Writes contracts already accepted by {@link #validate}.
     *
     * @return the outcome per contract code, in snapshot order
     */
    public Map<String, Outcome> write(Long memberId, List<CorumPort.LifeContract> contracts, Instant syncedAt) {
        Map<String, Outcome> outcomes = new LinkedHashMap<>();
        for (CorumPort.LifeContract contract : contracts) {
            String code = contract.contractCode().trim();
            outcomes.put(code, writeContract(memberId, code, contract, contracts.size(), syncedAt));
        }
        return outcomes;
    }

    private Outcome writeContract(
        Long memberId, String code, CorumPort.LifeContract contract, int contractCount, Instant syncedAt
    ) {
        String externalId = EXTERNAL_ID_PREFIX + code;
        Optional<Account> linked = accountRepository.findByExternalAccountIdAndMemberId(externalId, memberId);
        Outcome outcome;
        Account account;
        if (linked.isPresent()) {
            account = linked.get();
            outcome = Outcome.UPDATED;
        } else if (accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(externalId, memberId)) {
            log.info("CORUM Life skipped a soft-deleted account (member={})", memberId);
            return Outcome.IGNORED;
        } else {
            List<Account> candidates = adoptionCandidates(memberId);
            if (contractCount == 1 && candidates.size() == 1
                    && holdingRepository.findByAccount_Id(candidates.get(0).getId()).isEmpty()) {
                account = candidates.get(0);
                outcome = Outcome.LINKED;
            } else if (!candidates.isEmpty()) {
                // Several candidates, holdings typed by hand, or several contracts for one
                // account: the user decides, by renaming or deleting the manual account.
                log.info("A manual CORUM Life account cannot be adopted, contract not written "
                    + "(member={}; matches={}; contracts={})", memberId, candidates.size(), contractCount);
                return Outcome.AMBIGUOUS;
            } else {
                account = Account.builder()
                    .member(memberRepository.getReferenceById(memberId))
                    .name(truncate(contractCount == 1 ? ACCOUNT_NAME : ACCOUNT_NAME + " " + code, ACCOUNT_NAME_MAX))
                    .type(AccountType.ASSURANCE_VIE)
                    .color(ACCOUNT_COLOR)
                    .build();
                outcome = Outcome.CREATED;
            }
        }

        account.setExternalAccountId(externalId);
        account.setProvider(PROVIDER);
        account.setCurrency("EUR");
        account.setManual(false);
        account.setCurrentBalance(contract.totalValuationEur());
        account.setCashBalance(BigDecimal.ZERO);
        account.setLastSyncedAt(syncedAt);
        // Null from a sidecar that predates it, which restores the line-cost valuation.
        account.setProviderInvestedEur(contract.netInvestedEur());
        account.setProviderPaidInEur(contract.paidInEur());
        RecurringCadence cadence = cadenceOf(contract.scheduledPayment());
        account.setScheduledPaymentEur(cadence != null ? contract.scheduledPayment().amountEur() : null);
        account.setScheduledPaymentCadence(cadence);
        // The member's own date wins: it may be the one their paperwork carries.
        if (account.getOpenedAt() == null && contract.openedAt() != null) {
            account.setOpenedAt(contract.openedAt());
        }
        Account saved = accountRepository.save(account);

        holdingRepository.deleteByAccountId(saved.getId());
        holdingRepository.flush();
        BigDecimal linesInvested = BigDecimal.ZERO;
        for (CorumPort.LifeHolding holding : contract.holdings()) {
            linesInvested = SyncValues.sumComplete(linesInvested, holding.netInvestedEur());
        }
        // A switch between funds leaves the lines' costs short of the contract's, and the line
        // switched into shows a gain it never made. Line costs are then dropped rather than shown.
        boolean lineCosts = linesInvested != null && (contract.netInvestedEur() == null
            || SyncValues.linesClose(linesInvested, contract.netInvestedEur(), contract.valuationLineCount()));
        if (!lineCosts && linesInvested != null) {
            log.info("CORUM Life line costs do not add up to the contract's, written without them (member={})",
                memberId);
        }
        holdingRepository.saveAll(contract.holdings().stream()
            .map(holding -> toHolding(saved, holding, lineCosts, syncedAt))
            .toList());
        holdingRepository.flush();

        List<CorumPort.LifeOperation> reported = reportedOperations(contract);
        CorumLifeOperationWriter.Result operations =
            operationWriter.reconcile(memberId, saved, reported, contract.holdings());
        // Counts only: the rows carry the member's money.
        log.info("CORUM Life operations reconciled (member={}; reported={}; inserted={}; updated={}; "
                + "deleted={}; unknownTypes={})", memberId,
            reported == null ? "none" : reported.size(),
            operations.inserted(), operations.updated(), operations.deleted(), operations.unknownTypes());

        BigDecimal invested = contract.netInvestedEur() != null ? contract.netInvestedEur() : linesInvested;
        if (invested != null) {
            accountService.upsertSnapshot(saved, contract.totalValuationEur(), invested, LocalDate.now());
        } else {
            // A line without its paid-in amount leaves the cost basis unknown;
            // AccountService derives it the way it does for every connector.
            accountService.upsertSnapshot(saved, contract.totalValuationEur(), LocalDate.now());
        }
        return outcome;
    }

    private static AccountHolding toHolding(
        Account account, CorumPort.LifeHolding holding, boolean lineCosts, Instant syncedAt
    ) {
        BigDecimal netInvested = lineCosts ? holding.netInvestedEur() : null;
        BigDecimal averageBuyIn = netInvested != null && holding.quantity().signum() > 0
            ? netInvested.divide(holding.quantity(), UNIT_PRICE_SCALE, RoundingMode.HALF_UP)
            : null;
        String label = SyncValues.clean(holding.label());
        return AccountHolding.builder()
            .account(account)
            .ticker(ticker(holding))
            .name(truncate(label != null ? label : "CORUM " + holding.productCode().trim(), HOLDING_NAME_MAX))
            .quantity(holding.quantity())
            .averageBuyIn(averageBuyIn)
            .currentPrice(holding.unitPrice())
            .quoteCurrency("EUR")
            .providerValueEur(holding.valueEur())
            .providerPnlEur(netInvested != null ? holding.valueEur().subtract(netInvested) : null)
            .providerPriceDate(holding.valuationDate())
            .providerWealthTier(providerTier(holding.assetClass()))
            .lastSyncedAt(syncedAt)
            .build();
    }

    /**
     * The tier CORUM's asset-class view gives a line. Only {@code realEstate} is conclusive: the
     * SCPI units are property. Bond funds and the euro fund stay with the account's tier
     * ({@code EQUITY}), as does anything new, so null.
     */
    static WealthTier providerTier(String assetClass) {
        return "realEstate".equals(assetClass) ? WealthTier.REAL_ESTATE : null;
    }

    /**
     * CORUM's frequency word as a cadence. A plan of zero, or a word not known here, is no plan
     * to show: it is logged and left out, never a reason to refuse the contract.
     */
    static RecurringCadence cadenceOf(CorumPort.ScheduledPayment payment) {
        if (payment == null || payment.amountEur() == null || payment.frequency() == null) {
            return null;
        }
        if (payment.amountEur().signum() <= 0) {
            log.info("CORUM Life savings plan of zero, plan left out");
            return null;
        }
        RecurringCadence cadence = switch (payment.frequency().trim().toLowerCase(Locale.ROOT)) {
            case "monthly" -> RecurringCadence.MONTHLY;
            case "quarterly" -> RecurringCadence.QUARTERLY;
            case "annual", "yearly" -> RecurringCadence.YEARLY;
            default -> null;
        };
        if (cadence == null) {
            log.info("CORUM Life savings plan frequency not recognised, plan left out");
        }
        return cadence;
    }

    /**
     * The manual assurance-vie accounts the user may have created for this contract before
     * syncing it: no provider link, and either named "CORUM Life" up to case and punctuation or
     * filed under the CORUM provider (up to case) whatever its name. Only one, with no holdings,
     * is adopted -- replacing holdings is the one write a sync cannot undo.
     */
    private List<Account> adoptionCandidates(Long memberId) {
        String wanted = ScpiFundReconciler.normalize(ACCOUNT_NAME);
        return accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId).stream()
            .filter(account -> account.getType() == AccountType.ASSURANCE_VIE)
            .filter(Account::isManual)
            .filter(account -> account.getExternalAccountId() == null)
            .filter(account -> wanted.equals(ScpiFundReconciler.normalize(account.getName()))
                || account.getProvider() != null && PROVIDER.equalsIgnoreCase(account.getProvider().trim()))
            .toList();
    }

    private static String ticker(CorumPort.LifeHolding holding) {
        return TICKER_PREFIX + holding.productCode().trim().toUpperCase(Locale.ROOT);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static SyncException error(CorumErrorCode code, String message) {
        return new SyncException(message, null, code.name());
    }
}
