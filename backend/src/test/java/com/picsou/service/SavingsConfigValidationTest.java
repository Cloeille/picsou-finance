package com.picsou.service;

import com.picsou.dto.SavingsInterestProjection;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.RateBasis;
import com.picsou.model.SavingsInterestConfig;
import com.picsou.model.SavingsProduct;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Validation rules for {@link SavingsInterestService}:
 * <ul>
 *   <li>Regulated products (LIVRET_A, LDDS, LEP) reject a GROSS rate basis.</li>
 *   <li>Regulated products with NET basis are accepted.</li>
 *   <li>COMMERCIAL + GROSS with no tax rate defaults to 30 % PFU in the computation.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SavingsConfigValidationTest {

    @Mock TransactionRepository transactionRepository;
    @Mock BalanceSnapshotRepository balanceSnapshotRepository;

    SavingsInterestService service;
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

    // ─── Regulated rejects GROSS ──────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = SavingsProduct.class, names = {"LIVRET_A", "LDDS", "LEP"})
    void regulated_grossRateBasis_isRejected(SavingsProduct product) {
        SavingsInterestConfig invalid = SavingsInterestConfig.builder()
            .account(account)
            .product(product)
            .annualRate(new BigDecimal("3.00"))
            .rateBasis(RateBasis.GROSS)
            .build();

        assertThatThrownBy(() -> service.validate(invalid))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(product.toString());
    }

    // ─── Regulated forces NET — no exception ─────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = SavingsProduct.class, names = {"LIVRET_A", "LDDS", "LEP"})
    void regulated_netRateBasis_isAccepted(SavingsProduct product) {
        SavingsInterestConfig valid = SavingsInterestConfig.builder()
            .account(account)
            .product(product)
            .annualRate(new BigDecimal("3.00"))
            .rateBasis(RateBasis.NET)
            .build();

        // Must not throw
        service.validate(valid);
    }

    // ─── COMMERCIAL + GROSS applies default PFU when tax rate is absent ───────

    /**
     * COMMERCIAL 4 % GROSS with NO explicit tax rate.
     * Default PFU (30 %) must be applied: effective net rate = 4 × 0.70 = 2.80 %.
     * Expected annual interest on 5 000 € = 5 000 × 2.80 / 100 = 140.00 €.
     */
    @Test
    void commercial_gross_noTaxRate_defaultsPfu30pct() {
        account = Account.builder()
            .id(1L)
            .name("Livret commercial")
            .type(AccountType.SAVINGS)
            .currency("EUR")
            .currentBalance(new BigDecimal("5000"))
            .color("#6366f1")
            .build();

        SavingsInterestConfig config = SavingsInterestConfig.builder()
            .account(account)
            .product(SavingsProduct.COMMERCIAL)
            .annualRate(new BigDecimal("4.00"))
            .rateBasis(RateBasis.GROSS)
            .taxRatePct(null)  // explicitly absent
            .build();

        stubSnapshotForAccount(LocalDate.of(2025, 1, 1), new BigDecimal("5000"));
        when(transactionRepository.findByAccountIdAndDateBetweenOrderByDateAsc(
            eq(account.getId()), any(), any()
        )).thenReturn(List.of());

        SavingsInterestProjection result = service.computeProjection(
            account, config, LocalDate.of(2025, 12, 31)
        );

        // Effective net rate = 4.00 × (1 − 30/100) = 2.80 %
        assertThat(result.annualRatePct()).isEqualByComparingTo("2.80");
        assertThat(result.estimatedInterestYtd()).isEqualByComparingTo("140.00");
    }

    // ─── COMMERCIAL + NET — no validation error ───────────────────────────────

    @Test
    void commercial_net_isAccepted() {
        SavingsInterestConfig config = SavingsInterestConfig.builder()
            .account(account)
            .product(SavingsProduct.COMMERCIAL)
            .annualRate(new BigDecimal("2.80"))
            .rateBasis(RateBasis.NET)
            .build();

        // Must not throw
        service.validate(config);
    }

    // ─── Internal rate computation helper ────────────────────────────────────

    @Test
    void effectiveNetRatePct_grossWithExplicitTaxRate() {
        SavingsInterestConfig config = SavingsInterestConfig.builder()
            .account(account)
            .product(SavingsProduct.COMMERCIAL)
            .annualRate(new BigDecimal("4.00"))
            .rateBasis(RateBasis.GROSS)
            .taxRatePct(new BigDecimal("30.00"))
            .build();

        assertThat(service.effectiveNetRatePct(config)).isEqualByComparingTo("2.80");
    }

    // ─── Stub helpers ─────────────────────────────────────────────────────────

    private void stubSnapshotForAccount(LocalDate date, BigDecimal balance) {
        BalanceSnapshot snapshot = BalanceSnapshot.builder()
            .account(account)
            .date(date)
            .balance(balance)
            .build();
        when(balanceSnapshotRepository.findByAccountIdAndDateBetweenOrderByDateAsc(
            eq(account.getId()), any(), any()
        )).thenReturn(List.of(snapshot));
    }
}
