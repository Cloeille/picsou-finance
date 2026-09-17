package com.picsou.service.budget;

import com.picsou.model.BudgetSettings;
import com.picsou.model.Category;
import com.picsou.model.FamilyMember;
import com.picsou.repository.BudgetSettingsRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the budget module's <em>seed-on-read</em> invariant against a <b>real PostgreSQL</b>.
 *
 * <p>Several budget services are annotated {@code @Transactional(readOnly = true)} at the class
 * level but lazily seed per-member defaults on the first read ({@link CategoryService#ensureSeeded},
 * {@link BudgetSettingsService#getOrCreate}). Those seeds must run in a <em>writable</em>
 * transaction; they achieve this with {@code Propagation.REQUIRES_NEW}, which suspends the caller's
 * read-only transaction and starts a fresh writable one. If they used plain {@code REQUIRED} they
 * would join — and inherit the read-only flag of — the caller, and PostgreSQL would reject the
 * INSERT with SQLSTATE {@code 25006} ("cannot execute INSERT in a read-only transaction").
 *
 * <p>H2 — the in-memory database an earlier testing convention favoured for JPA tests —
 * <b>silently tolerates</b> writes in a read-only transaction, so this whole class of bug is
 * invisible on it; only a real PostgreSQL surfaces it. Hence this test deliberately stands up a
 * Postgres 16 container via Testcontainers (the project's only container-backed test).
 * {@code disabledWithoutDocker = true} makes it self-skip on machines/CI without a Docker daemon
 * rather than fail.
 *
 * <p>Note there is intentionally <b>no class-level {@code @Transactional}</b>: the usual
 * test-rollback wrapper would open one outer transaction around the whole test method, collapsing
 * the very read-only → REQUIRES_NEW boundary under test. Each service call manages its own
 * transaction; isolation between tests comes from a fresh {@link FamilyMember} (and therefore a
 * fresh {@code memberId} scope) per test.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class BudgetSeedWriteOnReadPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * The full application context refuses to boot without two secrets that have no default in
     * {@code application.yml}: {@code JwtUtil} demands a signing key of at least 32 characters, and
     * {@code CryptoEncryption} demands a non-blank Base64 AES key. Supply deterministic test values
     * (the all-zero 32-byte key is a valid AES-256 key for round-tripping in tests).
     */
    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "test-jwt-secret-test-jwt-secret-0123456789");
        registry.add("app.crypto.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    /** The number of default categories {@link CategoryService} seeds for a brand-new member. */
    private static final int DEFAULT_CATEGORY_COUNT = 18;

    @Autowired CategoryService categoryService;
    @Autowired BudgetSettingsService budgetSettingsService;
    @Autowired CategorizationService categorizationService;
    @Autowired CategoryRepository categoryRepository;
    @Autowired BudgetSettingsRepository budgetSettingsRepository;
    @Autowired FamilyMemberRepository familyMemberRepository;
    @Autowired PlatformTransactionManager txManager;

    private Long memberId;

    @BeforeEach
    void seedMember() {
        FamilyMember member = familyMemberRepository.save(
            FamilyMember.builder().displayName("IT Member").build());
        memberId = member.getId();
    }

    /**
     * Sanity check that this harness genuinely enforces read-only at the database — i.e. the test
     * is not vacuous the way it would be on H2. A plain repository {@code save()} joins the outer
     * read-only transaction (propagation REQUIRED) and, because the entity uses {@code IDENTITY}
     * generation, fires the INSERT immediately — which PostgreSQL rejects (SQLSTATE 25006).
     */
    @Test
    void readOnlyTransactionRejectsDirectWrites() {
        TransactionTemplate readOnly = newReadOnlyTemplate();

        assertThatThrownBy(() -> readOnly.executeWithoutResult(status ->
            budgetSettingsRepository.save(BudgetSettings.builder()
                .member(familyMemberRepository.getReferenceById(memberId))
                .cycleStartDay(1)
                .build())))
            .isInstanceOf(Exception.class);

        assertThat(budgetSettingsRepository.findByMemberId(memberId)).isEmpty();
    }

    /**
     * The core regression guard: {@link CategorizationService#categoriesBySlug} is reached from
     * read-only callers, yet it must seed the member's default categories on first access. The
     * seed escapes the read-only outer transaction via {@code REQUIRES_NEW}; were it {@code REQUIRED}
     * this call would throw SQLSTATE 25006. We invoke it from inside an explicit read-only
     * transaction so the regression is caught even if the class-level annotation is ever removed.
     */
    @Test
    void categoriesBySlugSeedsDefaultsFromWithinReadOnlyTransaction() {
        TransactionTemplate readOnly = newReadOnlyTemplate();

        Map<String, Category> bySlug = readOnly.execute(status ->
            categorizationService.categoriesBySlug(memberId));

        assertThat(bySlug).hasSize(DEFAULT_CATEGORY_COUNT);
        assertThat(bySlug).containsKeys("courses", "salaire", "epargne");
        // The seed actually committed (REQUIRES_NEW), visible to a subsequent independent read.
        assertThat(categoryRepository.findAllByMemberIdOrderBySortOrderAscIdAsc(memberId))
            .hasSize(DEFAULT_CATEGORY_COUNT);
    }

    /**
     * {@link CategoryService#findAll} is itself {@code @Transactional} (writable) and seeds via an
     * internal call. This is the writable-path baseline — the same seed that must <em>also</em>
     * survive the read-only path above.
     */
    @Test
    void findAllSeedsDefaultsOnFirstRead() {
        assertThat(categoryService.findAll(memberId)).hasSize(DEFAULT_CATEGORY_COUNT);
        // Idempotent: a second read does not duplicate the defaults.
        assertThat(categoryService.findAll(memberId)).hasSize(DEFAULT_CATEGORY_COUNT);
    }

    /**
     * {@link BudgetSettingsService#get} lazily inserts the default settings row (cycleStartDay = 1)
     * via {@code getOrCreate}'s {@code REQUIRES_NEW}. Asserts the row is created and persisted.
     */
    @Test
    void getSettingsInsertsDefaultRow() {
        assertThat(budgetSettingsRepository.findByMemberId(memberId)).isEmpty();

        var settings = budgetSettingsService.get(memberId);

        assertThat(settings.cycleStartDay()).isEqualTo(1);
        assertThat(budgetSettingsRepository.findByMemberId(memberId))
            .get()
            .satisfies(row -> {
                assertThat(row.getCycleStartDay()).isEqualTo(1);
                assertThat(row.isLogoFetchEnabled()).isFalse();
            });
    }

    /**
     * {@link CategorizationService#recategorizeUncategorized} runs the full pipeline (which loads
     * the context and therefore seeds categories) over a member with no transactions. It must
     * complete without error and seed the defaults as a side effect — proving the bulk entry point
     * is safe on a brand-new member.
     */
    @Test
    void recategorizeSeedsAndSucceedsOnEmptyMember() {
        int assigned = categorizationService.recategorizeUncategorized(memberId);

        assertThat(assigned).isZero();
        assertThat(categoryRepository.findAllByMemberIdOrderBySortOrderAscIdAsc(memberId))
            .hasSize(DEFAULT_CATEGORY_COUNT);
    }

    private TransactionTemplate newReadOnlyTemplate() {
        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setReadOnly(true);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return template;
    }
}
