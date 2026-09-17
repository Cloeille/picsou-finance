package com.picsou.service;

import com.picsou.dto.RealEstateSummaryResponse;
import com.picsou.dto.RealEstateSummaryResponse.LinkedLoan;
import com.picsou.dto.RealEstateSummaryResponse.PropertyLine;
import com.picsou.model.*;
import com.picsou.repository.DebtRepository;
import com.picsou.repository.PropertyValuationRepository;
import com.picsou.repository.RealEstateMetadataRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Aggregates property wealth: what the properties are worth, and what is still owed on them.
 *
 * <p>The link this rests on already existed — {@code debt.linked_account_id} has pointed a
 * loan at a property since V19 — but nothing ever read it. Gross versus net equity is the
 * question that link was created to answer.
 *
 * <p>This re-slices figures the dashboard already reports; it does not add to them. A linked
 * mortgage stays in {@code totalLiabilities} exactly as before, so nothing is double-counted.
 */
@Service
@Transactional(readOnly = true)
public class RealEstateSummaryService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final AccountAccessResolver accessResolver;
    private final RealEstateMetadataRepository metadataRepository;
    private final PropertyValuationRepository valuationRepository;
    private final DebtRepository debtRepository;
    private final LoanAmortizationService loanAmortizationService;
    private final AccountService accountService;

    public RealEstateSummaryService(AccountAccessResolver accessResolver,
                                    RealEstateMetadataRepository metadataRepository,
                                    PropertyValuationRepository valuationRepository,
                                    DebtRepository debtRepository,
                                    LoanAmortizationService loanAmortizationService,
                                    AccountService accountService) {
        this.accessResolver = accessResolver;
        this.metadataRepository = metadataRepository;
        this.valuationRepository = valuationRepository;
        this.debtRepository = debtRepository;
        this.loanAmortizationService = loanAmortizationService;
        this.accountService = accountService;
    }

    public RealEstateSummaryResponse summarize(Long memberId) {
        List<Account> accounts = accessResolver.readableAccounts(memberId);
        Map<Long, BigDecimal> shares = accessResolver.sharesFor(accounts, memberId);
        LocalDate today = LocalDate.now();

        BigDecimal grossTotal = BigDecimal.ZERO;
        BigDecimal debtTotal = BigDecimal.ZERO;
        BigDecimal costTotal = BigDecimal.ZERO;
        BigDecimal rentTotal = BigDecimal.ZERO;
        List<PropertyLine> lines = new ArrayList<>();

        for (Account account : accounts) {
            if (account.getType() != AccountType.REAL_ESTATE) {
                continue;
            }
            BigDecimal share = shares.get(account.getId());
            if (share == null || share.signum() <= 0) {
                continue;
            }

            BigDecimal gross = AccountAccessResolver.weigh(
                accountService.liveBalanceEur(account), share);

            Optional<RealEstateMetadata> metadata = metadataRepository.findByAccountId(account.getId());
            BigDecimal costBasis = metadata
                .map(m -> AccountAccessResolver.weigh(m.costBasis(), share))
                .orElse(BigDecimal.ZERO);
            BigDecimal rent = metadata
                .map(m -> AccountAccessResolver.weigh(m.getRentalIncome(), share))
                .orElse(BigDecimal.ZERO);

            LoanRollup loans = loansFor(account, memberId, today);
            // Resolved once: the date and the confidence come from the same row, and looking
            // it up per field would double the query count for every property.
            Optional<PropertyValuation> latest = latestValuation(account.getId());

            BigDecimal net = gross.subtract(loans.total());
            lines.add(new PropertyLine(
                account.getId(),
                account.getName(),
                account.getColor(),
                metadata.map(RealEstateMetadata::getPropertyType).orElse(null),
                metadata.map(m -> m.getCategory() != null ? m.getCategory().name() : null).orElse(null),
                metadata.map(RealEstateMetadata::getCity).orElse(null),
                share,
                scale(gross),
                scale(loans.total()),
                scale(net),
                scale(costBasis),
                scale(gross.subtract(costBasis)),
                metadata.map(RealEstateMetadata::getSurfaceArea).orElse(null),
                scale(rent),
                metadata.map(RealEstateMetadata::getValuationMode).orElse(ValuationMode.ESTIMATED),
                latest.map(PropertyValuation::getValuedAt).orElse(null),
                latest.map(PropertyValuation::getConfidence).orElse(null),
                loans.lines()
            ));

            grossTotal = grossTotal.add(gross);
            debtTotal = debtTotal.add(loans.total());
            costTotal = costTotal.add(costBasis);
            rentTotal = rentTotal.add(rent);
        }

        BigDecimal net = grossTotal.subtract(debtTotal);
        BigDecimal gain = grossTotal.subtract(costTotal);
        BigDecimal gainPercent = costTotal.signum() > 0
            ? gain.multiply(HUNDRED).divide(costTotal, 2, RoundingMode.HALF_UP)
            : null;
        BigDecimal ltv = grossTotal.signum() > 0
            ? debtTotal.multiply(HUNDRED).divide(grossTotal, 2, RoundingMode.HALF_UP)
            : null;

        return new RealEstateSummaryResponse(
            scale(grossTotal),
            scale(debtTotal),
            scale(net),
            scale(costTotal),
            scale(gain),
            gainPercent,
            ltv,
            scale(rentTotal),
            lines
        );
    }

    private record LoanRollup(BigDecimal total, List<LinkedLoan> lines) {}

    /**
     * Loans pointing at this property.
     *
     * <p>Each is weighted by the member's share <em>of the loan</em>, not of the property.
     * The two usually match, but nothing forces it — one partner can own more of the house
     * while the mortgage is split evenly — and assuming they are equal would quietly produce
     * the wrong equity.
     */
    private LoanRollup loansFor(Account property, Long memberId, LocalDate asOf) {
        List<Debt> debts = debtRepository.findByLinkedAccountId(property.getId());
        if (debts.isEmpty()) {
            return new LoanRollup(BigDecimal.ZERO, List.of());
        }

        BigDecimal total = BigDecimal.ZERO;
        List<LinkedLoan> lines = new ArrayList<>(debts.size());
        // Resolved for every linked loan in one query. This method runs per property, so the
        // per-loan form costs properties x loans round trips.
        Map<Long, BigDecimal> loanShares = accessResolver.sharesFor(
            debts.stream().map(Debt::getAccount).filter(java.util.Objects::nonNull).toList(),
            memberId);
        for (Debt debt : debts) {
            Account loanAccount = debt.getAccount();
            if (loanAccount == null) {
                continue;
            }
            // A loan the viewer holds no share of contributes nothing to their equity, even
            // though it does reduce the household's.
            BigDecimal loanShare = loanShares.getOrDefault(loanAccount.getId(), BigDecimal.ZERO);
            if (loanShare.signum() <= 0) {
                continue;
            }
            // Same rule as AccountService.valuation: a Debt without dates has no schedule, and
            // the balance stored on the loan account is what the user owes.
            BigDecimal remaining = LoanAmortizationService.hasSchedule(debt)
                ? loanAmortizationService.computeRemainingBalance(debt, asOf)
                : (loanAccount.getCurrentBalance() != null ? loanAccount.getCurrentBalance() : BigDecimal.ZERO);
            BigDecimal outstanding = AccountAccessResolver.weigh(remaining, loanShare);
            total = total.add(outstanding);
            lines.add(new LinkedLoan(
                loanAccount.getId(),
                loanAccount.getName(),
                debt.getLenderName(),
                scale(outstanding),
                loanShare,
                debt.getMonthlyPayment(),
                debt.getEndDate()
            ));
        }
        return new LoanRollup(total, lines);
    }

    private Optional<PropertyValuation> latestValuation(Long accountId) {
        return valuationRepository.findFirstByAccountIdOrderByValuedAtDesc(accountId);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }
}
