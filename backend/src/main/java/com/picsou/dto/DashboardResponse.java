package com.picsou.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record DashboardResponse(
    BigDecimal totalNetWorth,
    BigDecimal totalLiabilities,
    BigDecimal totalMonthlyPayment,      // null if no loan has a Debt row
    List<NetWorthPoint> netWorthHistory,
    List<DistributionItem> distribution,
    List<LiabilityEntry> liabilities,
    List<GoalProgressResponse> goalSummaries
) {
    public record LiabilityEntry(
        Long accountId,
        String name,
        String color,
        BigDecimal balanceEur,
        double percentage,
        String accountType,
        boolean hasHoldings,
        BigDecimal monthlyPayment,
        Double percentPaid,
        BigDecimal paymentDueAmountEur,  // credit cards only; null when unknown
        LocalDate paymentDueDate         // credit cards only; null when unknown
    ) {}

    public record AccountPoint(BigDecimal total, BigDecimal invested, BigDecimal pnl) {}

    public record NetWorthPoint(
        LocalDate date,
        BigDecimal total,
        BigDecimal invested,
        BigDecimal pnl,
        Map<Long, AccountPoint> accounts
    ) {
        public NetWorthPoint(LocalDate date, BigDecimal total, BigDecimal invested, BigDecimal pnl) {
            this(date, total, invested, pnl, null);
        }
    }

    public record DistributionItem(
        Long accountId,
        String name,
        String color,
        BigDecimal balanceEur,
        double percentage,
        String accountType,
        boolean hasHoldings
    ) {}

    public record NetWorthIntradayPoint(
        LocalDateTime timestamp,
        BigDecimal total,
        BigDecimal invested
    ) {}
}
