package com.picsou.finary;

import com.picsou.exception.SyncException;
import com.picsou.model.*;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.DividendReceiptService;
import com.picsou.service.PriceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Shared persistence logic for Finary imports (both xlsx and API).
 * Handles account snapshots and transaction imports.
 */
@Component
public class FinaryPersistenceHelper {

    private static final Logger log = LoggerFactory.getLogger(FinaryPersistenceHelper.class);

    private final BalanceSnapshotRepository balanceSnapshotRepository;
    private final TransactionRepository transactionRepository;
    private final PriceService priceService;

    public FinaryPersistenceHelper(BalanceSnapshotRepository balanceSnapshotRepository,
                                   TransactionRepository transactionRepository,
                                   PriceService priceService) {
        this.balanceSnapshotRepository = balanceSnapshotRepository;
        this.transactionRepository = transactionRepository;
        this.priceService = priceService;
    }

    /**
     * Snapshots are EUR (the history sums them as such), while a balance or a transaction may be
     * in any currency, so each is converted before it is written or walked back. A missing rate
     * aborts: {@link PriceService#toEur} would hand the amount back unconverted, engraving it
     * into the history as if it were EUR.
     */
    BigDecimal toEurOrFail(BigDecimal amount, String currency) {
        if (amount == null || currency == null || currency.isBlank() || "EUR".equalsIgnoreCase(currency)) {
            return amount;
        }
        BigDecimal rate = priceService.getFxRateEur(currency.toUpperCase(Locale.ROOT));
        if (rate == null) {
            throw new SyncException("No EUR rate for " + currency
                + " -- cannot rebuild the balance history, try again once the rate is available");
        }
        return amount.multiply(rate);
    }

    /**
     * Record types for parsed Finary data (used by both xlsx and API import paths)
     */
    public record ParsedFinaryAccount(
        String name,
        String institution,
        String category,
        BigDecimal balance,
        String currency
    ) {}

    public record ParsedFinaryTransaction(
        String accountName,
        LocalDate date,
        String description,
        BigDecimal amount,
        String type,
        String category,
        String currency
    ) {}

    /**
     * Reconstruct balance snapshots by walking backwards from current balance.
     *
     * <p>Like {@link #reconstructSnapshotsFromDb}, hand-entered statements are preserved:
     * only derived rows are deleted and rebuilt, manual dates are skipped, and nothing is
     * created before the oldest manual statement.
     */
    public int reconstructSnapshots(Account account, ParsedFinaryAccount finaryAcc,
                                    List<ParsedFinaryTransaction> allTransactions) {
        // Filter transactions for this account
        List<ParsedFinaryTransaction> accountTx = allTransactions.stream()
            .filter(tx -> tx.accountName().equalsIgnoreCase(finaryAcc.name()))
            .sorted(Comparator.comparing(ParsedFinaryTransaction::date).reversed())
            .toList();

        BigDecimal anchor = toEurOrFail(finaryAcc.balance(), finaryAcc.currency());

        List<BalanceSnapshot> all =
            balanceSnapshotRepository.findRecentByAccountId(account.getId(), LocalDate.of(2000, 1, 1));
        LocalDate today = LocalDate.now();
        List<BalanceSnapshot> manual =
            all.stream().filter(s -> s.getSource() == SnapshotSource.MANUAL).toList();
        Set<LocalDate> manualDates =
            manual.stream().map(BalanceSnapshot::getDate).collect(Collectors.toSet());
        // Reached statements only: a future statement must not wipe the past history.
        LocalDate oldestManual = manual.stream().map(BalanceSnapshot::getDate)
            .filter(date -> !date.isAfter(today)).min(LocalDate::compareTo).orElse(null);

        if (accountTx.isEmpty()) {
            // No transactions; the anchor is today with the file balance — unless the
            // member typed a statement for today, which wins.
            if (manualDates.contains(today)) {
                return 0;
            }
            balanceSnapshotRepository.findByAccountIdAndDate(account.getId(), today)
                .ifPresent(balanceSnapshotRepository::delete);
            balanceSnapshotRepository.flush();
            balanceSnapshotRepository.save(BalanceSnapshot.builder()
                .account(account)
                .date(today)
                .balance(anchor)
                .investedAmount(anchor)
                .build());
            return 1;
        }

        // Walk backwards through transactions before touching the stored history, so a missing
        // rate leaves it as it was.
        BigDecimal runningBalance = anchor;
        Map<LocalDate, BigDecimal> snapshots = new LinkedHashMap<>();
        // Newest first: when a day's latest transaction comes up, runningBalance is the balance
        // at the end of that day. Record it before subtracting, and only once per day, or the
        // snapshot dated D holds the balance before D's transactions, one day's movements off.
        for (ParsedFinaryTransaction tx : accountTx) {
            snapshots.putIfAbsent(tx.date(), runningBalance);
            runningBalance = runningBalance.subtract(toEurOrFail(tx.amount(), tx.currency()));
        }

        // Delete derived snapshots only (batch delete executes immediately)
        List<BalanceSnapshot> derived =
            all.stream().filter(s -> s.getSource() != SnapshotSource.MANUAL).toList();
        balanceSnapshotRepository.deleteAllInBatch(derived);

        int count = 0;

        // Anchor point: today with the file balance, unless the member typed a statement
        // for today
        if (!manualDates.contains(today)) {
            balanceSnapshotRepository.save(BalanceSnapshot.builder()
                .account(account)
                .date(today)
                .balance(anchor)
                .investedAmount(anchor)
                .build());
            count++;
        }

        // Remove today to avoid duplicate with anchor point above
        snapshots.remove(today);

        // Save snapshots
        for (Map.Entry<LocalDate, BigDecimal> entry : snapshots.entrySet()) {
            if (manualDates.contains(entry.getKey())) {
                continue;
            }
            if (oldestManual != null && entry.getKey().isBefore(oldestManual)) {
                continue;
            }
            balanceSnapshotRepository.save(BalanceSnapshot.builder()
                .account(account)
                .date(entry.getKey())
                .balance(entry.getValue())
                .investedAmount(entry.getValue())
                .build());
            count++;
        }

        return count;
    }

    /**
     * Import transactions for an account
     */
    public int importTransactions(Account account, ParsedFinaryAccount finaryAcc,
                                  List<ParsedFinaryTransaction> allTransactions) {
        // The recreated rows come back untyped, and classification only revisits recent ones.
        DividendReceiptService.Recognitions recognised = DividendReceiptService.Recognitions.of(
            transactionRepository.findByAccountIdAndIsManualFalse(account.getId()));
        // Delete existing transactions for this account
        transactionRepository.deleteByAccountIdAndIsManualFalse(account.getId());

        List<ParsedFinaryTransaction> accountTx = allTransactions.stream()
            .filter(tx -> tx.accountName().equalsIgnoreCase(finaryAcc.name()))
            .toList();

        if (accountTx.isEmpty()) {
            return 0;
        }

        List<Transaction> toInsert = accountTx.stream()
            .map(tx -> Transaction.builder()
                .account(account)
                .date(tx.date())
                .description(tx.description())
                .amount(tx.amount())
                .type(tx.type())
                .category(tx.category())
                .nativeCurrency(tx.currency())
                .build())
            .collect(Collectors.toList());
        toInsert.forEach(recognised::restoreOn);

        transactionRepository.saveAll(toInsert);
        return toInsert.size();
    }

    /**
     * Reconstruct balance snapshots from already-persisted Transaction entities.
     * Same algorithm as reconstructSnapshots() but reads from DB instead of parsed DTOs.
     *
     * <p>Hand-entered statements ({@code MANUAL} rows) are ground truth and are never
     * deleted or overwritten: only the derived rows are rebuilt, manual dates are skipped,
     * and no derived row is created before the oldest manual statement (history before the
     * first statement is unknown — a gap is honest, an invented point is not).
     */
    public int reconstructSnapshotsFromDb(Account account) {
        List<Transaction> accountTx = transactionRepository.findByAccountIdOrderByDateDesc(account.getId());
        BigDecimal currentBalance = toEurOrFail(account.getCurrentBalance(), account.getCurrency());

        List<BalanceSnapshot> all =
            balanceSnapshotRepository.findRecentByAccountId(account.getId(), LocalDate.of(2000, 1, 1));
        LocalDate today = LocalDate.now();
        List<BalanceSnapshot> manual =
            all.stream().filter(s -> s.getSource() == SnapshotSource.MANUAL).toList();
        Set<LocalDate> manualDates =
            manual.stream().map(BalanceSnapshot::getDate).collect(Collectors.toSet());
        // The cutoff only looks at reached statements: a future statement must not wipe
        // the past derived history while the balance still ignores it.
        LocalDate oldestManual = manual.stream().map(BalanceSnapshot::getDate)
            .filter(date -> !date.isAfter(today)).min(LocalDate::compareTo).orElse(null);

        if (accountTx.isEmpty()) {
            // No transactions left: the derived rows were walked back from a ledger that no
            // longer exists, so they go too — then re-anchor today with the current balance,
            // unless the member typed a statement for today, which wins.
            List<BalanceSnapshot> derived =
                all.stream().filter(s -> s.getSource() != SnapshotSource.MANUAL).toList();
            balanceSnapshotRepository.deleteAllInBatch(derived);
            if (manualDates.contains(today)) {
                return 0;
            }
            balanceSnapshotRepository.save(BalanceSnapshot.builder()
                .account(account)
                .date(today)
                .balance(currentBalance)
                .investedAmount(currentBalance)
                .build());
            return 1;
        }

        // Walk backwards through transactions before touching the stored history, so a missing
        // rate leaves it as it was.
        Map<LocalDate, BigDecimal> snapshots = new LinkedHashMap<>();
        BigDecimal runningBalance = currentBalance;
        // Same rule as reconstructSnapshots: the end-of-day balance, recorded once per day
        // before that day's transactions are walked back.
        for (Transaction tx : accountTx) {
            snapshots.putIfAbsent(tx.getDate(), runningBalance);
            runningBalance = runningBalance.subtract(toEurOrFail(tx.getAmount(), tx.getNativeCurrency()));
        }

        // Delete derived snapshots only (batch delete executes immediately)
        List<BalanceSnapshot> derived =
            all.stream().filter(s -> s.getSource() != SnapshotSource.MANUAL).toList();
        balanceSnapshotRepository.deleteAllInBatch(derived);

        int count = 0;

        // Anchor: today with current balance, unless the member typed a statement for today
        if (!manualDates.contains(today)) {
            balanceSnapshotRepository.save(BalanceSnapshot.builder()
                .account(account)
                .date(today)
                .balance(currentBalance)
                .investedAmount(currentBalance)
                .build());
            count++;
        }

        // Remove today to avoid duplicate with anchor
        snapshots.remove(today);

        for (Map.Entry<LocalDate, BigDecimal> entry : snapshots.entrySet()) {
            if (manualDates.contains(entry.getKey())) {
                continue;
            }
            if (oldestManual != null && entry.getKey().isBefore(oldestManual)) {
                continue;
            }
            balanceSnapshotRepository.save(BalanceSnapshot.builder()
                .account(account)
                .date(entry.getKey())
                .balance(entry.getValue())
                .investedAmount(entry.getValue())
                .build());
            count++;
        }

        return count;
    }

    /**
     * Balance of a manual ledger account anchored on the latest hand-entered statement:
     * the statement plus the transactions booked after it. With no statement the ledger is
     * assumed complete from zero and the balance is the sum of every transaction (the
     * previous behaviour, unchanged for statement-free accounts).
     *
     * <p>The anchor is the statement's stored native figure, so a later FX move never changes
     * the balance on its own. Rows written before the native figure existed fall back to
     * converting the EUR value back at the current rate. A statement dated in the future is
     * stored but ignored until its date: a future statement must not drive today's balance.
     */
    public BigDecimal computeManualLedgerBalance(Account account) {
        LocalDate today = LocalDate.now();
        Optional<BalanceSnapshot> anchor = balanceSnapshotRepository
            .findFirstByAccountIdAndSourceAndDateLessThanEqualOrderByDateDesc(
                account.getId(), SnapshotSource.MANUAL, today);
        if (anchor.isEmpty()) {
            return transactionRepository.sumAmountByAccountId(account.getId());
        }
        BalanceSnapshot statement = anchor.get();
        BigDecimal anchorNative = statement.getNativeBalance() != null
            ? statement.getNativeBalance()
            : toNativeOrFail(statement.getBalance(), account.getCurrency());
        // Same-day ordering runs on the entry instant, not the row creation: a DERIVED row
        // recycled into a statement keeps its old created_at. Legacy rows fall back to it.
        java.time.Instant enteredAt = statement.getStatementEnteredAt() != null
            ? statement.getStatementEnteredAt()
            : statement.getCreatedAt();
        return anchorNative.add(transactionRepository.sumAmountByAccountIdAfterStatement(
            account.getId(), statement.getDate(), enteredAt));
    }

    /**
     * Mirror of {@link #toEurOrFail}: converts a EUR snapshot value back to the account's
     * currency. Defensive only: every current MANUAL writer stores the native figure and the
     * migration backfills older rows as DERIVED, so in practice this always carries one.
     * Statements carry their native value and never touch a rate. A missing rate aborts
     * for the same reason — engraving an unconverted value as the balance would silently
     * rewrite what the member owns.
     */
    BigDecimal toNativeOrFail(BigDecimal eurAmount, String currency) {
        if (eurAmount == null || currency == null || currency.isBlank() || "EUR".equalsIgnoreCase(currency)) {
            return eurAmount;
        }
        BigDecimal rate = priceService.getFxRateEur(currency.toUpperCase(Locale.ROOT));
        if (rate == null || rate.signum() == 0) {
            throw new SyncException("No EUR rate for " + currency
                + " -- cannot anchor the balance on the hand-entered statement, try again once the rate is available");
        }
        return eurAmount.divide(rate, 8, RoundingMode.HALF_UP);
    }

    /**
     * Suggest AccountType based on Finary category.
     * This method handles display names from xlsx import ("Checkings", "Savings", etc).
     */
    public static AccountType suggestTypeFromDisplayCategory(String finaryCategory) {
        return switch (finaryCategory) {
            case "Checkings" -> AccountType.CHECKING;
            case "Savings" -> AccountType.SAVINGS;
            case "Investments" -> AccountType.COMPTE_TITRES;
            case "Cryptos" -> AccountType.CRYPTO;
            case "Credits" -> AccountType.OTHER;
            default -> AccountType.OTHER;
        };
    }

    /**
     * Suggest AccountType based on Finary API category (snake_case URL segments).
     * Used by API sync path.
     */
    public static AccountType suggestTypeFromApiCategory(String apiCategory) {
        return switch (apiCategory) {
            case "checkings" -> AccountType.CHECKING;
            case "savings" -> AccountType.SAVINGS;
            case "investments" -> AccountType.COMPTE_TITRES;
            case "cryptos" -> AccountType.CRYPTO;
            case "fonds_euro" -> AccountType.SAVINGS;
            case "loans" -> AccountType.LOAN;
            case "credits" -> AccountType.OTHER;
            case "real_estates", "commodities", "other_assets", "startups" -> AccountType.OTHER;
            default -> AccountType.OTHER;
        };
    }

    /**
     * Default color for account type
     */
    public static String defaultColorForType(AccountType type) {
        return switch (type) {
            case PEA -> "#10b981";
            case COMPTE_TITRES -> "#3b82f6";
            case CRYPTO -> "#f59e0b";
            case SAVINGS -> "#8b5cf6";
            default -> "#6366f1";
        };
    }
}
