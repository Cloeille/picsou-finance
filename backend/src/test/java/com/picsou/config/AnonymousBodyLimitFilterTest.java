package com.picsou.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * nginx capped every request body at 1 MB; Tomcat has no such cap for JSON, so an anonymous client
 * could make the JVM buffer tens of megabytes per request. The filter restores the cap, before
 * Spring Security, on the paths an anonymous client can reach.
 */
class AnonymousBodyLimitFilterTest {

    private static final int LIMIT = AnonymousBodyLimitFilter.MAX_BODY_BYTES;

    private final AnonymousBodyLimitFilter filter = new AnonymousBodyLimitFilter();

    private MockHttpServletResponse response;
    private MockFilterChain chain;

    private boolean passedThrough(String method, String path, Integer contentLength, String transferEncoding)
        throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (contentLength != null) {
            request.setContent(new byte[contentLength]);
        }
        if (transferEncoding != null) {
            request.addHeader("Transfer-Encoding", transferEncoding);
        }
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return chain.getRequest() != null;
    }

    @Test
    void oversizedBody_onAnAnonymousEndpoint_isRefusedWith413() throws Exception {
        for (String path : new String[] {"/api/setup/admin", "/api/setup", "/api/auth/login",
            "/api/auth/refresh", "/api/auth/mfa/verify", "/api/auth/activate/abc", "/oauth2/register"}) {
            assertThat(passedThrough("POST", path, LIMIT + 1, null)).as(path).isFalse();
            assertThat(response.getStatus()).as(path).isEqualTo(413);
        }
    }

    @Test
    void bodyUpToTheLimit_passes() throws Exception {
        assertThat(passedThrough("POST", "/api/auth/login", LIMIT, null)).isTrue();
        assertThat(passedThrough("POST", "/api/setup/admin", 200, null)).isTrue();
        assertThat(passedThrough("GET", "/api/setup/status", null, null)).isTrue();
    }

    @Test
    void chunkedBody_onAnAnonymousEndpoint_isRefused_asItsSizeIsUnknownUpFront() throws Exception {
        assertThat(passedThrough("POST", "/api/auth/login", null, "chunked")).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void encodedPath_isMatchedAsSpringRoutesIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/%61pi/auth/login");
        request.setServletPath("/api/auth/login");
        request.setContent(new byte[LIMIT + 1]);
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void otherPaths_keepTheirOwnLimits() throws Exception {
        // Authenticated endpoints (imports use the 10 MB multipart limit) and the SPA are not capped here.
        for (String path : new String[] {"/api/transactions/import", "/api/accounts", "/mcp", "/assets/app.js"}) {
            assertThat(passedThrough("POST", path, LIMIT + 1, null)).as(path).isTrue();
        }
    }

    @Test
    void refusal_isProblemJson_withoutEchoingTheRequest() throws Exception {
        passedThrough("POST", "/api/auth/login", LIMIT + 1, null);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"status\":413").doesNotContain("login");
    }
}
