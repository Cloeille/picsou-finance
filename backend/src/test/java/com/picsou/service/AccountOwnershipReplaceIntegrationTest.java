package com.picsou.service;

import com.picsou.dto.OwnershipRequest;
import com.picsou.dto.OwnershipResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pins {@link AccountOwnershipService#replace} against a real persistence context.
 *
 * <p><b>Why this exists.</b> {@code AccountOwnershipServiceTest} mocks every repository, so its
 * {@code Account} is a plain builder object whose {@code member} is a fully-populated
 * {@code FamilyMember}. Production hands the service something quite different: an entity loaded
 * by {@code AccountAccessResolver} whose {@code member} is an uninitialised lazy proxy — and
 * {@code deleteAllForAccount} is declared {@code clearAutomatically = true}, which detaches it
 * before the response is built. The mock can no more show that than it could show a unique-key
 * violation; only a real ORM can (same lesson as {@code CryptoExchangePositionRepositoryTest}).
 *
 * <p>Runs on PostgreSQL rather than H2 because the service loads a whole {@code Account}, whose
 * schema is Flyway's and PostgreSQL-flavoured.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@EnabledIf("dockerAvailable")
@Import({AccountOwnershipService.class, AccountAccessResolver.class})
class AccountOwnershipReplaceIntegrationTest {

    static {
        // Same reason as SchemaMappingValidationTest: docker-java negotiates down to an API
        // version modern engines refuse, which is indistinguishable from "no Docker".
        System.setProperty("api.version", System.getProperty("api.version", "1.44"));
    }

    @Container
    @SuppressWarnings("resource") // closed by the Testcontainers JUnit extension
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static boolean dockerAvailable() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();
        if (!available && Boolean.parseBoolean(System.getenv("PICSOU_REQUIRE_DOCKER_TESTS"))) {
            throw new IllegalStateException(
                "PICSOU_REQUIRE_DOCKER_TESTS is set but no Docker environment was found.");
        }
        return available;
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.out-of-order", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired AccountOwnershipService service;
    @Autowired TestEntityManager em;

    private FamilyMember member(String displayName, String avatarColor) {
        return em.persist(FamilyMember.builder()
            .displayName(displayName)
            .avatarColor(avatarColor)
            .build());
    }

    /**
     * Persists a property and hands back its id, then empties the persistence context.
     *
     * <p>The clear is the whole point: without it the caller keeps the very instance it built,
     * {@code member} still pointing at a real {@code FamilyMember}, and the service never sees
     * the lazy proxy production gives it.
     */
    private Long propertyOwnedBy(FamilyMember owner) {
        Account account = em.persist(Account.builder()
            .member(owner)
            .name("Résidence principale")
            .type(AccountType.REAL_ESTATE)
            .currency("EUR")
            .currentBalance(new BigDecimal("412000"))
            .isManual(true)
            .build());
        em.flush();
        em.clear();
        return account.getId();
    }

    @Test
    void replace_clearsTheSplitAndReportsTheOwnerAtFullShare() {
        FamilyMember alice = member("Alice", "#6366f1");
        Long accountId = propertyOwnedBy(alice);

        // Clearing the split is the path that broke: deleteAllForAccount detaches the account,
        // and the response then has to read the owner's name and colour off its lazy proxy.
        OwnershipResponse response = service.replace(
            accountId, alice.getId(), new OwnershipRequest(List.of()));

        assertThat(response.shares()).singleElement().satisfies(share -> {
            assertThat(share.memberId()).isEqualTo(alice.getId());
            assertThat(share.displayName()).isEqualTo("Alice");
            assertThat(share.avatarColor()).isEqualTo("#6366f1");
            assertThat(share.sharePercent()).isEqualByComparingTo("100");
            assertThat(share.isOwner()).isTrue();
        });
        assertThat(response.totalAssigned()).isEqualByComparingTo("100");
        assertThat(response.unassigned()).isEqualByComparingTo("0");
    }

    @Test
    void replace_writesANewSplitAcrossMembers() {
        FamilyMember alice = member("Alice", "#6366f1");
        FamilyMember bob = member("Bob", "#22c55e");
        Long accountId = propertyOwnedBy(alice);

        OwnershipResponse response = service.replace(accountId, alice.getId(),
            new OwnershipRequest(List.of(
                new OwnershipRequest.Share(alice.getId(), new BigDecimal("60")),
                new OwnershipRequest.Share(bob.getId(), new BigDecimal("40")))));

        // The rows reference an account the delete detached, so this also covers writing through
        // that detached reference rather than only reading from it.
        assertThat(response.shares())
            .extracting(OwnershipResponse.MemberShare::displayName,
                        OwnershipResponse.MemberShare::isOwner)
            .containsExactlyInAnyOrder(
                org.assertj.core.api.Assertions.tuple("Alice", true),
                org.assertj.core.api.Assertions.tuple("Bob", false));
        assertThat(response.totalAssigned()).isEqualByComparingTo("100");
    }

    @Test
    void replace_overwritesAnExistingSplitWithoutTrippingTheUniqueKey() {
        FamilyMember alice = member("Alice", "#6366f1");
        FamilyMember bob = member("Bob", "#22c55e");
        Long accountId = propertyOwnedBy(alice);

        service.replace(accountId, alice.getId(), new OwnershipRequest(List.of(
            new OwnershipRequest.Share(alice.getId(), new BigDecimal("50")),
            new OwnershipRequest.Share(bob.getId(), new BigDecimal("50")))));

        // Same (account, member) pairs a second time: the delete has to land before the inserts
        // or uk_account_ownership_account_member rejects them.
        assertThatCode(() -> service.replace(accountId, alice.getId(), new OwnershipRequest(List.of(
            new OwnershipRequest.Share(alice.getId(), new BigDecimal("70")),
            new OwnershipRequest.Share(bob.getId(), new BigDecimal("30"))))))
            .doesNotThrowAnyException();
    }

    @Test
    void replace_thenClear_restoresTheOwnerAtFullShare() {
        FamilyMember alice = member("Alice", "#6366f1");
        FamilyMember bob = member("Bob", "#22c55e");
        Long accountId = propertyOwnedBy(alice);

        service.replace(accountId, alice.getId(), new OwnershipRequest(List.of(
            new OwnershipRequest.Share(alice.getId(), new BigDecimal("50")),
            new OwnershipRequest.Share(bob.getId(), new BigDecimal("50")))));

        OwnershipResponse cleared = service.replace(
            accountId, alice.getId(), new OwnershipRequest(List.of()));

        assertThat(cleared.shares()).singleElement()
            .satisfies(share -> assertThat(share.displayName()).isEqualTo("Alice"));
        assertThat(cleared.unassigned()).isEqualByComparingTo("0");
    }
}
