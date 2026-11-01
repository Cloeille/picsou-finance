package com.picsou.mcp.tools;

import com.picsou.dto.AiJobStatus;
import com.picsou.mcp.tools.AiCategorizationTools.AiCategorizationProgress;
import com.picsou.mcp.tools.AiCategorizationTools.AiCategorizationProgress.State;
import com.picsou.mcp.tools.AiCategorizationTools.AiCategorizationStart;
import com.picsou.service.UserContext;
import com.picsou.service.budget.AiCategorizationJobService;
import com.picsou.exception.MissingScopeException;
import com.picsou.mcp.RequiresScope;
import com.picsou.mcp.ScopeEnforcementAspect;
import com.picsou.mcp.Scopes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiCategorizationToolsTest {

    private static final long MID = 7L;

    @Mock AiCategorizationJobService jobService;
    @Mock UserContext userContext;
    @InjectMocks AiCategorizationTools tools;

    @BeforeEach
    void member() {
        lenient().when(userContext.currentMemberId()).thenReturn(MID);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void bothToolsRequireBudgetAiWrite() {
        Map<String, String> scopeByTool = Arrays.stream(AiCategorizationTools.class.getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(Tool.class))
            .collect(Collectors.toMap(m -> m.getAnnotation(Tool.class).name(),
                m -> m.getAnnotation(RequiresScope.class).value()));

        assertThat(scopeByTool).isEqualTo(Map.of(
            "start_ai_categorization", "budget:ai-write",
            "get_ai_categorization_status", "budget:ai-write"));
    }

    @Test
    void aKeyWithEveryOtherScopeCannotStartOrReadTheJob() {
        List<SimpleGrantedAuthority> granted = Scopes.ALL.stream()
            .filter(s -> !s.equals(Scopes.BUDGET_AI_WRITE))
            .map(SimpleGrantedAuthority::new)
            .toList();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("key", null, granted));
        AspectJProxyFactory factory = new AspectJProxyFactory(tools);
        factory.setProxyTargetClass(true);
        factory.addAspect(new ScopeEnforcementAspect());
        AiCategorizationTools proxied = factory.getProxy();

        assertThatThrownBy(proxied::startAiCategorization)
            .isInstanceOf(MissingScopeException.class).hasMessageContaining("budget:ai-write");
        assertThatThrownBy(proxied::getAiCategorizationStatus)
            .isInstanceOf(MissingScopeException.class).hasMessageContaining("budget:ai-write");
        verifyNoInteractions(jobService);
    }

    @Test
    void status_neverReportsNegativeUnresolvedWhileCountersCatchUp() {
        when(jobService.status(MID)).thenReturn(new AiJobStatus(true, 10, 4, 3, 2, false, null));

        assertThat(tools.getAiCategorizationStatus().unresolved()).isZero();
    }

    @Test
    void start_whenIdle_startsAndEchoesTotal() {
        when(jobService.status(MID)).thenReturn(new AiJobStatus(false, 0, 0, 0, 0, false, null));
        when(jobService.start(MID)).thenReturn(new AiJobStatus(true, 42, 0, 0, 0, false, null));

        AiCategorizationStart result = tools.startAiCategorization();

        assertThat(result.started()).isTrue();
        assertThat(result.job()).isEqualTo(new AiCategorizationProgress(State.RUNNING, 42, 0, 0, 0, 0));
    }

    @Test
    void start_whenAlreadyRunning_returnsRunningJobWithoutStartingAnother() {
        AiJobStatus running = new AiJobStatus(true, 42, 10, 6, 3, false, null);
        when(jobService.status(MID)).thenReturn(running);
        when(jobService.start(MID)).thenReturn(running);

        AiCategorizationStart result = tools.startAiCategorization();

        assertThat(result.started()).isFalse();
        assertThat(result.job()).isEqualTo(new AiCategorizationProgress(State.RUNNING, 42, 10, 6, 3, 1));
        verify(jobService, times(1)).start(MID);
    }

    @Test
    void start_whenAiDisabledOrNothingToDo_reportsCompletedWithZeroTotal() {
        when(jobService.status(MID)).thenReturn(new AiJobStatus(false, 0, 0, 0, 0, false, null));
        when(jobService.start(MID)).thenReturn(new AiJobStatus(false, 0, 0, 0, 0, true, null));

        AiCategorizationStart result = tools.startAiCategorization();

        assertThat(result.started()).isFalse();
        assertThat(result.job()).isEqualTo(new AiCategorizationProgress(State.COMPLETED, 0, 0, 0, 0, 0));
    }

    @Test
    void status_whenIdle_isIdleWithZeros() {
        when(jobService.status(MID)).thenReturn(new AiJobStatus(false, 0, 0, 0, 0, false, null));

        assertThat(tools.getAiCategorizationStatus())
            .isEqualTo(new AiCategorizationProgress(State.IDLE, 0, 0, 0, 0, 0));
    }

    @Test
    void status_whenFailed_isFailedAndCarriesNoErrorText() {
        when(jobService.status(MID)).thenReturn(new AiJobStatus(false, 10, 4, 2, 1, true, "boom: Carrefour 42.10"));

        AiCategorizationProgress result = tools.getAiCategorizationStatus();

        assertThat(result).isEqualTo(new AiCategorizationProgress(State.FAILED, 10, 4, 2, 1, 1));
    }

    @Test
    void status_whenCompleted_derivesUnresolved() {
        when(jobService.status(MID)).thenReturn(new AiJobStatus(false, 10, 10, 6, 3, true, null));

        AiCategorizationProgress result = tools.getAiCategorizationStatus();

        assertThat(result).isEqualTo(new AiCategorizationProgress(State.COMPLETED, 10, 10, 6, 3, 1));
        assertThat(result.unresolved()).isEqualTo(1);
    }

    @Test
    void tools_passTheCurrentMemberIdToTheService() {
        when(jobService.status(MID)).thenReturn(new AiJobStatus(false, 0, 0, 0, 0, false, null));

        tools.getAiCategorizationStatus();

        verify(userContext).currentMemberId();
        verify(jobService).status(MID);
    }
}
