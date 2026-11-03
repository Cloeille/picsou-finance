package com.picsou.config;

import com.picsou.service.SetupService;
import com.picsou.service.ReAuthService;
import com.picsou.model.AppUser;
import com.picsou.repository.AppUserRepository;
import jakarta.servlet.http.Cookie;
import org.springframework.web.util.WebUtils;
import com.picsou.service.sso.SsoIntent;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

public class SsoStartGuardFilter extends OncePerRequestFilter {
    private final SsoClientRegistrationRepository repository;
    private final SetupService setupService;
    private final Map<String, Bucket> buckets;
    private final Clock clock;
    private final JwtTokenAuthenticator authenticator;
    private final ReAuthService reAuthService;
    private final AppUserRepository userRepository;

    public SsoStartGuardFilter(SsoClientRegistrationRepository repository, SetupService setupService,
                               Map<String, Bucket> buckets, Clock clock,
                               JwtTokenAuthenticator authenticator, ReAuthService reAuthService,
                               AppUserRepository userRepository) {
        this.repository = repository;
        this.setupService = setupService;
        this.buckets = buckets;
        this.clock = clock;
        this.authenticator = authenticator;
        this.reAuthService = reAuthService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!"POST".equals(request.getMethod())) {
            response.sendRedirect("/login");
            return;
        }
        if (path.startsWith("/oauth2/sso/code/")) {
            chain.doFilter(request, response);
            return;
        }
        if (!path.equals("/oauth2/sso/authorization/" + SsoClientRegistrationRepository.REGISTRATION_ID)) {
            response.sendRedirect("/login");
            return;
        }
        if (!buckets.computeIfAbsent(ClientIp.resolve(request), key -> RateLimitConfig.createSsoStartBucket()).tryConsume(1)) {
            response.sendRedirect(SsoIntent.loginError("RATE_LIMITED"));
            return;
        }
        String rawKind = request.getParameter("intent");
        SsoIntent.Kind kind = switch (rawKind == null ? "login" : rawKind) {
            case "login" -> SsoIntent.Kind.LOGIN;
            case "link" -> SsoIntent.Kind.LINK;
            case "reauth" -> SsoIntent.Kind.REAUTH;
            case "activate" -> SsoIntent.Kind.ACTIVATE;
            case "setup" -> SsoIntent.Kind.SETUP;
            default -> null;
        };
        if (kind == null) {
            response.sendRedirect(SsoIntent.loginError("IDP_ERROR"));
            return;
        }
        String fallback = switch (kind) {
            case LOGIN, ACTIVATE -> "/";
            case LINK, REAUTH -> "/settings";
            case SETUP -> "/setup/admin";
        };
        String activationToken = switch (kind) {
            case ACTIVATE -> request.getParameter("token") == null ? "" : request.getParameter("token");
            case LOGIN, LINK, REAUTH, SETUP -> null;
        };
        SsoIntent intent = new SsoIntent(kind, SafeRedirect.sanitize(request.getParameter("redirect"), fallback),
            "1".equals(request.getParameter("rememberMe")), null, activationToken, clock.instant());
        if (!repository.isAvailable()) {
            response.sendRedirect(intent.errorRedirect("IDP_UNAVAILABLE"));
            return;
        }
        switch (kind) {
            case LOGIN -> {
                if (!setupService.isComplete()) {
                    response.sendRedirect("/setup");
                    return;
                }
            }
            case LINK, REAUTH -> {
                Cookie access = WebUtils.getCookie(request, AuthCookieWriter.ACCESS_COOKIE);
                AppUser user = access == null ? null : authenticator.authenticate(access.getValue())
                    .map(authentication -> (AppUser) authentication.getPrincipal()).orElse(null);
                if (user == null) {
                    response.sendRedirect("/login");
                    return;
                }
                intent = new SsoIntent(kind, intent.redirect(), intent.rememberMe(), user.getId(), null, intent.startedAt());
                boolean proofRequired = switch (kind) {
                    case LINK -> true;
                    case REAUTH -> false;
                    case LOGIN, ACTIVATE, SETUP -> throw new IllegalStateException("Not a signed-in intent");
                };
                if (proofRequired && !reAuthService.hasValidProof(user, request)) {
                    response.sendRedirect(intent.errorRedirect("REAUTH_REQUIRED"));
                    return;
                }
            }
            case ACTIVATE -> {
                boolean valid = "1".equals(request.getParameter("ack"))
                    && userRepository.findByActivationToken(intent.activationToken())
                        .filter(invited -> invited.getActivationTokenExpires() == null
                            || !invited.getActivationTokenExpires().isBefore(clock.instant()))
                        .isPresent();
                if (!valid) {
                    response.sendRedirect(intent.errorRedirect("ACTIVATION_INVALID"));
                    return;
                }
            }
            case SETUP -> {
                response.sendRedirect(intent.errorRedirect("IDP_ERROR"));
                return;
            }
        }
        request.setAttribute(SsoIntent.ATTRIBUTE, intent);
        chain.doFilter(request, response);
    }
}
