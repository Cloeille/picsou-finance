package com.picsou.service.budget;

import com.picsou.model.AiCategorizationMode;
import com.picsou.model.BudgetSettings;
import com.picsou.model.CategorizationRule;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.FamilyMember;
import com.picsou.model.RuleMatchType;
import com.picsou.model.RuleSource;
import com.picsou.model.Transaction;
import com.picsou.port.TransactionCategorizerPort;
import com.picsou.repository.BudgetSettingsRepository;
import com.picsou.repository.CategorizationRuleRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategorizationServiceTest {

    @Mock CategorizationRuleRepository ruleRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock MerchantKnowledgeBase knowledgeBase;
    @Mock CategoryService categoryService;
    @Mock BudgetSettingsRepository settingsRepository;

    @InjectMocks CategorizationService service;

    private static MerchantKnowledgeBase.Brand brand(Long id, String slug, String categorySlug) {
        return new MerchantKnowledgeBase.Brand(id, slug, slug, categorySlug, "#000000", "X", null);
    }

    private static final Long MEMBER_ID = 10L;

    private Category category(Long id, CategoryKind kind, String name) {
        return Category.builder().id(id).kind(kind).name(name).build();
    }

    private CategorizationRule rule(RuleMatchType type, String pattern, Category cat, int priority) {
        return CategorizationRule.builder()
            .matchType(type).pattern(pattern).category(cat).priority(priority).source(RuleSource.USER)
            .build();
    }

    private Transaction tx(String counterparty, String description) {
        return Transaction.builder().counterparty(counterparty).description(description).build();
    }

    @Test
    void apply_counterpartyRule_matchesExactlyIgnoringCase() {
        Category groceries = category(1L, CategoryKind.EXPENSE, "Courses");
        List<CategorizationRule> rules = List.of(rule(RuleMatchType.COUNTERPARTY, "Carrefour", groceries, 0));

        Transaction t = tx("CARREFOUR", "CB CARREFOUR PARIS");
        boolean matched = service.apply(t, rules);

        assertThat(matched).isTrue();
        assertThat(t.getCategoryRef()).isEqualTo(groceries);
    }

    @Test
    void apply_keywordRule_matchesSubstringInDescription() {
        Category subs = category(2L, CategoryKind.EXPENSE, "Abonnements");
        List<CategorizationRule> rules = List.of(rule(RuleMatchType.KEYWORD, "netflix", subs, 0));

        Transaction t = tx(null, "PRLV NETFLIX.COM");
        assertThat(service.apply(t, rules)).isTrue();
        assertThat(t.getCategoryRef()).isEqualTo(subs);
    }

    @Test
    void apply_higherPriorityRuleWins() {
        Category generic = category(3L, CategoryKind.EXPENSE, "Divers");
        Category transport = category(4L, CategoryKind.EXPENSE, "Transport");
        // Service is given rules already ordered priority-desc by the repository.
        List<CategorizationRule> rules = List.of(
            rule(RuleMatchType.KEYWORD, "sncf", transport, 10),
            rule(RuleMatchType.KEYWORD, "cb", generic, 0)
        );

        Transaction t = tx("SNCF", "CB SNCF INTERNET");
        service.apply(t, rules);
        assertThat(t.getCategoryRef()).isEqualTo(transport);
    }

    @Test
    void apply_noMatch_leavesUncategorized() {
        Category groceries = category(1L, CategoryKind.EXPENSE, "Courses");
        List<CategorizationRule> rules = List.of(rule(RuleMatchType.COUNTERPARTY, "Carrefour", groceries, 0));

        Transaction t = tx("AMAZON", "AMZN MKTPLACE");
        assertThat(service.apply(t, rules)).isFalse();
        assertThat(t.getCategoryRef()).isNull();
    }

    @Test
    void apply_neverOverridesExistingCategory() {
        Category existing = category(5L, CategoryKind.EXPENSE, "Manual");
        Category groceries = category(1L, CategoryKind.EXPENSE, "Courses");
        List<CategorizationRule> rules = List.of(rule(RuleMatchType.COUNTERPARTY, "Carrefour", groceries, 0));

        Transaction t = tx("CARREFOUR", "CB CARREFOUR");
        t.setCategoryRef(existing);

        assertThat(service.apply(t, rules)).isFalse();
        assertThat(t.getCategoryRef()).isEqualTo(existing);
    }

    @Test
    void learnRule_createsAutoCounterpartyRule_whenNoneExists() {
        Category groceries = category(1L, CategoryKind.EXPENSE, "Courses");
        when(ruleRepository.findFirstByMemberIdAndMatchTypeAndPatternIgnoreCase(
            MEMBER_ID, RuleMatchType.COUNTERPARTY, "Carrefour")).thenReturn(Optional.empty());
        when(categoryRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(groceries));
        when(familyMemberRepository.getReferenceById(MEMBER_ID)).thenReturn(new FamilyMember());

        service.learnRule("Carrefour", 1L, MEMBER_ID);

        ArgumentCaptor<CategorizationRule> captor = ArgumentCaptor.forClass(CategorizationRule.class);
        verify(ruleRepository).save(captor.capture());
        CategorizationRule saved = captor.getValue();
        assertThat(saved.getSource()).isEqualTo(RuleSource.AUTO);
        assertThat(saved.getMatchType()).isEqualTo(RuleMatchType.COUNTERPARTY);
        assertThat(saved.getPattern()).isEqualTo("Carrefour");
        assertThat(saved.getCategory()).isEqualTo(groceries);
    }

    @Test
    void learnRule_isIdempotent_whenRuleAlreadyExists() {
        when(ruleRepository.findFirstByMemberIdAndMatchTypeAndPatternIgnoreCase(
            MEMBER_ID, RuleMatchType.COUNTERPARTY, "Carrefour"))
            .thenReturn(Optional.of(rule(RuleMatchType.COUNTERPARTY, "Carrefour",
                category(1L, CategoryKind.EXPENSE, "Courses"), 0)));

        service.learnRule("Carrefour", 1L, MEMBER_ID);

        verify(ruleRepository, never()).save(any());
    }

    // ─── Zero-config pipeline: enrich + brand fallback ─────────────────────────

    @Test
    void autoCategorize_assignsBrandCategory_whenNoRuleMatches() {
        Category courses = category(1L, CategoryKind.EXPENSE, "Courses");
        MerchantKnowledgeBase.Brand carrefour = brand(7L, "carrefour", "courses");
        when(knowledgeBase.match(anyString())).thenReturn(Optional.of(carrefour));
        when(knowledgeBase.findById(7L)).thenReturn(Optional.of(carrefour));

        var ctx = new CategorizationService.CategorizationContext(List.of(), Map.of("courses", courses));
        Transaction t = tx("CARREFOUR", "CB CARREFOUR PARIS");

        assertThat(service.autoCategorize(t, ctx)).isTrue();
        assertThat(t.getMerchantLabel()).isEqualTo("Carrefour");
        assertThat(t.getMerchantBrandId()).isEqualTo(7L);
        assertThat(t.getCategoryRef()).isEqualTo(courses);
    }

    @Test
    void autoCategorize_userRuleWinsOverBrand_butStillEnriches() {
        Category courses = category(1L, CategoryKind.EXPENSE, "Courses");
        Category restaurants = category(2L, CategoryKind.EXPENSE, "Restaurants");
        MerchantKnowledgeBase.Brand carrefour = brand(7L, "carrefour", "courses");
        when(knowledgeBase.match(anyString())).thenReturn(Optional.of(carrefour));

        // A user/learned rule maps the same merchant elsewhere — it must win over the brand.
        var rules = List.of(rule(RuleMatchType.KEYWORD, "carrefour", restaurants, 0));
        var ctx = new CategorizationService.CategorizationContext(rules, Map.of("courses", courses));
        Transaction t = tx("CARREFOUR", "CB CARREFOUR");

        assertThat(service.autoCategorize(t, ctx)).isTrue();
        assertThat(t.getCategoryRef()).isEqualTo(restaurants);
        assertThat(t.getMerchantBrandId()).isEqualTo(7L); // enrichment still happened
        verify(knowledgeBase, never()).findById(any());   // brand fallback never reached
    }

    @Test
    void autoCategorize_neverOverridesExistingCategory() {
        Category existing = category(5L, CategoryKind.EXPENSE, "Manual");
        when(knowledgeBase.match(anyString())).thenReturn(Optional.empty());

        var ctx = new CategorizationService.CategorizationContext(List.of(), Map.of());
        Transaction t = tx("CARREFOUR", "CB CARREFOUR");
        t.setCategoryRef(existing);

        assertThat(service.autoCategorize(t, ctx)).isFalse();
        assertThat(t.getCategoryRef()).isEqualTo(existing);
        assertThat(t.getMerchantLabel()).isEqualTo("Carrefour"); // still labelled
    }

    @Test
    void autoCategorize_brandKnown_butMemberLacksSlug_staysUncategorized() {
        MerchantKnowledgeBase.Brand carrefour = brand(7L, "carrefour", "courses");
        when(knowledgeBase.match(anyString())).thenReturn(Optional.of(carrefour));
        when(knowledgeBase.findById(7L)).thenReturn(Optional.of(carrefour));

        var ctx = new CategorizationService.CategorizationContext(List.of(), Map.of()); // no "courses"
        Transaction t = tx("CARREFOUR", "CB CARREFOUR");

        assertThat(service.autoCategorize(t, ctx)).isFalse();
        assertThat(t.getCategoryRef()).isNull();
        assertThat(t.getMerchantBrandId()).isEqualTo(7L); // brand linked even without a category
    }

    @Test
    void autoCategorize_unknownMerchant_staysUncategorized_butLabelled() {
        when(knowledgeBase.match(anyString())).thenReturn(Optional.empty());

        var ctx = new CategorizationService.CategorizationContext(List.of(), Map.of());
        Transaction t = tx("BOULANGERIE DUPONT", null);

        assertThat(service.autoCategorize(t, ctx)).isFalse();
        assertThat(t.getCategoryRef()).isNull();
        assertThat(t.getMerchantLabel()).isEqualTo("Boulangerie Dupont");
        assertThat(t.getMerchantBrandId()).isNull();
    }

    // ─── AI fallback (optional, opt-in) ────────────────────────────────────────

    private Category categoryWithSlug(Long id, String slug, String name) {
        return Category.builder().id(id).kind(CategoryKind.EXPENSE).name(name).slug(slug).build();
    }

    private BudgetSettings aiSettings(boolean enabled, AiCategorizationMode mode, int threshold) {
        return BudgetSettings.builder()
            .aiCategorizationEnabled(enabled).aiMode(mode).aiConfidenceThreshold(threshold).build();
    }

    private Transaction uncategorized(String label, String description, String amount) {
        return Transaction.builder().merchantLabel(label).description(description).amount(new BigDecimal(amount)).build();
    }

    // ─── loadAiContext ─────────────────────────────────────────────────────────

    @Test
    void loadAiContext_disabledWhenSettingsOff() {
        when(settingsRepository.findByMemberId(MEMBER_ID))
            .thenReturn(Optional.of(aiSettings(false, AiCategorizationMode.AUTO_ALL, 75)));

        CategorizationService.AiContext ctx = service.loadAiContext(MEMBER_ID);

        assertThat(ctx.enabled()).isFalse();
        assertThat(ctx.options()).isEmpty();
        assertThat(ctx.examples()).isEmpty();
        assertThat(ctx.categoryIdBySlug()).isEmpty();
    }

    // ─── applyAiResults ────────────────────────────────────────────────────────

    private CategorizationService.AiContext aiCtx(AiCategorizationMode mode, int threshold,
                                                    Map<String, Long> slugToId) {
        return new CategorizationService.AiContext(List.of(), List.of(), slugToId, mode, threshold, true);
    }

    @Test
    void applyAiResults_autoAll_allApplied() {
        Category courses = categoryWithSlug(1L, "courses", "Courses");
        Transaction unc = uncategorized("Carrefour", "CB CARREFOUR", "42.30");
        when(transactionRepository.findByIdAndAccountMemberId(42L, MEMBER_ID))
            .thenReturn(Optional.of(unc));
        when(categoryRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(courses));

        var ctx = aiCtx(AiCategorizationMode.AUTO_ALL, 75, Map.of("courses", 1L));
        Map<Long, Boolean> result = service.applyAiResults(
            Map.of(42L, new TransactionCategorizerPort.CategorySuggestion("courses", 0.50)),
            ctx, MEMBER_ID);

        assertThat(result).containsEntry(42L, true);
        assertThat(unc.getCategoryRef()).isEqualTo(courses);
        assertThat(unc.getAiSuggestedCategoryId()).isNull();
        assertThat(unc.getAiConfidence()).isNull();
    }

    @Test
    void applyAiResults_autoHighConfidence_atThreshold_applied() {
        Category courses = categoryWithSlug(1L, "courses", "Courses");
        Transaction unc = uncategorized("Carrefour", "CB CARREFOUR", "42.30");
        when(transactionRepository.findByIdAndAccountMemberId(42L, MEMBER_ID))
            .thenReturn(Optional.of(unc));
        when(categoryRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(courses));

        var ctx = aiCtx(AiCategorizationMode.AUTO_HIGH_CONFIDENCE, 75, Map.of("courses", 1L));
        Map<Long, Boolean> result = service.applyAiResults(
            Map.of(42L, new TransactionCategorizerPort.CategorySuggestion("courses", 0.75)),
            ctx, MEMBER_ID);

        assertThat(result).containsEntry(42L, true);
        assertThat(unc.getCategoryRef()).isEqualTo(courses);
    }

    @Test
    void applyAiResults_autoHighConfidence_belowThreshold_suggested() {
        Transaction unc = uncategorized("Carrefour", "CB CARREFOUR", "42.30");
        when(transactionRepository.findByIdAndAccountMemberId(42L, MEMBER_ID))
            .thenReturn(Optional.of(unc));

        var ctx = aiCtx(AiCategorizationMode.AUTO_HIGH_CONFIDENCE, 75, Map.of("courses", 1L));
        Map<Long, Boolean> result = service.applyAiResults(
            Map.of(42L, new TransactionCategorizerPort.CategorySuggestion("courses", 0.50)),
            ctx, MEMBER_ID);

        assertThat(result).containsEntry(42L, false);
        assertThat(unc.getCategoryRef()).isNull();
        assertThat(unc.getAiSuggestedCategoryId()).isEqualTo(1L);
        assertThat(unc.getAiConfidence()).isEqualTo(50);
    }

    @Test
    void applyAiResults_suggestMode_neverApplies() {
        Transaction unc = uncategorized("Carrefour", "CB CARREFOUR", "42.30");
        when(transactionRepository.findByIdAndAccountMemberId(42L, MEMBER_ID))
            .thenReturn(Optional.of(unc));

        var ctx = aiCtx(AiCategorizationMode.SUGGEST, 75, Map.of("courses", 1L));
        Map<Long, Boolean> result = service.applyAiResults(
            Map.of(42L, new TransactionCategorizerPort.CategorySuggestion("courses", 0.99)),
            ctx, MEMBER_ID);

        assertThat(result).containsEntry(42L, false);
        assertThat(unc.getCategoryRef()).isNull();
        assertThat(unc.getAiSuggestedCategoryId()).isEqualTo(1L);
        assertThat(unc.getAiConfidence()).isEqualTo(99);
    }

    @Test
    void applyAiResults_unknownSlug_absentFromResult() {
        Transaction unc = uncategorized("Mystery", "CB MYSTERY", "9.99");

        // "restaurants" not in map — should skip without touching the repo
        var ctx = aiCtx(AiCategorizationMode.AUTO_ALL, 0, Map.of("courses", 1L));
        Map<Long, Boolean> result = service.applyAiResults(
            Map.of(42L, new TransactionCategorizerPort.CategorySuggestion("restaurants", 0.95)),
            ctx, MEMBER_ID);

        assertThat(result).doesNotContainKey(42L);
        assertThat(unc.getCategoryRef()).isNull();
    }

    @Test
    void applyAiResults_alreadyCategorized_absentFromResult() {
        Category existing = category(5L, CategoryKind.EXPENSE, "Manual");
        Transaction unc = uncategorized("Carrefour", "CB CARREFOUR", "42.30");
        unc.setCategoryRef(existing);
        when(transactionRepository.findByIdAndAccountMemberId(42L, MEMBER_ID))
            .thenReturn(Optional.of(unc));

        var ctx = aiCtx(AiCategorizationMode.AUTO_ALL, 0, Map.of("courses", 1L));
        Map<Long, Boolean> result = service.applyAiResults(
            Map.of(42L, new TransactionCategorizerPort.CategorySuggestion("courses", 0.95)),
            ctx, MEMBER_ID);

        assertThat(result).doesNotContainKey(42L);
        assertThat(unc.getCategoryRef()).isEqualTo(existing);
    }

    // ─── KEYWORDS_ALL match type ──────────────────────────────────────────────

    @Test
    void apply_keywordsAllRule_requiresAllTokens_caseInsensitive() {
        Category groceries = category(1L, CategoryKind.EXPENSE, "Courses");
        List<CategorizationRule> rules = List.of(rule(RuleMatchType.KEYWORDS_ALL, "carrefour city", groceries, 0));

        // All tokens present
        Transaction t = tx("CARREFOUR CITY", "CB CARREFOUR CITY PARIS");
        assertThat(service.apply(t, rules)).isTrue();
        assertThat(t.getCategoryRef()).isEqualTo(groceries);
    }

    @Test
    void apply_keywordsAllRule_failsWhenOneTokenMissing() {
        Category groceries = category(1L, CategoryKind.EXPENSE, "Courses");
        List<CategorizationRule> rules = List.of(rule(RuleMatchType.KEYWORDS_ALL, "carrefour bio", groceries, 0));

        // "bio" is absent
        Transaction t = tx("CARREFOUR CITY", "CB CARREFOUR CITY PARIS");
        assertThat(service.apply(t, rules)).isFalse();
        assertThat(t.getCategoryRef()).isNull();
    }

    @Test
    void apply_keywordsAllRule_orderIndependent() {
        Category groceries = category(1L, CategoryKind.EXPENSE, "Courses");
        List<CategorizationRule> rules = List.of(rule(RuleMatchType.KEYWORDS_ALL, "city carrefour", groceries, 0));

        Transaction t = tx("CARREFOUR CITY", "CB CARREFOUR CITY PARIS");
        assertThat(service.apply(t, rules)).isTrue();
    }

    // ─── KEYWORDS_ANY match type ──────────────────────────────────────────────

    @Test
    void apply_keywordsAnyRule_matchesWhenAtLeastOneTokenPresent() {
        Category subs = category(2L, CategoryKind.EXPENSE, "Abonnements");
        List<CategorizationRule> rules = List.of(rule(RuleMatchType.KEYWORDS_ANY, "netflix spotify", subs, 0));

        Transaction t = tx(null, "PRLV SPOTIFY.COM");
        assertThat(service.apply(t, rules)).isTrue();
        assertThat(t.getCategoryRef()).isEqualTo(subs);
    }

    @Test
    void apply_keywordsAnyRule_failsWhenNoTokenPresent() {
        Category subs = category(2L, CategoryKind.EXPENSE, "Abonnements");
        List<CategorizationRule> rules = List.of(rule(RuleMatchType.KEYWORDS_ANY, "netflix spotify", subs, 0));

        Transaction t = tx("AMAZON", "AMZN MKTPLACE");
        assertThat(service.apply(t, rules)).isFalse();
    }

    // ─── merchantLabel as match source ────────────────────────────────────────

    @Test
    void apply_keywordRule_matchesMerchantLabel() {
        Category restaurants = category(3L, CategoryKind.EXPENSE, "Restaurants");
        // Pattern "mcdonald" is a substring of the merchantLabel "McDonald's" (lowercased: "mcdonald's")
        // but NOT a substring of the counterparty "MC DONALD'S FR 7521" (space between MC and DONALD'S)
        // — so this test specifically exercises the merchantLabel source.
        List<CategorizationRule> rules = List.of(rule(RuleMatchType.KEYWORD, "mcdonald", restaurants, 0));

        Transaction t = Transaction.builder()
            .counterparty("MC DONALD'S FR 7521")
            .description("CB 7521")
            .merchantLabel("McDonald's")
            .build();
        assertThat(service.apply(t, rules)).isTrue();
        assertThat(t.getCategoryRef()).isEqualTo(restaurants);
    }

    @Test
    void apply_keywordsAllRule_matchesMerchantLabel() {
        Category restaurants = category(3L, CategoryKind.EXPENSE, "Restaurants");
        List<CategorizationRule> rules = List.of(rule(RuleMatchType.KEYWORDS_ALL, "mc donald", restaurants, 0));

        Transaction t = Transaction.builder()
            .counterparty("MC DONALD'S")
            .description("CB")
            .merchantLabel("Mc Donald's Paris")
            .build();
        // "mc" and "donald" must both appear anywhere in the sources
        assertThat(service.apply(t, rules)).isTrue();
    }

    // ─── manual-override guard with categoryManual ────────────────────────────

    @Test
    void apply_neverOverridesManuallySetCategory() {
        Category existing = category(5L, CategoryKind.EXPENSE, "Manual");
        Category groceries = category(1L, CategoryKind.EXPENSE, "Courses");
        List<CategorizationRule> rules = List.of(rule(RuleMatchType.COUNTERPARTY, "Carrefour", groceries, 0));

        Transaction t = tx("CARREFOUR", "CB CARREFOUR");
        t.setCategoryRef(existing);
        t.setCategoryManual(true);

        assertThat(service.apply(t, rules)).isFalse();
        assertThat(t.getCategoryRef()).isEqualTo(existing);
    }

    // ─── learnRule idempotency for new types ─────────────────────────────────

    @Test
    void learnRuleInternal_isIdempotent_forKeywordsAll() {
        Category groceries = category(1L, CategoryKind.EXPENSE, "Courses");
        CategorizationRule existing = rule(RuleMatchType.KEYWORDS_ALL, "carrefour city", groceries, 0);
        when(ruleRepository.findFirstByMemberIdAndMatchTypeAndPatternIgnoreCase(
            MEMBER_ID, RuleMatchType.KEYWORDS_ALL, "carrefour city")).thenReturn(Optional.of(existing));

        CategorizationRule result = service.learnRuleInternal("carrefour city", RuleMatchType.KEYWORDS_ALL, 1L, MEMBER_ID);

        assertThat(result).isEqualTo(existing);
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void learnRuleInternal_isIdempotent_forKeywordsAny() {
        Category subs = category(2L, CategoryKind.EXPENSE, "Abonnements");
        CategorizationRule existing = rule(RuleMatchType.KEYWORDS_ANY, "netflix spotify", subs, 0);
        when(ruleRepository.findFirstByMemberIdAndMatchTypeAndPatternIgnoreCase(
            MEMBER_ID, RuleMatchType.KEYWORDS_ANY, "netflix spotify")).thenReturn(Optional.of(existing));

        CategorizationRule result = service.learnRuleInternal("netflix spotify", RuleMatchType.KEYWORDS_ANY, 2L, MEMBER_ID);

        assertThat(result).isEqualTo(existing);
        verify(ruleRepository, never()).save(any());
    }
}
