package com.picsou.service;

import com.picsou.dto.UpcomingDividendResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.DistributionFrequency;
import com.picsou.model.DividendBasis;
import com.picsou.model.DividendCadence;
import com.picsou.model.DividendEvent;
import com.picsou.model.DividendOrigin;
import com.picsou.model.DividendPolicy;
import com.picsou.model.DividendSource;
import com.picsou.model.ScpiPosition;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.DividendSourceRepository;
import com.picsou.repository.ScpiPositionRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.repository.TransactionRepository.DividendCashDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Projects the next dividends a member can see, from stored history and from declared SCPI
 * distributions. Never calls Yahoo: a missing source is a missing line, not a scrape.
 */
@Service
@Transactional(readOnly = true)
public class DividendForecastService {

    static final int MIN_HORIZON_DAYS = 1;
    static final int MAX_HORIZON_DAYS = 366;
    private static final int MONEY_SCALE = 8;
    /** A cash credit more than this many days after the ex-date is a different payment. */
    private static final int PAYMENT_LAG_WINDOW_DAYS = 60;

    private static final Comparator<UpcomingDividendResponse> BY_DATE_THEN_NAME =
        Comparator.comparing(UpcomingDividendResponse::expectedDate)
            .thenComparing(UpcomingDividendResponse::accountName, Comparator.nullsLast(String::compareTo))
            .thenComparing(UpcomingDividendResponse::ticker, Comparator.nullsLast(String::compareTo));

    private final AccountAccessResolver access;
    private final AccountHoldingRepository holdings;
    private final DividendSourceRepository sources;
    private final ScpiPositionRepository positions;
    private final TransactionRepository transactions;

    public DividendForecastService(
        AccountAccessResolver access,
        AccountHoldingRepository holdings,
        DividendSourceRepository sources,
        ScpiPositionRepository positions,
        TransactionRepository transactions
    ) {
        this.access = access;
        this.holdings = holdings;
        this.sources = sources;
        this.positions = positions;
        this.transactions = transactions;
    }

    /** Horizon is clamped to {@code [1, 366]} so a caller cannot ask for the next decade. */
    public static int clampHorizon(int horizonDays) {
        return Math.clamp(horizonDays, MIN_HORIZON_DAYS, MAX_HORIZON_DAYS);
    }

    public List<UpcomingDividendResponse> upcoming(Long memberId, LocalDate today, int horizonDays) {
        return upcoming(memberId, today, horizonDays, null);
    }

    /**
     * Payments from today through {@code horizonDays} (clamped to 1..366). A non-null
     * {@code accountId} must be readable by the member. The line id stays
     * {@code SOURCE:accountId:instrument:expectedDate}.
     */
    public List<UpcomingDividendResponse> upcoming(
        Long memberId, LocalDate today, int horizonDays, Long accountId
    ) {
        if (accountId != null) {
            access.requireReadable(accountId, memberId);
        }
        int horizon = clampHorizon(horizonDays);
        LocalDate end = today.plusDays(horizon);
        List<Account> readable = access.readableAccounts(memberId);
        if (readable.isEmpty()) {
            return List.of();
        }
        List<Account> scoped = accountId == null
            ? readable
            : readable.stream().filter(account -> accountId.equals(account.getId())).toList();
        if (scoped.isEmpty()) {
            return List.of();
        }
        Map<Long, BigDecimal> shares = access.sharesFor(scoped, memberId);
        List<UpcomingDividendResponse> lines = new ArrayList<>();
        lines.addAll(holdingLines(scoped, readable, shares, today, end));
        lines.addAll(scpiLines(scoped, shares, today, end));
        lines.sort(BY_DATE_THEN_NAME);
        return lines;
    }

    private List<UpcomingDividendResponse> holdingLines(
        List<Account> accounts,
        List<Account> lagAccounts,
        Map<Long, BigDecimal> shares,
        LocalDate today,
        LocalDate end
    ) {
        Map<Long, Account> investment = accounts.stream()
            // EMPLOYEE_SAVINGS holds lines but is not an investment account. Widening
            // isInvestment() would recompute its balance from trades. It stays out of the
            // budget: the type is a locked wrapper, so countsInBudget is false.
            .filter(a -> a.getType() != null && a.getType() != AccountType.CRYPTO
                && (a.getType().isInvestment() || a.getType() == AccountType.EMPLOYEE_SAVINGS))
            .collect(Collectors.toMap(Account::getId, a -> a, (a, b) -> a));
        if (investment.isEmpty()) {
            return List.of();
        }
        List<AccountHolding> rows = holdings.findPositiveByAccountIdIn(investment.keySet());
        List<AccountHolding> held = rows.stream()
            .filter(h -> h.getQuantity() != null && h.getQuantity().signum() > 0)
            .filter(h -> h.getTicker() != null && !h.getTicker().isBlank())
            .toList();
        if (held.isEmpty()) {
            return List.of();
        }
        Map<String, DividendSource> byTicker = sources.findAllWithEventsByTickerIn(held.stream()
                .map(h -> h.getTicker().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet()))
            .stream()
            // Any status: a FAILED refresh keeps the stored events on purpose (see
            // DividendHistoryService), so the last good history keeps forecasting. A source
            // with no events yields no dates and no line.
            .collect(Collectors.toMap(s -> s.getTicker().toUpperCase(Locale.ROOT), s -> s, (a, b) -> a));

        List<DividendCashDate> cashDates = dividendCashDates(lagAccounts);
        List<UpcomingDividendResponse> lines = new ArrayList<>();
        for (AccountHolding holding : held) {
            DividendSource source = byTicker.get(holding.getTicker().toUpperCase(Locale.ROOT));
            if (source == null) {
                continue;
            }
            Account account = investment.get(accountIdOf(holding));
            if (account == null) {
                continue;
            }
            List<LocalDate> dates = datesInWindow(source.getEvents(), today);
            List<DividendEvent> announced = announcedInHorizon(source.getEvents(), today, end);
            DividendCadence cadence = cadenceOf(dates);
            boolean canProject = cadence != null && !dates.isEmpty()
                && !isStale(dates.get(dates.size() - 1), cadence, today);
            DividendEvent last = canProject ? eventOn(source.getEvents(), dates.get(dates.size() - 1)) : null;
            if (last == null || last.getAmount() == null || last.getAmountEur() == null) {
                canProject = false;
            }
            if (!canProject && announced.isEmpty()) {
                continue;
            }
            BigDecimal share = shares.get(account.getId());
            String name = holding.getName() == null || holding.getName().isBlank()
                ? holding.getTicker() : holding.getName();
            Integer lag = paymentLagDays(account.getId(), holding.getTicker(), source.getEvents(), cashDates);
            String currency = source.getCurrency() == null ? "EUR" : source.getCurrency();
            for (DividendEvent declared : announced) {
                if (declared.getAmount() == null || declared.getAmountEur() == null) {
                    continue;
                }
                // ANNOUNCED uses the source payment date. When that date is null, the learned
                // lag fills it and the line is marked estimated. No lag means null, never a default.
                LocalDate paymentDate = declared.getPaymentDate();
                boolean paymentDateEstimated = false;
                if (paymentDate == null && lag != null) {
                    paymentDate = declared.getExDate().plusDays(lag);
                    paymentDateEstimated = true;
                }
                lines.add(new UpcomingDividendResponse(
                    id(DividendOrigin.HOLDING, account.getId(), holding.getTicker(), declared.getExDate()),
                    DividendOrigin.HOLDING,
                    account.getId(),
                    account.getName(),
                    holding.getTicker(),
                    name,
                    declared.getExDate(),
                    cadence,
                    DividendBasis.ANNOUNCED,
                    holding.getQuantity(),
                    declared.getAmount(),
                    currency,
                    AccountAccessResolver.weigh(holding.getQuantity().multiply(declared.getAmountEur()), share),
                    false,
                    account.getType(),
                    countsInBudget(account.getType(), false),
                    declared.getExDate(),
                    paymentDate,
                    paymentDateEstimated,
                    declared.getAmountEur()
                ));
            }
            if (!canProject) {
                continue;
            }
            BigDecimal amountEur = AccountAccessResolver.weigh(
                holding.getQuantity().multiply(last.getAmountEur()), share);
            LocalDate lastDate = dates.get(dates.size() - 1);
            List<LocalDate> announcedDates = announced.stream().map(DividendEvent::getExDate).toList();
            for (int k = 1; k <= 24; k++) {
                LocalDate projected = lastDate.plusMonths((long) cadence.months() * k);
                if (projected.isAfter(end)) {
                    break;
                }
                if (projected.isBefore(today) || nearAnnounced(projected, announcedDates)) {
                    continue;
                }
                lines.add(new UpcomingDividendResponse(
                    id(DividendOrigin.HOLDING, account.getId(), holding.getTicker(), projected),
                    DividendOrigin.HOLDING,
                    account.getId(),
                    account.getName(),
                    holding.getTicker(),
                    name,
                    projected,
                    cadence,
                    DividendBasis.HISTORY_CADENCE,
                    holding.getQuantity(),
                    last.getAmount(),
                    currency,
                    amountEur,
                    false,
                    account.getType(),
                    countsInBudget(account.getType(), false),
                    projected,
                    lag == null ? null : projected.plusDays(lag),
                    lag != null,
                    last.getAmountEur()
                ));
            }
        }
        return lines;
    }

    private List<UpcomingDividendResponse> scpiLines(
        List<Account> accounts,
        Map<Long, BigDecimal> shares,
        LocalDate today,
        LocalDate end
    ) {
        Map<Long, Account> scpi = accounts.stream()
            .filter(a -> a.getType() == AccountType.SCPI)
            .collect(Collectors.toMap(Account::getId, a -> a, (a, b) -> a));
        if (scpi.isEmpty()) {
            return List.of();
        }
        // Account ids are already the readable set. Filtering the position by the viewer's
        // member id would hide a co-owned fund: the row belongs to the account's owner.
        List<ScpiPosition> rows = positions.findWithAccountByAccountIdIn(scpi.keySet());
        List<UpcomingDividendResponse> lines = new ArrayList<>();
        for (ScpiPosition position : rows) {
            if (position.getAnnualDistributionPerShareEur() == null
                || position.getAnnualDistributionPerShareEur().signum() <= 0
                || position.getDistributionFrequency() == null
                || position.getShareCount() == null
                || position.getShareCount().signum() <= 0) {
                continue;
            }
            Account account = position.getAccount();
            if (account == null || !scpi.containsKey(account.getId()) || account.getType() != AccountType.SCPI) {
                continue;
            }
            DistributionFrequency frequency = position.getDistributionFrequency();
            DividendCadence cadence = frequency == DistributionFrequency.MONTHLY
                ? DividendCadence.MONTHLY : DividendCadence.QUARTERLY;
            int periods = frequency == DistributionFrequency.MONTHLY ? 12 : 4;
            BigDecimal perPayment = position.getAnnualDistributionPerShareEur()
                .divide(BigDecimal.valueOf(periods), MONEY_SCALE, RoundingMode.HALF_UP);
            boolean reinvested = position.getDividendPolicy() == DividendPolicy.REINVEST;
            BigDecimal amountEur = AccountAccessResolver.weigh(
                position.getShareCount().multiply(perPayment), shares.get(account.getId()));
            String ticker = blankToNull(account.getTicker());
            String idKey = ticker != null ? ticker : blankToNull(position.getIsin());
            for (YearMonth month = YearMonth.from(today); !month.isAfter(YearMonth.from(end)); month = month.plusMonths(1)) {
                if (!paysIn(month, frequency)) {
                    continue;
                }
                LocalDate payment = month.atDay(25);
                if (payment.isBefore(today) || payment.isAfter(end)) {
                    continue;
                }
                // The 25th is the month after the period ends, for both cadences: a quarterly
                // April payment closes the quarter that ended in March.
                LocalDate periodEnd = month.minusMonths(1).atEndOfMonth();
                if (position.getJouissanceDate() != null && periodEnd.isBefore(position.getJouissanceDate())) {
                    continue;
                }
                lines.add(new UpcomingDividendResponse(
                    id(DividendOrigin.SCPI, account.getId(), idKey, payment),
                    DividendOrigin.SCPI,
                    account.getId(),
                    account.getName(),
                    ticker,
                    account.getName(),
                    payment,
                    cadence,
                    DividendBasis.SCPI_DECLARED,
                    position.getShareCount(),
                    perPayment,
                    "EUR",
                    amountEur,
                    reinvested,
                    account.getType(),
                    countsInBudget(account.getType(), reinvested),
                    null,
                    payment,
                    true,
                    perPayment
                ));
            }
        }
        return lines;
    }

    private static boolean paysIn(YearMonth month, DistributionFrequency frequency) {
        if (frequency == DistributionFrequency.MONTHLY) {
            return true;
        }
        int m = month.getMonthValue();
        return m == 1 || m == 4 || m == 7 || m == 10;
    }

    /**
     * Ex-dates in the last 24 months, oldest first. A date after {@code today} is a published
     * future ex-date; the projection rebuilds those, so counting it here would shift the cadence.
     */
    private static List<LocalDate> datesInWindow(Collection<DividendEvent> events, LocalDate today) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        LocalDate windowStart = today.minusMonths(24);
        return events.stream()
            .map(DividendEvent::getExDate)
            .filter(date -> date != null && !date.isBefore(windowStart) && !date.isAfter(today))
            .distinct()
            .sorted()
            .toList();
    }

    /**
     * A cadence only if every consecutive gap falls in one band, and there are enough events
     * for that band. Otherwise null — an irregular payer is not forecast.
     */
    static DividendCadence cadenceOf(List<LocalDate> dates) {
        if (dates == null || dates.size() < 2) {
            return null;
        }
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < dates.size(); i++) {
            gaps.add(ChronoUnit.DAYS.between(dates.get(i - 1), dates.get(i)));
        }
        for (DividendCadence cadence : DividendCadence.values()) {
            if (dates.size() < cadence.minEvents()) {
                continue;
            }
            if (gaps.stream().allMatch(cadence::acceptsGap)) {
                return cadence;
            }
        }
        return null;
    }

    /**
     * A payer that has missed a cycle and a half has stopped, not slipped.
     *
     * <p>The period is the cadence in months. A half period is {@code months * 15} days, so
     * {@code last + 1.5 periods} uses the same month arithmetic as the projection
     * ({@code plusMonths}) rather than a 30-day approximation that would drift from it.
     * Equal to today is still a payer; strictly before today is stale.
     */
    static boolean isStale(LocalDate lastExDate, DividendCadence cadence, LocalDate today) {
        LocalDate limit = lastExDate.plusMonths(cadence.months()).plusDays(cadence.months() * 15L);
        return limit.isBefore(today);
    }

    /** A declared ex-date replaces the cadence guess for that period, inclusive of 20 days. */
    private static final int ANNOUNCED_REPLACES_WITHIN_DAYS = 20;

    /** A declared ex-date, or any ex-date still after today, inside the horizon. */
    private static List<DividendEvent> announcedInHorizon(
        Collection<DividendEvent> events, LocalDate today, LocalDate end
    ) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        return events.stream()
            .filter(event -> event.getExDate() != null)
            .filter(event -> event.isDeclared() || event.getExDate().isAfter(today))
            .filter(event -> !event.getExDate().isBefore(today) && !event.getExDate().isAfter(end))
            .toList();
    }

    private static boolean nearAnnounced(LocalDate projected, List<LocalDate> announcedDates) {
        for (LocalDate announced : announcedDates) {
            if (Math.abs(ChronoUnit.DAYS.between(projected, announced)) <= ANNOUNCED_REPLACES_WITHIN_DAYS) {
                return true;
            }
        }
        return false;
    }

    static boolean countsInBudget(AccountType type, boolean reinvested) {
        return !reinvested && (type == null || !type.isLockedWrapper());
    }

    static String id(DividendOrigin origin, Long accountId, String tickerOrIsin, LocalDate date) {
        String key = tickerOrIsin == null || tickerOrIsin.isBlank() ? "-" : tickerOrIsin;
        return origin.name() + ":" + accountId + ":" + key + ":" + date;
    }

    private static DividendEvent eventOn(Collection<DividendEvent> events, LocalDate date) {
        if (events == null) {
            return null;
        }
        return events.stream().filter(e -> date.equals(e.getExDate())).findFirst().orElse(null);
    }

    private static Long accountIdOf(AccountHolding holding) {
        return holding.getAccount() == null ? null : holding.getAccount().getId();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * One lookup for the whole forecast, covering every readable account so a line can fall
     * back to another account of the same ticker. Not one query per line.
     */
    private List<DividendCashDate> dividendCashDates(List<Account> lagAccounts) {
        List<Long> ids = lagAccounts.stream().map(Account::getId).filter(Objects::nonNull).toList();
        List<DividendCashDate> cashDates = transactions.findDividendCashDates(ids);
        return cashDates == null ? List.of() : cashDates;
    }

    /**
     * Days from ex-date to cash credit. Stored {@code payment_date} values for the ticker
     * come first (median of payment date minus ex-date, no 60-day window: those dates were
     * published). Otherwise the same account and ticker, then the same ticker on any account
     * already loaded. Each transaction pairs with the latest stored ex-date on or before its
     * date and at most {@value #PAYMENT_LAG_WINDOW_DAYS} days earlier. The lag is the median
     * of those day counts. An even count uses the lower of the two central values
     * ({@code (n - 1) / 2}), which rounds down instead of averaging a half-day.
     * No pair means unknown: null, never a default.
     */
    static Integer paymentLagDays(
        Long accountId,
        String ticker,
        Collection<DividendEvent> events,
        List<DividendCashDate> cashDates
    ) {
        List<LocalDate> exDates = storedExDates(events);
        Integer fromPayments = medianLag(storedPaymentLags(events));
        if (fromPayments != null) {
            return fromPayments;
        }
        if (exDates.isEmpty() || cashDates == null || cashDates.isEmpty() || ticker == null) {
            return null;
        }
        if (accountId != null) {
            Integer onAccount = medianLag(pairedLags(cashDates, exDates, accountId, ticker, true));
            if (onAccount != null) {
                return onAccount;
            }
        }
        return medianLag(pairedLags(cashDates, exDates, accountId, ticker, false));
    }

    private static List<Long> storedPaymentLags(Collection<DividendEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<Long> lags = new ArrayList<>();
        for (DividendEvent event : events) {
            if (event.getExDate() == null || event.getPaymentDate() == null) {
                continue;
            }
            lags.add(ChronoUnit.DAYS.between(event.getExDate(), event.getPaymentDate()));
        }
        return lags;
    }

    private static List<LocalDate> storedExDates(Collection<DividendEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        return events.stream()
            .map(DividendEvent::getExDate)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
    }

    private static List<Long> pairedLags(
        List<DividendCashDate> cashDates,
        List<LocalDate> exDates,
        Long accountId,
        String ticker,
        boolean sameAccountOnly
    ) {
        List<Long> lags = new ArrayList<>();
        for (DividendCashDate cash : cashDates) {
            if (cash.getDate() == null || !sameTicker(cash.getTicker(), ticker)) {
                continue;
            }
            if (sameAccountOnly && !accountId.equals(cash.getAccountId())) {
                continue;
            }
            Long lag = lagAgainstLatestEx(exDates, cash.getDate());
            if (lag != null) {
                lags.add(lag);
            }
        }
        return lags;
    }

    /** Latest stored ex-date on or before the cash date, when that gap is within the window. */
    private static Long lagAgainstLatestEx(List<LocalDate> exDatesAscending, LocalDate cashDate) {
        LocalDate matched = null;
        for (LocalDate exDate : exDatesAscending) {
            if (exDate.isAfter(cashDate)) {
                break;
            }
            matched = exDate;
        }
        if (matched == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(matched, cashDate);
        return days <= PAYMENT_LAG_WINDOW_DAYS ? days : null;
    }

    /**
     * Lower median. Even counts take index {@code (n - 1) / 2}, the lower central value,
     * rather than the mean of the two central values.
     */
    private static Integer medianLag(List<Long> lags) {
        if (lags == null || lags.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(lags);
        sorted.sort(Comparator.naturalOrder());
        return Math.toIntExact(sorted.get((sorted.size() - 1) / 2));
    }

    private static boolean sameTicker(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.toUpperCase(Locale.ROOT).equals(right.toUpperCase(Locale.ROOT));
    }
}
