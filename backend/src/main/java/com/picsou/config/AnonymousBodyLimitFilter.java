package com.picsou.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Caps the request body on the endpoints an anonymous client can reach. The bundled nginx refused
 * any body above 1 MB; Tomcat limits only form posts, so a few dozen concurrent 20 MB JSON posts
 * to {@code /api/setup/admin} or {@code /api/auth/login} used to exhaust the heap.
 *
 * <p>The decision uses {@code Content-Length} only, before anything reads the body: Tomcat then
 * answers and drops the connection after swallowing a few kilobytes. A body of unknown length
 * ({@code Transfer-Encoding: chunked}) is refused too, since browsers always send the length of a
 * JSON body. Authenticated endpoints keep their own limits (Spring's 10 MB multipart limit for
 * imports).
 *
 * <p>Runs before Spring Security so that even a request it would reject never gets read.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AnonymousBodyLimitFilter extends OncePerRequestFilter {

    static final int MAX_BODY_BYTES = 1024 * 1024;

    /** Prefixes of the permit-all endpoints of {@link SecurityConfig}, matched as nginx matched paths. */
    private static final List<String> ANONYMOUS_PREFIXES =
        List.of("/api/setup", "/api/auth/", "/oauth2/register");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = SpaPaths.requestPath(request);
        if (isAnonymousEndpoint(path) && bodyExceedsLimit(request)) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("application/problem+json");
            response.setHeader("Connection", "close");
            response.getWriter().write("""
                {"status":413,"title":"Payload Too Large","code":"payload_too_large"}
                """);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isAnonymousEndpoint(String path) {
        for (String prefix : ANONYMOUS_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean bodyExceedsLimit(HttpServletRequest request) {
        return request.getContentLengthLong() > MAX_BODY_BYTES
            || request.getHeader("Transfer-Encoding") != null;
    }
}
