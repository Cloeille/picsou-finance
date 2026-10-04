package com.picsou.mcp.tools;

import com.picsou.dto.CashflowPeriod;
import com.picsou.dto.RecurringActivityResponse;
import com.picsou.dto.RecurringActivityType;
import com.picsou.dto.RecurringOccurrenceResponse;
import com.picsou.dto.RecurringSeriesResponse;
import com.picsou.exception.MissingScopeException;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.mcp.ScopeEnforcementAspect;
import com.picsou.mcp.Scopes;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.RecurringCadence;
import com.picsou.model.RecurringSeries;
import com.picsou.model.RecurringStatus;
import com.picsou.model.RuleMatchType;
import com.picsou.model.Transaction;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BudgetSettingsRepository;
import com.picsou.repository.CategorizationRuleRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.RecurringSeriesRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.UserContext;
import com.picsou.service.budget.BudgetService;
import com.picsou.service.budget.BudgetSettingsService;
import com.picsou.service.budget.CashflowFlowService;
import com.picsou.service.budget.CashflowService;
import com.picsou.service.budget.CategorizationService;
import com.picsou.service.budget.CategoryService;
import com.picsou.service.budget.MerchantKnowledgeBase;
import com.picsou.service.budget.RecurringDetectionService;
import com.picsou.service.budget.RecurringSeriesService;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The recurring-triage, spending/cashflow and rule-preview tools driven end to end: tool → the real
 * budget service → mocked repositories. Unlike {@link BudgetToolsTest}, which checks delegation, these
 * assert the state the REST endpoints guarantee (undo, ignore-not-resurrected, member scoping,
 * bean validation, dry run) and that {@link ScopeEnforcementAspect} actually guards each tool.
 */
@ExtendWith(MockitoExtension.class)
class BudgetToolsRecurringTriageTest {

    private static final long MID = 7L;
    private static final long FOREIGN_ID = 99L;
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Mock RecurringSeriesRepository seriesRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock CategorizationRuleRepository ruleRepository;
    @Mock MerchantKnowledgeBase knowledgeBase;
    @Mock BudgetSettingsRepository settingsRepository;
    @Mock BudgetSettingsService budgetSettingsService;
    @Mock CategoryService categoryService;
    @Mock BudgetService budgetService;
    @Mock CashflowService cashflowService;
    @Mock UserContext userContext;

    BudgetTools tools;

    @BeforeEach
    void setUp() {
        lenient().when(userContext.currentMemberId()).thenReturn(MID);
        lenient().when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MID)).thenReturn(List.of());
        tools = new BudgetTools(
            categoryService,
            new CategorizationService(ruleRepository, categoryRepository, transactionRepository,
                familyMemberRepository, knowledgeBase, categoryService, settingsRepository),
            budgetService,
            new RecurringSeriesService(seriesRepository, categoryRepository, familyMemberRepository, accountRepository),
            new RecurringDetectionService(transactionRepository, seriesRepository, familyMemberRepository),
            cashflowService,
            new CashflowFlowService(transactionRepository, budgetSettingsService, categoryRepository),
            transactionRepository,
            userContext,
            VALIDATOR);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static RecurringSeries.RecurringSeriesBuilder series(long id) {
        return RecurringSeries.builder()
            .id(id).label("Netflix").expectedAmount(new BigDecimal("-12.99"))
            .cadence(RecurringCadence.MONTHLY).status(RecurringStatus.SUGGESTED);
    }

    private void stored(RecurringSeries s) {
        when(seriesRepository.findByIdAndMemberId(s.getId(), MID)).thenReturn(Optional.of(s));
    }

    private void echoSave() {
        when(seriesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Transaction netflixOn(LocalDate date) {
        return Transaction.builder().id(date.toEpochDay()).date(date)
            .amount(new BigDecimal("-12.99")).counterparty("Netflix").description("Netflix").build();
    }

    // ─── Triage ─────────────────────────────────────────────────────────────

    @Test
    void confirm_marksTheSeriesConfirmedAndIsIdempotent() {
        RecurringSeries s = series(1L).build();
        stored(s);
        echoSave();

        tools.confirmRecurringSeries(1L);
        RecurringSeriesResponse out = tools.confirmRecurringSeries(1L);

        assertThat(out.id()).isEqualTo(1L);
        assertThat(out.status()).isEqualTo(RecurringStatus.CONFIRMED);
    }

    @Test
    void ignore_keepsTheSeriesAndDetectionDoesNotResurrectIt() {
        RecurringSeries s = series(1L).build();
        stored(s);
        echoSave();

        RecurringSeriesResponse out = tools.ignoreRecurringSeries(1L);

        assertThat(out.status()).isEqualTo(RecurringStatus.IGNORED);
        verify(seriesRepository, never()).delete(any());

        when(transactionRepository.findByMemberIdAndDateBetween(eq(MID), any(), any())).thenReturn(List.of(
            netflixOn(LocalDate.now().minusDays(65)), netflixOn(LocalDate.now().minusDays(35)),
            netflixOn(LocalDate.now().minusDays(5))));
        when(seriesRepository.findByMemberIdAndLabelIgnoreCase(MID, "Netflix")).thenReturn(Optional.of(s));

        assertThat(tools.detectRecurringSeries()).isEqualTo("Detected or refreshed 0 recurring series");
        assertThat(s.getStatus()).isEqualTo(RecurringStatus.IGNORED);
    }

    @Test
    void undo_acknowledgesAPriceStepKeepingTheNewAmount() {
        RecurringSeries s = series(1L).status(RecurringStatus.CONFIRMED)
            .expectedAmount(new BigDecimal("-13.99")).previousAmount(new BigDecimal("-12.99"))
            .priceChangedAt(LocalDate.now().minusDays(2)).build();
        stored(s);
        echoSave();

        RecurringSeriesResponse out = tools.undoRecurringSeriesChange(1L);

        assertThat(out.status()).isEqualTo(RecurringStatus.CONFIRMED);
        assertThat(out.expectedAmount()).isEqualByComparingTo("-13.99");
        assertThat(s.getPreviousAmount()).isNull();
        assertThat(s.getPriceChangedAt()).isNull();
    }

    @Test
    void undo_rejectsASilentAutoConfirmByIgnoringTheSeries() {
        RecurringSeries s = series(1L).status(RecurringStatus.CONFIRMED).autoConfirmed(true)
            .lastSeenDate(LocalDate.now().minusDays(4)).build();
        stored(s);
        echoSave();

        RecurringSeriesResponse out = tools.undoRecurringSeriesChange(1L);

        assertThat(out.status()).isEqualTo(RecurringStatus.IGNORED);
        assertThat(s.isAutoConfirmed()).isFalse();
    }

    @Test
    void create_declaresAConfirmedSeriesForTheCurrentMember() {
        when(seriesRepository.save(any())).thenAnswer(inv -> {
            RecurringSeries s = inv.getArgument(0);
            s.setId(42L);
            return s;
        });

        RecurringSeriesResponse out = tools.createRecurringSeries(
            "  Gym  ", new BigDecimal("-30"), RecurringCadence.MONTHLY, null, null, null);

        assertThat(out.id()).isEqualTo(42L);
        assertThat(out.label()).isEqualTo("Gym");
        assertThat(out.status()).isEqualTo(RecurringStatus.CONFIRMED);
        verify(familyMemberRepository).getReferenceById(MID);
    }

    @Test
    void update_replacesTheEditableFields() {
        RecurringSeries s = series(1L).status(RecurringStatus.CONFIRMED).counterparty("NETFLIX.COM").build();
        stored(s);
        echoSave();

        RecurringSeriesResponse out = tools.updateRecurringSeries(
            1L, "Netflix Premium", new BigDecimal("-17.99"), RecurringCadence.MONTHLY, null,
            LocalDate.of(2026, 11, 3), null);

        assertThat(out.label()).isEqualTo("Netflix Premium");
        assertThat(out.expectedAmount()).isEqualByComparingTo("-17.99");
        assertThat(out.nextDueDate()).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(out.counterparty()).isNull();
        assertThat(out.status()).isEqualTo(RecurringStatus.CONFIRMED);
    }

    @Test
    void delete_removesTheSeries() {
        RecurringSeries s = series(1L).build();
        stored(s);

        assertThat(tools.deleteRecurringSeries(1L)).isEqualTo("Deleted recurring series 1");
        verify(seriesRepository).delete(s);
    }

    @Test
    void detect_upsertsNewSuggestionsAndReportsTheCount() {
        when(transactionRepository.findByMemberIdAndDateBetween(eq(MID), any(), any())).thenReturn(List.of(
            netflixOn(LocalDate.now().minusDays(65)), netflixOn(LocalDate.now().minusDays(35)),
            netflixOn(LocalDate.now().minusDays(5))));
        when(seriesRepository.findByMemberIdAndLabelIgnoreCase(MID, "Netflix")).thenReturn(Optional.empty());
        when(seriesRepository.save(any())).thenAnswer(inv -> {
            RecurringSeries s = inv.getArgument(0);
            s.setId(5L);
            return s;
        });

        assertThat(tools.detectRecurringSeries()).isEqualTo("Detected or refreshed 1 recurring series");
        verify(transactionRepository).findByMemberIdAndDateBetween(MID, LocalDate.now().minusDays(400), LocalDate.now());
    }

    // ─── Member scoping ─────────────────────────────────────────────────────

    static Stream<Arguments> seriesWriteTools() {
        return Stream.of(
            tool("confirm_recurring_series", t -> t.confirmRecurringSeries(FOREIGN_ID)),
            tool("ignore_recurring_series", t -> t.ignoreRecurringSeries(FOREIGN_ID)),
            tool("undo_recurring_series_change", t -> t.undoRecurringSeriesChange(FOREIGN_ID)),
            tool("update_recurring_series", t -> t.updateRecurringSeries(
                FOREIGN_ID, "X", BigDecimal.ONE, RecurringCadence.MONTHLY, null, null, null)),
            tool("delete_recurring_series", t -> t.deleteRecurringSeries(FOREIGN_ID)));
    }

    @ParameterizedTest
    @MethodSource("seriesWriteTools")
    void anotherMembersSeries_isNotFoundAndNeverModified(Consumer<BudgetTools> call) {
        when(seriesRepository.findByIdAndMemberId(FOREIGN_ID, MID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> call.accept(tools)).isInstanceOf(ResourceNotFoundException.class);
        verify(seriesRepository, never()).save(any());
        verify(seriesRepository, never()).delete(any());
    }

    @Test
    void createWithAnotherMembersCategory_isNotFoundAndWritesNothing() {
        when(categoryRepository.findByIdAndMemberId(FOREIGN_ID, MID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tools.createRecurringSeries(
            "Gym", new BigDecimal("-30"), RecurringCadence.MONTHLY, null, null, FOREIGN_ID))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(seriesRepository, never()).save(any());
    }

    @Test
    void spendingDetailOfAnotherMembersCategory_isNotFound() {
        when(categoryRepository.findByIdAndMemberId(FOREIGN_ID, MID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tools.getSpendingCategoryDetail(FOREIGN_ID, CashflowPeriod.YTD, null))
            .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(transactionRepository);
    }

    // ─── Validation ─────────────────────────────────────────────────────────

    @Test
    void create_rejectsABlankLabelAndWritesNothing() {
        assertThatThrownBy(() -> tools.createRecurringSeries(
            " ", new BigDecimal("-30"), RecurringCadence.MONTHLY, null, null, null))
            .isInstanceOf(ConstraintViolationException.class)
            .hasMessageContaining("label");
        verifyNoInteractions(seriesRepository);
    }

    @Test
    void create_rejectsAMissingAmountAndCadence() {
        assertThatThrownBy(() -> tools.createRecurringSeries("Gym", null, null, null, null, null))
            .isInstanceOf(ConstraintViolationException.class)
            .hasMessageContaining("expectedAmount")
            .hasMessageContaining("cadence");
        verifyNoInteractions(seriesRepository);
    }

    @Test
    void update_rejectsAnOverlongCounterpartyAndWritesNothing() {
        assertThatThrownBy(() -> tools.updateRecurringSeries(
            1L, "Gym", new BigDecimal("-30"), RecurringCadence.MONTHLY, "x".repeat(256), null, null))
            .isInstanceOf(ConstraintViolationException.class)
            .hasMessageContaining("counterparty");
        verifyNoInteractions(seriesRepository);
    }

    @Test
    void preview_rejectsABlankPatternOrMissingMatchType() {
        assertThatThrownBy(() -> tools.previewBudgetRule(null, ""))
            .isInstanceOf(ConstraintViolationException.class)
            .hasMessageContaining("matchType")
            .hasMessageContaining("pattern");
        verifyNoInteractions(transactionRepository);
    }

    // ─── Reads ──────────────────────────────────────────────────────────────

    @Test
    void activity_listsARecentPriceStep() {
        RecurringSeries s = series(1L).status(RecurringStatus.CONFIRMED)
            .expectedAmount(new BigDecimal("-13.99")).previousAmount(new BigDecimal("-12.99"))
            .priceChangedAt(LocalDate.now().minusDays(3)).build();
        when(seriesRepository.findAllByMemberIdOrderByNextDueDateAsc(MID)).thenReturn(List.of(s));

        List<RecurringActivityResponse> feed = tools.getRecurringActivity();

        assertThat(feed).singleElement().satisfies(e -> {
            assertThat(e.seriesId()).isEqualTo(1L);
            assertThat(e.type()).isEqualTo(RecurringActivityType.PRICE_CHANGE);
            assertThat(e.previousAmount()).isEqualByComparingTo("-12.99");
        });
    }

    @Test
    void calendar_defaultsToA60DayHorizon() {
        LocalDate due = LocalDate.now().plusDays(10);
        RecurringSeries s = series(1L).status(RecurringStatus.CONFIRMED).nextDueDate(due).build();
        when(seriesRepository.findAllByMemberIdAndStatusOrderByNextDueDateAsc(MID, RecurringStatus.CONFIRMED))
            .thenReturn(List.of(s));

        assertThat(tools.getRecurringCalendar(null)).extracting(RecurringOccurrenceResponse::dueDate)
            .containsExactly(due, due.plusMonths(1));
        assertThat(tools.getRecurringCalendar(15)).extracting(RecurringOccurrenceResponse::dueDate)
            .containsExactly(due);
    }

    @Test
    void calendar_listsCardPaymentsOnlyWithAccountsRead() {
        LocalDate due = LocalDate.now().plusDays(10);
        RecurringSeries s = series(1L).status(RecurringStatus.CONFIRMED).nextDueDate(due).build();
        when(seriesRepository.findAllByMemberIdAndStatusOrderByNextDueDateAsc(MID, RecurringStatus.CONFIRMED))
            .thenReturn(List.of(s));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MID)).thenReturn(List.of(Account.builder()
            .id(5L).name("Amex Gold").type(AccountType.CREDIT_CARD)
            .paymentDueDate(due.plusDays(2)).paymentDueAmount(new BigDecimal("830.50")).build()));

        grant(Scopes.BUDGET_RECURRING_READ);
        assertThat(tools.getRecurringCalendar(15)).extracting(RecurringOccurrenceResponse::label)
            .containsExactly("Netflix");

        grant(Scopes.BUDGET_RECURRING_READ, Scopes.ACCOUNTS_READ);
        assertThat(tools.getRecurringCalendar(15)).extracting(RecurringOccurrenceResponse::label)
            .containsExactly("Netflix", "Amex Gold");
    }

    @Test
    void preview_countsMatchesAndWritesNothing() throws NoSuchMethodException {
        Transaction match = Transaction.builder().id(1L).date(LocalDate.of(2026, 9, 1))
            .amount(new BigDecimal("-40")).counterparty("CARREFOUR MARKET").description("CB CARREFOUR").build();
        Transaction other = Transaction.builder().id(2L).date(LocalDate.of(2026, 9, 2))
            .amount(new BigDecimal("-9")).counterparty("SNCF").description("CB SNCF").build();
        when(transactionRepository.findChangeable(MID)).thenReturn(List.of(match, other));
        grant(Scopes.BUDGET_RULES_READ, Scopes.BUDGET_TRANSACTIONS_READ);

        CategorizationService.RulePreviewResult out = tools.previewBudgetRule(RuleMatchType.KEYWORD, " carrefour ");

        assertThat(out.matchCount()).isEqualTo(1);
        assertThat(out.transactions()).extracting(CategorizationService.PreviewTransaction::id).containsExactly(1L);
        verify(transactionRepository).findChangeable(MID);
        verifyNoMoreInteractions(transactionRepository);
        verifyNoInteractions(ruleRepository, categoryRepository, seriesRepository, familyMemberRepository,
            settingsRepository, knowledgeBase, categoryService);
        assertThat(CategorizationService.class
            .getMethod("previewRule", RuleMatchType.class, String.class, Long.class)
            .getAnnotation(Transactional.class).readOnly()).isTrue();
        assertThat(match.getCategoryRef()).isNull();
    }

    @Test
    void preview_withoutTransactionsRead_returnsTheCountButNoRows() {
        Transaction match = Transaction.builder().id(1L).date(LocalDate.of(2026, 9, 1))
            .amount(new BigDecimal("-40")).counterparty("CARREFOUR MARKET").description("CB CARREFOUR").build();
        when(transactionRepository.findChangeable(MID)).thenReturn(List.of(match));
        grant(Scopes.BUDGET_RULES_READ);

        CategorizationService.RulePreviewResult out =
            scopeEnforced(tools).previewBudgetRule(RuleMatchType.KEYWORD, "carrefour");

        assertThat(out.matchCount()).isEqualTo(1);
        assertThat(out.transactions()).isEmpty();
    }

    @Test
    void spendingDetail_isRefusedToADashboardOnlyKey() {
        grant(Scopes.BUDGET_DASHBOARD_READ);

        assertThatThrownBy(() -> scopeEnforced(tools).getSpendingCategoryDetail(1L, null, null))
            .isInstanceOf(MissingScopeException.class)
            .hasMessageContaining(Scopes.BUDGET_TRANSACTIONS_READ);
        verifyNoInteractions(transactionRepository, categoryRepository);
    }

    // ─── Scope enforcement ──────────────────────────────────────────────────

    static Stream<Arguments> scopedNewTools() {
        return Stream.of(
            scoped(Scopes.BUDGET_RECURRING_WRITE, "confirm_recurring_series", t -> t.confirmRecurringSeries(1L)),
            scoped(Scopes.BUDGET_RECURRING_WRITE, "ignore_recurring_series", t -> t.ignoreRecurringSeries(1L)),
            scoped(Scopes.BUDGET_RECURRING_WRITE, "undo_recurring_series_change", t -> t.undoRecurringSeriesChange(1L)),
            scoped(Scopes.BUDGET_RECURRING_WRITE, "create_recurring_series", t -> t.createRecurringSeries(
                "Gym", BigDecimal.ONE, RecurringCadence.MONTHLY, null, null, null)),
            scoped(Scopes.BUDGET_RECURRING_WRITE, "update_recurring_series", t -> t.updateRecurringSeries(
                1L, "Gym", BigDecimal.ONE, RecurringCadence.MONTHLY, null, null, null)),
            scoped(Scopes.BUDGET_RECURRING_WRITE, "delete_recurring_series", t -> t.deleteRecurringSeries(1L)),
            scoped(Scopes.BUDGET_RECURRING_WRITE, "detect_recurring_series", BudgetTools::detectRecurringSeries),
            scoped(Scopes.BUDGET_RECURRING_READ, "get_recurring_activity", BudgetTools::getRecurringActivity),
            scoped(Scopes.BUDGET_RECURRING_READ, "get_recurring_calendar", t -> t.getRecurringCalendar(null)),
            scoped(Scopes.BUDGET_DASHBOARD_READ, "get_spending_by_category", t -> t.getSpendingByCategory(null, null)),
            scoped(Scopes.BUDGET_TRANSACTIONS_READ, "get_spending_category_detail",
                t -> t.getSpendingCategoryDetail(1L, null, null)),
            scoped(Scopes.BUDGET_DASHBOARD_READ, "get_cashflow", t -> t.getCashflow(null, null)),
            scoped(Scopes.BUDGET_DASHBOARD_READ, "get_cashflow_flow", t -> t.getCashflowFlow(null, null)),
            scoped(Scopes.BUDGET_RULES_READ, "preview_budget_rule", t -> t.previewBudgetRule(RuleMatchType.KEYWORD, "x")));
    }

    @ParameterizedTest
    @MethodSource("scopedNewTools")
    void refused_whenTheKeyLacksExactlyTheToolsScope(String requiredScope, Consumer<BudgetTools> call) {
        grantEveryScopeExcept(requiredScope);

        assertThatThrownBy(() -> call.accept(scopeEnforced(tools)))
            .isInstanceOf(MissingScopeException.class)
            .hasMessageContaining(requiredScope);
        verifyNoInteractions(seriesRepository, transactionRepository, categoryRepository, cashflowService,
            budgetSettingsService, familyMemberRepository);
    }

    @Test
    void readScopeAlone_cannotTriage_butTheWriteScopeCan() {
        RecurringSeries s = series(1L).build();
        BudgetTools proxied = scopeEnforced(tools);
        grant(Scopes.BUDGET_RECURRING_READ);

        assertThatThrownBy(() -> proxied.confirmRecurringSeries(1L)).isInstanceOf(MissingScopeException.class);
        verify(seriesRepository, never()).findByIdAndMemberId(anyLong(), anyLong());

        stored(s);
        echoSave();
        grant(Scopes.BUDGET_RECURRING_WRITE);

        assertThat(proxied.confirmRecurringSeries(1L).status()).isEqualTo(RecurringStatus.CONFIRMED);
    }

    private static BudgetTools scopeEnforced(BudgetTools target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new ScopeEnforcementAspect());
        return factory.getProxy();
    }

    private static void grant(String... scopes) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "key", null, Stream.of(scopes).map(SimpleGrantedAuthority::new).toList()));
    }

    private static void grantEveryScopeExcept(String scope) {
        grant(Scopes.ALL.stream().filter(s -> !s.equals(scope)).toArray(String[]::new));
    }

    private static Arguments tool(String name, Consumer<BudgetTools> call) {
        return Arguments.of(Named.of(name, call));
    }

    private static Arguments scoped(String scope, String name, Consumer<BudgetTools> call) {
        return Arguments.of(scope, Named.of(name, call));
    }
}
