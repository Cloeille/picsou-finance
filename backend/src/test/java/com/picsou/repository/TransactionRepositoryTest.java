package com.picsou.repository;

import com.picsou.model.Account;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TransactionRepository#findByAccountIdAndTxTypeInOrderByDateAscIdAsc} feeds the
 * order-sensitive realized-P&L moving-average pass ({@code RealizedPnlService}) and the holdings
 * recompute pass ({@code HoldingComputeService}). {@code Transaction.date} is a day-granularity
 * {@code LocalDate}, so two same-day rows have undefined order under a {@code date}-only
 * ORDER BY. This test pins that {@code id} (insertion order) is a deterministic tiebreaker.
 *
 * <p>Flyway is disabled and the schema is hand-rolled (see
 * {@code sql/transaction-repository-test-schema.sql}): the real migrations are
 * PostgreSQL-flavoured and cannot run on H2 -- per docs/conventions/testing.md. This is the
 * first {@code @DataJpaTest} in the suite.
 */
@DataJpaTest
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=none"
})
@Sql("classpath:sql/transaction-repository-test-schema.sql")
class TransactionRepositoryTest {

    @Autowired
    TransactionRepository transactionRepository;

    @Autowired
    TestEntityManager testEntityManager;

    @Test
    void sameDateRows_orderedByIdAfterDate_stableAcrossRepeatedCalls() {
        // A reference proxy (not a builder-constructed POJO): the seed account row was inserted
        // by the raw SQL script above, outside JPA, so Hibernate has never "seen" it as managed --
        // persisting a Transaction against a plain Account.builder().id(1L).build() would throw
        // TransientPropertyValueException. getReference() is the standard way to point a
        // @ManyToOne at a row known to exist without loading or re-persisting it.
        Account account = testEntityManager.getEntityManager().getReference(Account.class, 1L);
        LocalDate sameDate = LocalDate.of(2026, 7, 1);

        // Persist SELL first so it gets the lower id, despite BUY being the economically "earlier"
        // leg -- this is what proves the tiebreaker is insertion order (id), not transaction type.
        Transaction sell = transactionRepository.save(Transaction.builder()
            .account(account)
            .date(sameDate)
            .description("SELL")
            .amount(BigDecimal.ZERO)
            .txType(TransactionType.SELL)
            .ticker("AAPL")
            .quantity(new BigDecimal("10"))
            .build());

        Transaction buy = transactionRepository.save(Transaction.builder()
            .account(account)
            .date(sameDate)
            .description("BUY")
            .amount(BigDecimal.ZERO)
            .txType(TransactionType.BUY)
            .ticker("AAPL")
            .quantity(new BigDecimal("10"))
            .build());

        testEntityManager.flush();
        assertThat(sell.getId()).isLessThan(buy.getId());

        List<TransactionType> types = List.of(TransactionType.BUY, TransactionType.SELL);

        // Called twice: not a coincidence of one lucky plan, but the guaranteed order the
        // ORDER BY (date, id) clause produces every time.
        List<Transaction> first = transactionRepository.findByAccountIdAndTxTypeInOrderByDateAscIdAsc(1L, types);
        List<Transaction> second = transactionRepository.findByAccountIdAndTxTypeInOrderByDateAscIdAsc(1L, types);

        assertThat(first).extracting(Transaction::getId).containsExactly(sell.getId(), buy.getId());
        assertThat(second).extracting(Transaction::getId).containsExactly(sell.getId(), buy.getId());
    }

    // ─── ISIN repair queries ────────────────────────────────────────────────────
    // A ticker that is still a raw ISIN is one an earlier resolution failed to convert; it can
    // never be priced, so the whole position drops out of its account's value (GH issue #74).
    // What must not be swept up: rows a provider owns, and legitimate 12-character symbols.

    private Transaction row(long accountId, String ticker, boolean manual) {
        return transactionRepository.save(Transaction.builder()
            .account(testEntityManager.getEntityManager().getReference(Account.class, accountId))
            .date(LocalDate.of(2026, 7, 1))
            .description(ticker)
            .amount(BigDecimal.ZERO)
            .txType(TransactionType.BUY)
            .ticker(ticker)
            .isManual(manual)
            .quantity(BigDecimal.ONE)
            .build());
    }

    @Test
    void isinLengthTickers_areScopedToManualRowsOfManualAccounts() {
        row(1L, "IE000BI8OT95", true);
        row(1L, "MWRD.PA", true);           // already resolved
        row(1L, "123456789012", true);      // 12 chars but not an ISIN — the caller's shape check
        row(1L, "LU1681043599", false);     // synced row: its provider re-resolves on each sync
        row(2L, "IE00B4L5Y983", true);      // synced account: same reason

        testEntityManager.flush();
        testEntityManager.clear();

        assertThat(transactionRepository.findManualTransactionsWithIsinLengthTicker())
            .extracting(Transaction::getTicker)
            // "123456789012" comes back on purpose: LENGTH is all the database can check, and
            // OpenFigiIsinConverter.isIsin() is what rejects it in the caller.
            .containsExactlyInAnyOrder("IE000BI8OT95", "123456789012");
    }

    @Test
    void manualAccountIds_areFoundByTheTickersAboutToBeRewritten() {
        row(1L, "IE000BI8OT95", true);
        row(1L, "IE000BI8OT95", true);   // same instrument, two buys — one account, not two
        row(2L, "IE000BI8OT95", true);   // synced account: never recomputed from transactions

        testEntityManager.flush();
        testEntityManager.clear();

        assertThat(transactionRepository.findManualAccountIdsByTickerIn(List.of("IE000BI8OT95")))
            .containsExactly(1L);
        assertThat(transactionRepository.findManualAccountIdsByTickerIn(List.of("MWRD.PA"))).isEmpty();
    }

    // ─── File re-import lookups ─────────────────────────────────────────────────
    // Rows of a soft-deleted account are gone for the user, so a re-import must not see them:
    // they would block it or count as already imported.

    private void imported(long accountId, String externalId) {
        transactionRepository.save(Transaction.builder()
            .account(testEntityManager.getEntityManager().getReference(Account.class, accountId))
            .date(LocalDate.of(2026, 7, 1))
            .description(externalId)
            .amount(BigDecimal.ONE)
            .nativeCurrency("EUR")
            .externalId(externalId)
            .isManual(true)
            .build());
    }

    @Test
    void importLookupsIgnoreRowsOfASoftDeletedAccount() {
        imported(1L, "actual_live");
        imported(3L, "actual_deleted");
        testEntityManager.flush();
        testEntityManager.clear();

        assertThat(transactionRepository.findStoredExternalIds(1L, List.of("actual_live", "actual_deleted")))
            .extracting(TransactionRepository.StoredExternalId::getExternalId)
            .containsExactly("actual_live");
        assertThat(transactionRepository.findStoredExternalIdsInAccounts(1L, List.of(1L, 3L), "actual_"))
            .extracting(TransactionRepository.StoredExternalId::getExternalId)
            .containsExactly("actual_live");
    }
}
