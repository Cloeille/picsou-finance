package com.picsou.service;

import com.picsou.dto.SavingsInterestProjection;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.RateBasis;
import com.picsou.model.SavingsInterestConfig;
import com.picsou.model.SavingsProduct;
import com.picsou.model.Transaction;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the French quinzaine interest projection engine.
 *
 * <p>All expected values are hand-computed.  No persistence calls are made —
 * repositories are mocked.</p>
 *
 * <h2>Quinzaine rule recap</h2>
 * <ul>
 *   <li>Deposit in Q_k → starts earning in Q_{k+1}.</li>
 *   <li>Withdrawal in Q_k → stops earning from the start of Q_k.</li>
 *   <li>Interest per quinzaine = capital × net_rate_pct / 2400.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SavingsInterestServiceTest {

    @Mock TransactionRepository transactionRepository;
    @Mock BalanceSnapshotRepository balanceSnapshotRepository;

    SavingsInterestService service;

    /** Reusable account stub. */
    Account account;

    @BeforeEach
    void setUp() {
        service = new SavingsInterestService(transactionRepository, balanceSnapshotRepository);
        account = Account.builder()
            .id(1L)
            .name("Livret A")
            .type(AccountType.SAVINGS)
            .currency("EUR")
            .currentBalance(new BigDecimal("10000"))
            .color("#6366f1")
            .build();
    }

    // ─── (a) Flat all year ────────────────────────────────────────────────────

    /**
     * 10 000 € at 3 % net, no movements, full year → exactly 300.00 €.
     * Hand-computed: 24 quinzaines × 10 000 × 3 / 2400 = 24 × 12.50 = 300.00
     */
    @Test
    void flatAllYear_exactly300() {
        // Earliest snapshot provides the Jan-1 opening capital.
        stubSnapshot(LocalDate.of(2025, 1, 1), new BigDecimal("10000"));
        stubNoTransactions();

        SavingsInterestProjection result = service.computeProjection(
            account, livretA("3.00"), LocalDate.of(2025, 12, 31)
        );

        assertThat(result.estimatedInterestYtd()).isEqualByComparingTo("300.00");
        assertThat(result.projectedInterestFullYear()).isEqualByComparingTo("300.00");
        assertThat(result.nextCapitalizationDate()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(result.annualRatePct()).isEqualByComparingTo("3.00");
        assertThat(result.basis()).isEqualTo(RateBasis.NET);
        assertThat(result.netOfTax()).isTrue();
    }

    // ─── (b) Mid-year deposit — next-quinzaine rule ───────────────────────────

    /**
     * Initial 10 000 €, deposit 1 200 € on Jan 20 (falls in Q2: Jan 16–31) at 3 % net.
     * <pre>
     *   Q1  (Jan  1–15): cap = 10 000, int = 12.50
     *   Q2  (Jan 16–31): cap = 10 000 (deposit NOT yet earning), int = 12.50  → cumDeposits = 1 200 after Q2
     *   Q3  (Feb  1–15): cap = 11 200, int = 14.00
     *   Q4–Q24 (22 Q) : cap = 11 200, int = 14.00 each
     *   Total = 2 × 12.50 + 22 × 14.00 = 25.00 + 308.00 = 333.00
     * </pre>
     */
    @Test
    void midYearDeposit_nextQuinzaineRule() {
        stubSnapshot(LocalDate.of(2025, 1, 1), new BigDecimal("10000"));
        stubTransactions(List.of(
            tx(LocalDate.of(2025, 1, 20), new BigDecimal("1200")) // Q2
        ));

        SavingsInterestProjection result = service.computeProjection(
            account, livretA("3.00"), LocalDate.of(2025, 12, 31)
        );

        assertThat(result.estimatedInterestYtd()).isEqualByComparingTo("333.00");
        assertThat(result.projectedInterestFullYear()).isEqualByComparingTo("333.00");
    }

    // ─── (c) Mid-year withdrawal — same-quinzaine stop ───────────────────────

    /**
     * Initial 10 000 €, withdrawal 1 000 € on Feb 5 (Q3: Feb 1–15) at 3 % net.
     * <pre>
     *   Q1  (Jan  1–15): cap = 10 000, int = 12.50
     *   Q2  (Jan 16–31): cap = 10 000, int = 12.50
     *   Q3  (Feb  1–15): withdrawal stops earning from start of Q3 → cap = 9 000, int = 11.25
     *   Q4–Q24 (21 Q) : cap =  9 000, int = 11.25 each
     *   Total = 2 × 12.50 + 22 × 11.25 = 25.00 + 247.50 = 272.50
     * </pre>
     */
    @Test
    void midYearWithdrawal_sameQuinzaineStop() {
        stubSnapshot(LocalDate.of(2025, 1, 1), new BigDecimal("10000"));
        stubTransactions(List.of(
            tx(LocalDate.of(2025, 2, 5), new BigDecimal("-1000")) // Q3, negative = withdrawal
        ));

        SavingsInterestProjection result = service.computeProjection(
            account, livretA("3.00"), LocalDate.of(2025, 12, 31)
        );

        assertThat(result.estimatedInterestYtd()).isEqualByComparingTo("272.50");
        assertThat(result.projectedInterestFullYear()).isEqualByComparingTo("272.50");
    }

    // ─── (d) COMMERCIAL gross 4 % + 30 % PFU ─────────────────────────────────

    /**
     * COMMERCIAL 4 % GROSS + 30 % PFU → effective net rate = 4 × (1 − 0.30) = 2.80 %.
     * Capital 5 000 €, flat all year.
     * Expected = 5 000 × 2.80 / 100 = 140.00
     */
    @Test
    void commercial_gross4pct_pfu30_effectiveNet2p8() {
        account = accountWith(new BigDecimal("5000"));
        stubSnapshot(LocalDate.of(2025, 1, 1), new BigDecimal("5000"));
        stubNoTransactions();

        SavingsInterestConfig config = commercialGross("4.00", "30.00");

        SavingsInterestProjection result = service.computeProjection(
            account, config, LocalDate.of(2025, 12, 31)
        );

        assertThat(result.estimatedInterestYtd()).isEqualByComparingTo("140.00");
        assertThat(result.projectedInterestFullYear()).isEqualByComparingTo("140.00");
        // Effective net rate shown in the projection
        assertThat(result.annualRatePct()).isEqualByComparingTo("2.80");
        assertThat(result.basis()).isEqualTo(RateBasis.GROSS);
        assertThat(result.netOfTax()).isFalse();
    }

    // ─── (e) COMMERCIAL net 2.8 % ────────────────────────────────────────────

    /**
     * COMMERCIAL 2.80 % NET, same capital → identical result to (d).
     * Net rate passed through as-is.
     */
    @Test
    void commercial_net2p8pct_passThrough() {
        account = accountWith(new BigDecimal("5000"));
        stubSnapshot(LocalDate.of(2025, 1, 1), new BigDecimal("5000"));
        stubNoTransactions();

        SavingsInterestConfig config = commercialNet("2.80");

        SavingsInterestProjection result = service.computeProjection(
            account, config, LocalDate.of(2025, 12, 31)
        );

        assertThat(result.estimatedInterestYtd()).isEqualByComparingTo("140.00");
        assertThat(result.projectedInterestFullYear()).isEqualByComparingTo("140.00");
        assertThat(result.annualRatePct()).isEqualByComparingTo("2.80");
        assertThat(result.netOfTax()).isTrue();
    }

    // ─── (f) Partial-year: YTD ≠ projected full year ─────────────────────────

    /**
     * 10 000 € at 3 % net, no movements, asOf = Jun 1 (start of Q11, k=10).
     * <pre>
     *   YTD  (Q1–Q11 = 11 quinzaines): 11 × 12.50 = 137.50
     *   Proj (Q1–Q24 = 24 quinzaines): 24 × 12.50 = 300.00
     *   137.50 ≠ 300.00 ✓
     * </pre>
     */
    @Test
    void partialYear_ytdDiffersFromProjected() {
        stubSnapshot(LocalDate.of(2025, 1, 1), new BigDecimal("10000"));
        stubNoTransactions();

        SavingsInterestProjection result = service.computeProjection(
            account, livretA("3.00"), LocalDate.of(2025, 6, 1)
        );

        assertThat(result.estimatedInterestYtd()).isEqualByComparingTo("137.50");
        assertThat(result.projectedInterestFullYear()).isEqualByComparingTo("300.00");
        assertThat(result.estimatedInterestYtd())
            .isNotEqualByComparingTo(result.projectedInterestFullYear());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void stubSnapshot(LocalDate date, BigDecimal balance) {
        BalanceSnapshot snapshot = BalanceSnapshot.builder()
            .account(account)
            .date(date)
            .balance(balance)
            .build();
        when(balanceSnapshotRepository.findByAccountIdAndDateBetweenOrderByDateAsc(
            eq(account.getId()), any(), any()
        )).thenReturn(List.of(snapshot));
    }

    private void stubTransactions(List<Transaction> txs) {
        when(transactionRepository.findByAccountIdAndDateBetweenOrderByDateAsc(
            eq(account.getId()), any(), any()
        )).thenReturn(txs);
    }

    private void stubNoTransactions() {
        when(transactionRepository.findByAccountIdAndDateBetweenOrderByDateAsc(
            eq(account.getId()), any(), any()
        )).thenReturn(List.of());
    }

    private Transaction tx(LocalDate date, BigDecimal amount) {
        return Transaction.builder()
            .account(account)
            .date(date)
            .description("test")
            .amount(amount)
            .build();
    }

    private SavingsInterestConfig livretA(String rate) {
        return SavingsInterestConfig.builder()
            .account(account)
            .product(SavingsProduct.LIVRET_A)
            .annualRate(new BigDecimal(rate))
            .rateBasis(RateBasis.NET)
            .build();
    }

    private SavingsInterestConfig commercialGross(String rate, String taxPct) {
        return SavingsInterestConfig.builder()
            .account(account)
            .product(SavingsProduct.COMMERCIAL)
            .annualRate(new BigDecimal(rate))
            .rateBasis(RateBasis.GROSS)
            .taxRatePct(new BigDecimal(taxPct))
            .build();
    }

    private SavingsInterestConfig commercialNet(String rate) {
        return SavingsInterestConfig.builder()
            .account(account)
            .product(SavingsProduct.COMMERCIAL)
            .annualRate(new BigDecimal(rate))
            .rateBasis(RateBasis.NET)
            .build();
    }

    private Account accountWith(BigDecimal balance) {
        return Account.builder()
            .id(1L)
            .name("Test account")
            .type(AccountType.SAVINGS)
            .currency("EUR")
            .currentBalance(balance)
            .color("#6366f1")
            .build();
    }
}
