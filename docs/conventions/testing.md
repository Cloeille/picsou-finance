# Convention: Testing

## Structure

There is **no** `unit/` or `integration/` directory split. All tests live flat under `src/test/java/com/picsou/`, mirroring the source package structure.

```
src/test/java/com/picsou/
├── service/      # 19 service test classes (GoalService, AccountService, FamilyService,
│                 #   MfaService, SecurityInsightService, HoldingCompute, …)
├── adapter/      # external-provider adapter tests
├── controller/   # controller tests (pure Mockito -- no MockMvc, see below)
├── config/       # security / config tests
└── export/       # GDPR export tests
```

## Unit tests

**Stack:** Mockito + AssertJ — no Spring context loaded.

```java
@ExtendWith(MockitoExtension.class)
class GoalServiceTest {

    @Mock GoalRepository goalRepository;
    @Mock AccountRepository accountRepository;
    @Mock BalanceSnapshotRepository snapshotRepository;
    @Mock AccountService accountService;
    @Mock GoalMonthOverrideRepository overrideRepository;

    @InjectMocks GoalService goalService;

    @Test
    void progressCalculation_onTrack() {
        // arrange
        Account account = Account.builder()
            .id(1L)
            .name("LEP")
            .type(AccountType.LEP)
            .currency("EUR")
            .currentBalance(new BigDecimal("5000"))
            .color("#6366f1")
            .build();

        when(accountService.toResponse(account)).thenReturn(/* ... */);

        // act
        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        // assert
        assertThat(progress.currentTotal()).isEqualByComparingTo("5000");
    }
}
```

### Patterns

- **`@ExtendWith(MockitoExtension.class)`** with `@Mock` and `@InjectMocks` — no `MockitoAnnotations.openMocks()`.
- **Lombok builders for test data** — `Account.builder()`, `Goal.builder()` etc. No test fixtures or mother objects.
- **AssertJ** for all assertions (`assertThat`, `isEqualByComparingTo`). No JUnit `assertEquals`.
- **No Spring context** in unit tests — pure Mockito mocking.

### Naming

- **Class:** `[Class]Test` (e.g., `GoalServiceTest`).
- **Method:** descriptive, underscore-separated, e.g., `progressCalculation_onTrack`. Not strictly `should_xxx_when_yyy`.
- **One test per behavior** — a test may have multiple assertions on the same logical result, but does not test multiple scenarios.

## Integration tests

When JPA is needed, use `@DataJpaTest` with **H2 in-memory** — the default, and enough
for repository queries and entity mapping.

```bash
# H2 auto-configures; no external database needed
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test -Dtest=SomeRepoTest
```

H2 is on the test classpath via `spring-boot-starter-test`.

### Testcontainers — only for real-PostgreSQL behaviour

H2 cannot run the Flyway chain: the migrations are PostgreSQL-flavoured
(`CREATE TYPE ... AS ENUM`, `split_part()`, partial indexes). So a **data-mutating
migration** — one that rewrites existing rows rather than only adding structure — is
verified against real PostgreSQL via Testcontainers.

Reach for it *only* for that. Everything else stays on Mockito or H2; a container costs
seconds of wall clock per class.

Pattern (see `WalletEvmMigrationTest`): no Spring context — drive Flyway and
JDBC directly, migrating in two steps so the seeded data is what the migration under test
actually operates on.

```java
@Testcontainers
class V99SomeMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        migrateTo("98");   // the schema a deployed instance is already on
        // ... seed rows representing real pre-migration data, incl. negative controls ...
        migrateTo("99");   // apply the migration under test, alone
    }
}
```

Assert both directions: the rows that must change, **and** the rows that must not.

Three things the class must do, all learned the hard way:

- **Gate on Docker** with `@EnabledIf("dockerAvailable")` (backed by
  `DockerClientFactory.instance().isDockerAvailable()`), so a machine without a Docker
  socket skips this class instead of failing the whole suite. It must be a JUnit
  `ExecutionCondition` — a `@BeforeAll` assumption runs *after* the Testcontainers
  extension has already tried to start the container.
- **Pin `api.version=1.44`, in two places.** Otherwise docker-java negotiates down to API
  1.32, which Engine ≥ 28 refuses — and that surfaces as the *same* "Could not find a valid
  Docker environment" error a Docker-less machine gives, so the guard above would quietly
  skip the test on a perfectly capable host. `pom.xml` sets it via surefire
  `systemPropertyVariables` so it applies process-wide before *any* Testcontainers class
  initializes; the test class also sets it in a static block so IDE and failsafe runs
  (which never read surefire config) work too. A classpath `testcontainers.properties` is
  **not** honored for this — tested. Sets the floor at Docker Engine ≥ 25.0.

- **Make CI refuse to skip.** A skip is invisible in a green build, so `ci.yml` sets
  `PICSOU_REQUIRE_DOCKER_TESTS=true` and `dockerAvailable()` throws instead of returning
  false when it is set. Without this, any Docker drift on the runner silently converts the
  PostgreSQL coverage of data-mutating migrations into a permanently green no-op.

The three interlock: the guard alone turns a config problem into a silent pass, and the
API pin alone makes Docker-less machines fail the whole suite.

Also order any test that mutates the shared seeded dataset **last**
(`@TestMethodOrder` + `@Order`) — JUnit's default method order is deliberately
unspecified, so otherwise the other tests may assert against post-mutation state.

## Frontend tests

- **Unit tests:** Vitest (`vitest`) with `@testing-library/react` and `jsdom`.
- **E2E tests:** Playwright (`@playwright/test`) against demo mode. CI runs them
  in the `e2e` job (`bun run test:e2e` after `playwright install --with-deps chromium`).
- Run commands:
  ```bash
  bunx vitest run         # unit tests
  bun run test:e2e        # E2E tests (starts Vite with VITE_DEMO_MODE=true)
  ```

## Running tests

Backend Maven runs enforce Java 21 during `validate`; set `JAVA_HOME` to a JDK 21
installation before running backend tests locally.

```bash
# Backend — all tests
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test

# Backend — single test class
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test -Dtest=GoalServiceTest

# Backend — single test method
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test -Dtest=GoalServiceTest#progressCalculation_onTrack
```

## Current coverage

The suite has **627 backend tests** (service, adapter, controller, config, export, migration). Service-layer unit tests dominate. When adding coverage, prioritize:

1. **Service-layer unit tests** — mock dependencies, test business logic.
2. **Repository custom queries** — `@DataJpaTest` for non-trivial JPQL.
3. **Controller tests** — pure Mockito (`@Mock` + `@InjectMocks`), calling controller methods directly and asserting on the returned DTO. There is **no MockMvc anywhere in `src/test`**; controllers in this project are thin enough that a Spring context buys nothing. Assert that the member id comes from `UserContext`, which is the scoping contract at that layer.
4. **Data-mutating migrations** — Testcontainers, per the section above.

## Don'ts

- **Never load Spring context in unit tests** — pure Mockito. Use `@DataJpaTest` only for JPA integration tests.
- **Never use `MockitoAnnotations.openMocks()`** — use `@ExtendWith(MockitoExtension.class)`.
- **Never use JUnit `assertEquals`** — always AssertJ (`assertThat`).
- **Never use `@Autowired` in tests** unless it's a `@DataJpaTest` integration test.
- **Never create test fixtures or "mother objects"** — use Lombok builders directly.
