package com.picsou.config;

import com.picsou.service.SetupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SetupFilterTest {

    @Mock SetupService setupService;

    SetupFilter filter;

    @BeforeEach
    void setUp() {
        filter = new SetupFilter(setupService);
        when(setupService.isComplete()).thenReturn(false);
    }

    private MockHttpServletResponse response;
    private MockFilterChain chain;

    private boolean passedThrough(String path) throws Exception {
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", path), response, chain);
        return chain.getRequest() != null;
    }

    @Test
    void spaPaths_passWhileSetupIsIncomplete() throws Exception {
        for (String path : new String[] {"/", "/index.html", "/assets/app-abc.js", "/setup/admin",
            "/accounts/12", "/consent", "/.well-known/security.txt"}) {
            assertThat(passedThrough(path)).as(path).isTrue();
        }
    }

    @Test
    void backendPaths_stillAnswer503WhileSetupIsIncomplete() throws Exception {
        for (String path : new String[] {"/api/accounts", "/mcp", "/oauth2/jwks",
            "/.well-known/oauth-authorization-server"}) {
            assertThat(passedThrough(path)).as(path).isFalse();
            assertThat(response.getStatus()).as(path).isEqualTo(503);
        }
    }

    @Test
    void encodedSetupPath_isMatchedOnTheDecodedServletPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/%61pi/setup/status");
        request.setServletPath("/api/setup/status");
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void setupAndHealthPaths_stayAllowed() throws Exception {
        for (String path : new String[] {"/api/setup/status", "/actuator/health", "/actuator/info"}) {
            assertThat(passedThrough(path)).as(path).isTrue();
        }
    }
}
