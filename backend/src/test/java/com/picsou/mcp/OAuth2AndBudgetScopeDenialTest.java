package com.picsou.mcp;

import com.picsou.exception.MissingScopeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Denial coverage for every OAuth2 + Budget scope (13 total), mirroring
 * {@link ScopeEnforcementAspectTest}: a key that lacks the exact required scope must never reach
 * the tool body, regardless of which other scopes it holds.
 */
class OAuth2AndBudgetScopeDenialTest {

    private final ScopeEnforcementAspect aspect = new ScopeEnforcementAspect();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private RequiresScope requiring(String scope) {
        RequiresScope ann = mock(RequiresScope.class);
        when(ann.value()).thenReturn(scope);
        return ann;
    }

    @ParameterizedTest
    @ValueSource(strings = {
        Scopes.OAUTH2_DISCOVER, Scopes.OAUTH2_SESSION_STATUS,
        Scopes.BUDGET_CATEGORIES_READ, Scopes.BUDGET_CATEGORIES_WRITE,
        Scopes.BUDGET_RULES_READ, Scopes.BUDGET_RULES_WRITE,
        Scopes.BUDGET_TRANSACTIONS_READ, Scopes.BUDGET_TRANSACTIONS_WRITE,
        Scopes.BUDGET_RECURRING_READ, Scopes.BUDGET_RECURRING_WRITE,
        Scopes.BUDGET_ENVELOPES_READ, Scopes.BUDGET_ENVELOPES_WRITE,
        Scopes.BUDGET_DASHBOARD_READ
    })
    void denies_whenExactlyThisScopeIsMissing(String requiredScope) {
        // Holds every OTHER new scope, but never the one under test.
        List<String> granted = Scopes.ALL.stream().filter(s -> !s.equals(requiredScope)).toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "key", null, granted.stream().map(SimpleGrantedAuthority::new).toList()));

        assertThatThrownBy(() -> aspect.enforce(requiring(requiredScope)))
            .isInstanceOf(MissingScopeException.class)
            .hasMessageContaining(requiredScope);
    }
}
