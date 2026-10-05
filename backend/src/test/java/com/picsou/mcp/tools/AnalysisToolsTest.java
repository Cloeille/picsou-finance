package com.picsou.mcp.tools;

import com.picsou.controller.RealEstateController.ValuationHistoryEntry;
import com.picsou.dto.AllocationResponse;
import com.picsou.dto.AllocationTargetsResponse;
import com.picsou.dto.CashflowPeriod;
import com.picsou.dto.DiversificationResponse;
import com.picsou.dto.EssentialExpenseEstimateResponse;
import com.picsou.dto.ExchangePositionResponse;
import com.picsou.dto.ProjectionResponse;
import com.picsou.dto.RealEstateSummaryResponse;
import com.picsou.dto.RealizedPnlResponse;
import com.picsou.dto.SavingsInterestProjection;
import com.picsou.dto.SavingsSuggestionResponse;
import com.picsou.dto.SecurityInsightResponse;
import com.picsou.dto.WealthPyramidResponse;
import com.picsou.exception.MissingScopeException;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.mcp.RequiresScope;
import com.picsou.mcp.ScopeEnforcementAspect;
import com.picsou.mcp.Scopes;
import com.picsou.model.PropertyValuation;
import com.picsou.model.ValuationConfidence;
import com.picsou.service.AccountService;
import com.picsou.service.AllocationTargetService;
import com.picsou.service.CryptoExchangeSyncService;
import com.picsou.service.EssentialExpenseEstimator;
import com.picsou.service.LoanAmortizationService.LoanScheduleResponse;
import com.picsou.service.PortfolioDiversificationService;
import com.picsou.service.ProjectionService;
import com.picsou.service.PropertyValuationService;
import com.picsou.service.RealEstateSummaryService;
import com.picsou.service.RealizedPnlService;
import com.picsou.service.SavingsService;
import com.picsou.service.SecurityInsightService;
import com.picsou.service.UserContext;
import com.picsou.service.WealthPyramidService;
import com.picsou.service.budget.AllocationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Read-only analysis tools. Each delegates to the service behind its REST counterpart with the
 * caller's member, so another member's account surfaces the service's own not-found error.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisToolsTest {

    private static final long MID = 7L;
    private static final long OTHER_MEMBERS_ACCOUNT = 99L;

    @Mock AllocationService allocationService;
    @Mock WealthPyramidService pyramidService;
    @Mock PortfolioDiversificationService diversificationService;
    @Mock ProjectionService projectionService;
    @Mock AllocationTargetService allocationTargetService;
    @Mock EssentialExpenseEstimator expenseEstimator;
    @Mock SavingsService savingsService;
    @Mock SecurityInsightService securityInsightService;
    @Mock RealEstateSummaryService realEstateSummaryService;
    @Mock PropertyValuationService propertyValuationService;
    @Mock AccountService accountService;
    @Mock RealizedPnlService realizedPnlService;
    @Mock CryptoExchangeSyncService cryptoExchangeSyncService;
    @Mock UserContext userContext;
    @InjectMocks AnalysisTools tools;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── Delegation, scoped to the current member ──────────────────────────────

    @Test
    void getAllocation_defaultsToCurrentCycleAndToday() {
        AllocationResponse r = mock(AllocationResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(allocationService.compute(MID, CashflowPeriod.CYCLE, LocalDate.now())).thenReturn(r);

        assertThat(tools.getAllocation(null, null)).isSameAs(r);
    }

    @Test
    void getAllocation_passesExplicitPeriodAndAnchor() {
        AllocationResponse r = mock(AllocationResponse.class);
        LocalDate anchor = LocalDate.of(2026, 3, 15);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(allocationService.compute(MID, CashflowPeriod.YTD, anchor)).thenReturn(r);

        assertThat(tools.getAllocation(CashflowPeriod.YTD, anchor)).isSameAs(r);
    }

    @Test
    void getWealthPyramid_delegates() {
        WealthPyramidResponse r = mock(WealthPyramidResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(pyramidService.pyramid(MID)).thenReturn(r);

        assertThat(tools.getWealthPyramid()).isSameAs(r);
    }

    @Test
    void getPortfolioDiversification_delegates() {
        DiversificationResponse r = mock(DiversificationResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(diversificationService.diversification(MID)).thenReturn(r);

        assertThat(tools.getPortfolioDiversification()).isSameAs(r);
    }

    @Test
    void getWealthProjection_defaultsToTwentyYearsLikeRest() {
        ProjectionResponse r = mock(ProjectionResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(projectionService.project(MID, 20)).thenReturn(r);

        assertThat(tools.getWealthProjection(null)).isSameAs(r);
    }

    @Test
    void getWealthProjection_leavesClampingToTheService() {
        ProjectionResponse r = mock(ProjectionResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(projectionService.project(MID, 500)).thenReturn(r);

        assertThat(tools.getWealthProjection(500)).isSameAs(r);
    }

    @Test
    void getAllocationTargets_delegates() {
        AllocationTargetsResponse r = mock(AllocationTargetsResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(allocationTargetService.get(MID)).thenReturn(r);

        assertThat(tools.getAllocationTargets()).isSameAs(r);
    }

    @Test
    void getEssentialExpenseEstimate_delegates() {
        EssentialExpenseEstimateResponse r = mock(EssentialExpenseEstimateResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(expenseEstimator.estimate(MID)).thenReturn(r);

        assertThat(tools.getEssentialExpenseEstimate()).isSameAs(r);
    }

    @Test
    void getSavingsSuggestions_delegates() {
        SavingsSuggestionResponse s = mock(SavingsSuggestionResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(savingsService.getSuggestions(MID)).thenReturn(List.of(s));

        assertThat(tools.getSavingsSuggestions()).containsExactly(s);
    }

    @Test
    void getRealEstateSummary_delegates() {
        RealEstateSummaryResponse r = mock(RealEstateSummaryResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(realEstateSummaryService.summarize(MID)).thenReturn(r);

        assertThat(tools.getRealEstateSummary()).isSameAs(r);
    }

    @Test
    void getSavingsInterest_delegates() {
        SavingsInterestProjection r = mock(SavingsInterestProjection.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(savingsService.getProjection(3L, MID)).thenReturn(r);

        assertThat(tools.getSavingsInterest(3L)).isSameAs(r);
    }

    @Test
    void getPropertyValuations_mapsToTheRestHistoryEntry() {
        PropertyValuation v = new PropertyValuation();
        v.setValuedAt(LocalDate.of(2026, 9, 1));
        v.setEstimatedValue(new BigDecimal("310000"));
        v.setLowValue(new BigDecimal("290000"));
        v.setHighValue(new BigDecimal("330000"));
        v.setPricePerSqm(new BigDecimal("4100"));
        v.setProvider("DVF");
        v.setConfidence(ValuationConfidence.HIGH);
        v.setSampleSize(42);
        v.setSourceYear((short) 2025);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(propertyValuationService.history(4L, MID)).thenReturn(List.of(v));

        assertThat(tools.getPropertyValuations(4L)).containsExactly(new ValuationHistoryEntry(
            LocalDate.of(2026, 9, 1), new BigDecimal("310000"), new BigDecimal("290000"),
            new BigDecimal("330000"), new BigDecimal("4100"), "DVF", ValuationConfidence.HIGH,
            42, (short) 2025));
    }

    @Test
    void getLoanSummary_delegates() {
        LoanScheduleResponse r = new LoanScheduleResponse(null, List.of());
        when(userContext.currentMemberId()).thenReturn(MID);
        when(accountService.getLoanSummary(5L, MID)).thenReturn(r);

        assertThat(tools.getLoanSummary(5L)).isSameAs(r);
    }

    @Test
    void getRealizedPnl_delegates() {
        RealizedPnlResponse r = mock(RealizedPnlResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(realizedPnlService.compute(6L, MID)).thenReturn(r);

        assertThat(tools.getRealizedPnl(6L)).isSameAs(r);
    }

    @Test
    void getExchangePositions_delegates() {
        ExchangePositionResponse p = mock(ExchangePositionResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(cryptoExchangeSyncService.getPositions(8L, MID)).thenReturn(List.of(p));

        assertThat(tools.getExchangePositions(8L)).containsExactly(p);
    }

    @Test
    void getSecurityInsight_isReferenceDataAndNeverResolvesAMember() {
        SecurityInsightResponse r = new SecurityInsightResponse("CW8.PA", "ETF", null);
        when(securityInsightService.getInsight("CW8.PA", "Amundi MSCI World")).thenReturn(r);

        assertThat(tools.getSecurityInsight("CW8.PA", "Amundi MSCI World")).isSameAs(r);
        verifyNoInteractions(userContext);
    }

    // ── Another member's account → the service's not-found, unchanged ─────────

    @Test
    void accountTools_surfaceNotFoundForAnotherMembersAccount() {
        ResourceNotFoundException notFound = ResourceNotFoundException.account(OTHER_MEMBERS_ACCOUNT);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(savingsService.getProjection(OTHER_MEMBERS_ACCOUNT, MID)).thenThrow(notFound);
        when(propertyValuationService.history(OTHER_MEMBERS_ACCOUNT, MID)).thenThrow(notFound);
        when(accountService.getLoanSummary(OTHER_MEMBERS_ACCOUNT, MID)).thenThrow(notFound);
        when(realizedPnlService.compute(OTHER_MEMBERS_ACCOUNT, MID)).thenThrow(notFound);
        when(cryptoExchangeSyncService.getPositions(OTHER_MEMBERS_ACCOUNT, MID)).thenThrow(notFound);

        List<Executable> calls = List.of(
            () -> tools.getSavingsInterest(OTHER_MEMBERS_ACCOUNT),
            () -> tools.getPropertyValuations(OTHER_MEMBERS_ACCOUNT),
            () -> tools.getLoanSummary(OTHER_MEMBERS_ACCOUNT),
            () -> tools.getRealizedPnl(OTHER_MEMBERS_ACCOUNT),
            () -> tools.getExchangePositions(OTHER_MEMBERS_ACCOUNT));
        for (Executable call : calls) {
            assertThatThrownBy(call::execute).isSameAs(notFound);
        }
    }

    // ── Scopes ───────────────────────────────────────────────────────────────

    @Test
    void eachToolCarriesTheDocumentedScope() {
        Map<String, String> scopeByTool = Arrays.stream(AnalysisTools.class.getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(Tool.class))
            .collect(Collectors.toMap(m -> m.getAnnotation(Tool.class).name(),
                m -> m.getAnnotation(RequiresScope.class).value()));

        assertThat(scopeByTool).isEqualTo(Map.ofEntries(
            Map.entry("get_allocation", "analysis:read"),
            Map.entry("get_wealth_pyramid", "analysis:read"),
            Map.entry("get_portfolio_diversification", "analysis:read"),
            Map.entry("get_wealth_projection", "analysis:read"),
            Map.entry("get_allocation_targets", "analysis:read"),
            Map.entry("get_essential_expense_estimate", "analysis:read"),
            Map.entry("get_savings_suggestions", "analysis:read"),
            Map.entry("get_real_estate_summary", "analysis:read"),
            Map.entry("get_savings_interest", "accounts:read"),
            Map.entry("get_property_valuations", "accounts:read"),
            Map.entry("get_loan_summary", "accounts:read"),
            Map.entry("get_realized_pnl", "accounts:read"),
            Map.entry("get_exchange_positions", "accounts:read"),
            Map.entry("get_security_insight", "prices:read")));
    }

    @Test
    void aKeyWithoutTheToolsScopeIsRejectedBeforeAnyServiceRuns() throws Exception {
        AnalysisTools proxied = withScopeEnforcement();
        for (Method m : toolMethods()) {
            String required = m.getAnnotation(RequiresScope.class).value();
            grantEveryScopeExcept(required);

            assertThatThrownBy(() -> invoke(proxied, m))
                .as("%s without %s", m.getName(), required)
                .isInstanceOf(MissingScopeException.class)
                .hasMessageContaining(required);
        }
        verifyNoInteractions(allocationService, pyramidService, diversificationService, projectionService,
            allocationTargetService, expenseEstimator, savingsService, securityInsightService,
            realEstateSummaryService, propertyValuationService, accountService, realizedPnlService,
            cryptoExchangeSyncService, userContext);
    }

    @Test
    void analysisReadAloneReachesTheWholeWealthTools() throws Exception {
        WealthPyramidResponse r = mock(WealthPyramidResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(pyramidService.pyramid(MID)).thenReturn(r);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "key", null, List.of(new SimpleGrantedAuthority(Scopes.ANALYSIS_READ))));

        assertThat(withScopeEnforcement().getWealthPyramid()).isSameAs(r);
    }

    private AnalysisTools withScopeEnforcement() {
        AspectJProxyFactory factory = new AspectJProxyFactory(tools);
        factory.setProxyTargetClass(true);
        factory.addAspect(new ScopeEnforcementAspect());
        return factory.getProxy();
    }

    private static List<Method> toolMethods() {
        return Arrays.stream(AnalysisTools.class.getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(Tool.class))
            .toList();
    }

    private static void grantEveryScopeExcept(String scope) {
        List<SimpleGrantedAuthority> granted = Scopes.ALL.stream()
            .filter(s -> !s.equals(scope))
            .map(SimpleGrantedAuthority::new)
            .toList();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("key", null, granted));
    }

    private static Object invoke(AnalysisTools target, Method m) throws Throwable {
        try {
            return m.invoke(target, new Object[m.getParameterCount()]);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
