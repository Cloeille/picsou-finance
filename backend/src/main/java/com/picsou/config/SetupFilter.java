package com.picsou.config;

import com.picsou.service.SetupService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Blocks every request that is not part of the setup wizard while setup is not
 * yet {@code COMPLETE}. On a completed install this filter is a fast-path
 * no-op (cached in-memory flag flipped to true at startup after the first
 * successful status lookup).
 */
@Component
public class SetupFilter extends OncePerRequestFilter {

    private final SetupService setupService;
    private volatile boolean cachedComplete;

    public SetupFilter(SetupService setupService) {
        this.setupService = setupService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        if (cachedComplete || setupService.isComplete()) {
            cachedComplete = true;
            chain.doFilter(request, response);
            return;
        }

        String path = SpaPaths.requestPath(request);
        if (isAllowedDuringSetup(path) || !SpaPaths.isBackend(path)) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/problem+json");
        response.getWriter().write("""
            {"status":503,"title":"Setup Required","code":"setup_required",\
"detail":"Picsou is not configured yet. Complete the setup wizard at /setup."}
            """);
    }

    private boolean isAllowedDuringSetup(String path) {
        return path.startsWith("/api/setup/")
            || path.equals("/api/setup")
            || path.equals("/actuator/health")
            || path.equals("/actuator/info")
            || path.equals("/error");
    }
}
