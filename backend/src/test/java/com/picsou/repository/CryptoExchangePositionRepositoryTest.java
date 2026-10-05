package com.picsou.repository;

import com.picsou.model.Account;
import com.picsou.model.CryptoExchangePosition;
import com.picsou.port.CryptoExchangePort.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Pins the delete-then-reinsert rewrite {@code CryptoExchangeSyncService.replacePositions} performs
 * on every sync, which needs a real persistence context to be meaningful at all.
 *
 * <p>The first shipped version used a derived {@code deleteByAccountId}. That only <em>queues</em>
 * removals, and Hibernate's action queue flushes inserts before deletes — so re-inserting the same
 * {@code (account, product, ticker)} in the same transaction hit the rows still in the table and
 * every sync after the first died on the account/product/ticker unique key. The service test
 * caught nothing because its repository is a Mockito mock: no ORM, no constraint, no ordering.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=none",
    // Its own named database, not the auto-configured one every @DataJpaTest shares. Both this
    // class and TransactionRepositoryTest hand-roll an `account` table, and the other script has
    // no DROP — so whichever ran second failed on "table ACCOUNT already exists". That surfaced
    // only in CI, where surefire happened to order them the other way round than locally.
    "spring.datasource.url=jdbc:h2:mem:crypto-exchange-position;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password="
})
@Sql("classpath:sql/crypto-exchange-position-test-schema.sql")
class CryptoExchangePositionRepositoryTest {

    @Autowired
    CryptoExchangePositionRepository positionRepository;

    @Autowired
    TestEntityManager testEntityManager;

    private CryptoExchangePosition position(Account account, Product product, String ticker, String quantity) {
        return CryptoExchangePosition.builder()
            .account(account)
            .product(product)
            .ticker(ticker)
            .quantity(new BigDecimal(quantity))
            .build();
    }

    @Test
    void replacingAnAccountsPositionsInOneTransactionDoesNotTripTheUniqueConstraint() {
        // getReference(): the seed account row was inserted by raw SQL, outside JPA.
        Account account = testEntityManager.getEntityManager().getReference(Account.class, 1L);
        positionRepository.saveAll(List.of(
            position(account, Product.SPOT, "ETH", "0.5"),
            position(account, Product.STAKING, "ATOM", "33.154")));
        positionRepository.flush();

        // Exactly what a sync does: wipe the breakdown, then write the fresh one — same keys.
        assertThatCode(() -> {
            positionRepository.deleteAllForAccount(1L);
            positionRepository.saveAll(List.of(
                position(account, Product.SPOT, "ETH", "0.75"),
                position(account, Product.STAKING, "ATOM", "34.000")));
            positionRepository.flush();
        }).doesNotThrowAnyException();

        // The exact quantities, not "anything but the old one": the expected values are known, and
        // "not 0.5" would accept a rewrite that stored the wrong number entirely.
        assertThat(positionRepository.findByAccountIdOrderByProductAscTickerAsc(1L))
            .extracting(CryptoExchangePosition::getProduct, CryptoExchangePosition::getTicker,
                position -> position.getQuantity().stripTrailingZeros())
            .containsExactly(
                tuple(Product.SPOT, "ETH", new BigDecimal("0.75")),
                tuple(Product.STAKING, "ATOM", new BigDecimal("34")));
    }

    @Test
    void deletingOnesAccountPositionsLeavesAnotherAccountsAlone() {
        // The `WHERE p.account.id = :accountId` of the bulk delete, which no other test exercises
        // as a filter: every one of them holds a single account, so a delete that ignored its
        // parameter would pass them all and quietly wipe every other account on each sync.
        Account first = testEntityManager.getEntityManager().getReference(Account.class, 1L);
        Account second = testEntityManager.getEntityManager().getReference(Account.class, 2L);
        positionRepository.saveAll(List.of(
            position(first, Product.SPOT, "ETH", "0.5"),
            position(second, Product.SPOT, "ETH", "1.25")));
        positionRepository.flush();

        positionRepository.deleteAllForAccount(1L);
        positionRepository.flush();

        assertThat(positionRepository.findByAccountIdOrderByProductAscTickerAsc(1L)).isEmpty();
        assertThat(positionRepository.findByAccountIdOrderByProductAscTickerAsc(2L))
            .extracting(CryptoExchangePosition::getTicker)
            .containsExactly("ETH");
    }

    @Test
    void aProductNoLongerHeldDisappears() {
        Account account = testEntityManager.getEntityManager().getReference(Account.class, 1L);
        positionRepository.saveAll(List.of(
            position(account, Product.SPOT, "ETH", "0.5"),
            position(account, Product.LENDING, "USDT", "75")));
        positionRepository.flush();

        // The user closed their lending contract: the rewrite must not leave the line behind.
        positionRepository.deleteAllForAccount(1L);
        positionRepository.saveAll(List.of(position(account, Product.SPOT, "ETH", "0.5")));
        positionRepository.flush();

        assertThat(positionRepository.findByAccountIdOrderByProductAscTickerAsc(1L))
            .extracting(CryptoExchangePosition::getProduct)
            .containsExactly(Product.SPOT);
    }
}
