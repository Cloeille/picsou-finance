package com.picsou.config;

import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import com.picsou.service.SessionIssuer;
import com.picsou.service.sso.SsoAccountService;
import com.picsou.service.sso.SsoFlowException;
import com.picsou.service.sso.SsoIdentity;
import com.picsou.service.sso.SsoIntent;
import com.picsou.service.sso.SsoRoleSyncService;
import com.picsou.util.LogSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Completes a successful SSO handshake. The IdP only authenticates (S1): no IdP token is kept and
 * no SecurityContext is written; the caller gets Picsou's own cookies from {@link SessionIssuer}.
 * The handshake session is always discarded (S3) and an SSO-born series never counts as a trusted
 * device for a later password login (S11), so {@code trustedFor2fa} is always {@code false}.
 */
@Component
@Conditional(SsoEnabledCondition.class)
public class SsoAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(SsoAuthenticationSuccessHandler.class);
    private static final String OAUTH2_TARGET_PREFIX = "/oauth2/";
    private static final String COMPLETE_TARGET = "/login/sso-complete?redirect=";

    private final SsoAccountService ssoAccountService;
    private final SsoRoleSyncService ssoRoleSyncService;
    private final SessionIssuer sessionIssuer;
    private final SsoProperties ssoProperties;
    private final Clock clock;
    private final boolean adminRecoveryEnabled;

    public SsoAuthenticationSuccessHandler(SsoAccountService ssoAccountService,
                                           SsoRoleSyncService ssoRoleSyncService,
                                           SessionIssuer sessionIssuer,
                                           SsoProperties ssoProperties,
                                           Clock clock,
                                           @Value("${app.admin-recovery.enabled:false}") boolean adminRecoveryEnabled) {
        this.ssoAccountService = ssoAccountService;
        this.ssoRoleSyncService = ssoRoleSyncService;
        this.sessionIssuer = sessionIssuer;
        this.ssoProperties = ssoProperties;
        this.clock = clock;
        this.adminRecoveryEnabled = adminRecoveryEnabled;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException {
        SsoIntent intent = SsoAuthorizationRequestRepository.removedRequest(request)
            .map(authorization -> authorization.<SsoIntent>getAttribute(SsoIntent.ATTRIBUTE))
            .orElse(null);
        String target;
        try {
            target = switch (intent == null ? null : intent.kind()) {
                case LOGIN -> login(request, response, authentication, intent);
                case LINK, REAUTH, ACTIVATE, SETUP -> intent.errorRedirect("IDP_ERROR");
                case null -> SsoIntent.loginError("SESSION_EXPIRED");
            };
        } catch (SsoFlowException e) {
            target = intent == null ? SsoIntent.loginError(e.code()) : intent.errorRedirect(e.code());
        } finally {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        }
        response.sendRedirect(target);
    }

    private String login(HttpServletRequest request, HttpServletResponse response,
                         Authentication authentication, SsoIntent intent) {
        SsoIdentity identity = SsoIdentity.from((OidcUser) authentication.getPrincipal(), ssoProperties.groupsClaim());
        return switch (ssoAccountService.resolveLogin(identity, request)) {
            case SsoAccountService.LoginOutcome.Refused refused -> intent.errorRedirect(refused.code());
            case SsoAccountService.LoginOutcome.Resolved resolved -> {
                AppUser user = resolved.user();
                if (!user.isActivated()) {
                    // Mirrors AuthController.login: the admin-recovery message only for a
                    // deactivated admin while break-glass recovery is on.
                    yield intent.errorRedirect(inactiveCode(user));
                }
                ssoRoleSyncService.sync(user, identity.groups(), request);
                sessionIssuer.issue(user, intent.rememberMe(), false, clock.instant(), request, response);
                log.info("SSO login user={} provisioned={}", LogSanitizer.safe(user.getUsername()), resolved.provisioned());
                String redirect = intent.redirect() == null ? "/" : intent.redirect();
                yield redirect.startsWith(OAUTH2_TARGET_PREFIX) ? redirect
                    : COMPLETE_TARGET + URLEncoder.encode(redirect, StandardCharsets.UTF_8);
            }
        };
    }

    private String inactiveCode(AppUser user) {
        return adminRecoveryEnabled && user.getRole() == UserRole.ADMIN ? "ADMIN_RECOVERY_ACTIVE" : "ACCOUNT_INACTIVE";
    }
}
