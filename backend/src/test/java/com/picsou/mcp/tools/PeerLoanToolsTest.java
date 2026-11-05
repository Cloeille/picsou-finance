package com.picsou.mcp.tools;

import com.picsou.dto.PeerLoanDetailResponse;
import com.picsou.dto.PeerLoanRepaymentRequest;
import com.picsou.dto.PeerLoanRepaymentResponse;
import com.picsou.dto.PeerLoansOverviewResponse;
import com.picsou.exception.MissingScopeException;
import com.picsou.mcp.RequiresScope;
import com.picsou.mcp.ScopeEnforcementAspect;
import com.picsou.mcp.Scopes;
import com.picsou.model.PeerLoanRepaymentKind;
import com.picsou.service.PeerLoanService;
import com.picsou.service.UserContext;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.ai.tool.annotation.Tool;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PeerLoanToolsTest {

    private static final long MID = 7L;

    @Mock PeerLoanService peerLoanService;
    @Mock UserContext userContext;
    @Mock Validator validator;
    @InjectMocks PeerLoanTools tools;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getPeerLoan_delegatesScopedToTheCurrentMember() {
        PeerLoanDetailResponse detail = org.mockito.Mockito.mock(PeerLoanDetailResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(peerLoanService.get(12L, MID)).thenReturn(detail);

        assertThat(tools.getPeerLoan(12L)).isSameAs(detail);
    }

    @Test
    void addPeerLoanRepayment_buildsTheRequestAndDelegates() {
        PeerLoanRepaymentResponse row = org.mockito.Mockito.mock(PeerLoanRepaymentResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(peerLoanService.addRepayment(org.mockito.ArgumentMatchers.eq(12L),
            org.mockito.ArgumentMatchers.eq(MID), org.mockito.ArgumentMatchers.any()))
            .thenReturn(row);

        PeerLoanRepaymentResponse result = tools.addPeerLoanRepayment(12L, LocalDate.parse("2026-11-05"),
            new BigDecimal("250"), PeerLoanRepaymentKind.INTEREST, "November", null);

        assertThat(result).isSameAs(row);
        ArgumentCaptor<PeerLoanRepaymentRequest> sent = ArgumentCaptor.forClass(PeerLoanRepaymentRequest.class);
        verify(peerLoanService).addRepayment(org.mockito.ArgumentMatchers.eq(12L),
            org.mockito.ArgumentMatchers.eq(MID), sent.capture());
        assertThat(sent.getValue().paidOn()).isEqualTo(LocalDate.parse("2026-11-05"));
        assertThat(sent.getValue().amount()).isEqualByComparingTo("250");
        assertThat(sent.getValue().kind()).isEqualTo(PeerLoanRepaymentKind.INTEREST);
        assertThat(sent.getValue().note()).isEqualTo("November");
    }

    @Test
    void getPeerLoansOverview_defaultsToTwelveMonthsAndDelegates() {
        PeerLoansOverviewResponse overview = org.mockito.Mockito.mock(PeerLoansOverviewResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(peerLoanService.overview(MID, 12)).thenReturn(overview);

        assertThat(tools.getPeerLoansOverview(null)).isSameAs(overview);
    }

    @Test
    void closePeerLoan_delegatesWithTheOptionalDate() {
        PeerLoanDetailResponse detail = org.mockito.Mockito.mock(PeerLoanDetailResponse.class);
        LocalDate closedAt = LocalDate.parse("2026-11-05");
        when(userContext.currentMemberId()).thenReturn(MID);
        when(peerLoanService.close(12L, MID, closedAt)).thenReturn(detail);

        assertThat(tools.closePeerLoan(12L, closedAt)).isSameAs(detail);
    }

    @Test
    void addPeerLoanRepayment_refusesAWriteOff() {
        assertThatThrownBy(() -> tools.addPeerLoanRepayment(12L, LocalDate.now(), BigDecimal.TEN,
            PeerLoanRepaymentKind.WRITE_OFF, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("close the loan instead");

        verifyNoInteractions(peerLoanService, userContext);
    }

    @Test
    void addPeerLoanRepayment_rejectsAnInvalidAmount() {
        PeerLoanTools real = withRealValidator();

        assertThatThrownBy(() -> real.addPeerLoanRepayment(12L, LocalDate.parse("2026-11-05"),
            BigDecimal.ZERO, null, null, null))
            .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> real.addPeerLoanRepayment(12L, null, BigDecimal.TEN, null, null, null))
            .isInstanceOf(ConstraintViolationException.class);

        verifyNoInteractions(peerLoanService);
    }

    @Test
    void addPeerLoanRepayment_rejectsANoteLongerThanTheColumn() {
        PeerLoanTools real = withRealValidator();

        assertThatThrownBy(() -> real.addPeerLoanRepayment(12L, LocalDate.parse("2026-11-05"),
            BigDecimal.TEN, null, "x".repeat(256), null))
            .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void eachToolCarriesTheDocumentedScope() {
        Map<String, String> scopeByTool = Arrays.stream(PeerLoanTools.class.getDeclaredMethods())
            .filter((method) -> method.isAnnotationPresent(Tool.class))
            .collect(Collectors.toMap(
                (method) -> method.getAnnotation(Tool.class).name(),
                (method) -> method.getAnnotation(RequiresScope.class).value()));

        assertThat(scopeByTool).isEqualTo(Map.of(
            "get_peer_loan", Scopes.ACCOUNTS_READ,
            "add_peer_loan_repayment", Scopes.ACCOUNTS_WRITE,
            "get_peer_loans_overview", Scopes.ACCOUNTS_READ,
            "close_peer_loan", Scopes.ACCOUNTS_WRITE));
    }

    @Test
    void aKeyWithoutTheToolsScopeIsRejectedBeforeAnyServiceRuns() throws Exception {
        PeerLoanTools proxied = withScopeEnforcement();
        for (Method method : toolMethods()) {
            String required = method.getAnnotation(RequiresScope.class).value();
            grantEveryScopeExcept(required);

            assertThatThrownBy(() -> invoke(proxied, method))
                .as("%s without %s", method.getName(), required)
                .isInstanceOf(MissingScopeException.class)
                .hasMessageContaining(required);
        }
        verifyNoInteractions(peerLoanService, userContext);
    }

    private PeerLoanTools withRealValidator() {
        Validator real = Validation.buildDefaultValidatorFactory().getValidator();
        return new PeerLoanTools(peerLoanService, userContext, real);
    }

    private PeerLoanTools withScopeEnforcement() {
        AspectJProxyFactory factory = new AspectJProxyFactory(tools);
        factory.setProxyTargetClass(true);
        factory.addAspect(new ScopeEnforcementAspect());
        return factory.getProxy();
    }

    private static List<Method> toolMethods() {
        return Arrays.stream(PeerLoanTools.class.getDeclaredMethods())
            .filter((method) -> method.isAnnotationPresent(Tool.class))
            .toList();
    }

    private static void grantEveryScopeExcept(String scope) {
        List<SimpleGrantedAuthority> granted = Scopes.ALL.stream()
            .filter((candidate) -> !candidate.equals(scope))
            .map(SimpleGrantedAuthority::new)
            .toList();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("key", null, granted));
    }

    private static Object invoke(Object target, Method method) throws Throwable {
        try {
            return method.invoke(target, new Object[method.getParameterCount()]);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }
}
