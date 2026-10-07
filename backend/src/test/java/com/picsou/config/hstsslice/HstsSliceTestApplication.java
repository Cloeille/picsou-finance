package com.picsou.config.hstsslice;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Minimal {@code @SpringBootConfiguration} for the web slice in this package.
 *
 * <p>{@code @WebMvcTest} searches upward from the test's package for a
 * {@code @SpringBootConfiguration} and would otherwise find {@link com.picsou.PicsouApplication},
 * which carries {@code @EnableJpaAuditing}. A web slice loads no entities, so JPA auditing fails
 * with "JPA metamodel must not be empty". Declaring this class in the same package as the test
 * makes it win the search, keeping the slice free of persistence concerns. No need to also
 * exclude {@code DataSourceAutoConfiguration}/{@code HibernateJpaAutoConfiguration} here —
 * {@code @WebMvcTest} already restricts auto-configuration to the web layer on its own.
 *
 * <p>Do NOT add an explicit {@code @EnableAutoConfiguration(exclude = ...)} on this class. Doing
 * so once caused every unrelated {@code @SpringBootTest} in the suite (a plain, unmocked context —
 * {@code BudgetSeedWriteOnReadPostgresTest}, the OAuth2/MCP authorization-server tests, etc.) to
 * fail with "No qualifying bean of type AppSettingRepository", because the excluded
 * autoconfigurations leaked into those other contexts within the same Surefire fork. Root cause
 * not fully understood (suspected Spring Test context-cache/condition-report state shared across
 * {@code MergedContextConfiguration} keys in the same JVM) — the fix was simply to drop the
 * redundant excludes, not to chase the leak further.
 *
 * <p>Lives in its own {@code hstsslice} sub-package, not directly in {@code com.picsou.config}, as
 * a defensive measure: several other tests in that package ({@code AuthorizationServerConfigTest}
 * and siblings) are plain {@code @SpringBootTest} with no explicit {@code classes=}, so they too
 * resolve their configuration by searching upward from their package.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class HstsSliceTestApplication {
}
