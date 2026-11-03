package com.picsou.config;

import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import com.picsou.service.SessionIssuer;
import com.picsou.service.ReAuthService;
import com.picsou.service.SetupAuditService;
import com.picsou.service.SetupService;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.transaction.TransactionException;
import jakarta.servlet.http.Cookie;
import org.springframework.web.util.WebUtils;
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
    private final JwtTokenAuthenticator authenticator;
    private final ReAuthService reAuthService;
    private final JwtUtil jwtUtil;
    private final AuthCookieWriter cookieWriter;
    private final SetupAuditService auditService;
    private final SetupService setupService;

    public SsoAuthenticationSuccessHandler(SsoAccountService ssoAccountService,
                                           SsoRoleSyncService ssoRoleSyncService,
                                           SessionIssuer sessionIssuer,
                                           SsoProperties ssoProperties,
                                           Clock clock,
                                           @Value("${app.admin-recovery.enabled:false}") boolean adminRecoveryEnabled,
                                           JwtTokenAuthenticator authenticator, ReAuthService reAuthService,
                                           JwtUtil jwtUtil, AuthCookieWriter cookieWriter, SetupAuditService auditService,
                                           SetupService setupService) {
        this.ssoAccountService = ssoAccountService;
        this.ssoRoleSyncService = ssoRoleSyncService;
        this.sessionIssuer = sessionIssuer;
        this.ssoProperties = ssoProperties;
        this.clock = clock;
        this.adminRecoveryEnabled = adminRecoveryEnabled;
        this.authenticator = authenticator;
        this.reAuthService = reAuthService;
        this.jwtUtil = jwtUtil;
        this.cookieWriter = cookieWriter;
        this.auditService = auditService;
        this.setupService = setupService;
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
                case REAUTH, LINK -> signedInIntent(request, response, authentication, intent);
                case ACTIVATE -> activate(request, response, authentication, intent);
                case SETUP -> setup(request, response, authentication, intent);
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

    private String activate(HttpServletRequest request, HttpServletResponse response,
                            Authentication authentication, SsoIntent intent) {
        SsoIdentity identity = SsoIdentity.from((OidcUser) authentication.getPrincipal(), ssoProperties.groupsClaim());
        AppUser user = ssoAccountService.activateWithIdentity(intent.activationToken(), identity, request);
        ssoRoleSyncService.sync(user, identity.groups(), request);
        sessionIssuer.issue(user, intent.rememberMe(), false, clock.instant(), request, response);
        if (!intent.rememberMe()) {
            // Activation revoked every persistent series of the account, so drop this browser's stale cookie.
            cookieWriter.clearPersistent(response);
        }
        log.info("SSO activation user={}", LogSanitizer.safe(user.getUsername()));
        return COMPLETE_TARGET + URLEncoder.encode(intent.redirect(), StandardCharsets.UTF_8);
    }

    private String setup(HttpServletRequest request, HttpServletResponse response,
                         Authentication authentication, SsoIntent intent) {
        SsoIdentity identity = SsoIdentity.from((OidcUser) authentication.getPrincipal(), ssoProperties.groupsClaim());
        if (identity.groups().filter(groups -> groups.contains(ssoProperties.adminGroup())).isEmpty()) {
            return intent.errorRedirect("NOT_IN_ADMIN_GROUP");
        }
        AppUser user;
        try {
            user = setupService.seedSsoAdmin(identity,
                ssoAccountService.usernameFor(identity), ssoAccountService.displayNameFor(identity));
        } catch (IllegalStateException | ConcurrencyFailureException | TransactionException e) {
            return intent.errorRedirect("SETUP_ALREADY_CLAIMED");
        }
        auditService.record("setup.admin.created", user.getUsername(), request, "method=sso");
        sessionIssuer.issue(user, false, false, clock.instant(), request, response);
        return "/setup/security";
    }

    private String signedInIntent(HttpServletRequest request, HttpServletResponse response,
                                  Authentication authentication, SsoIntent intent) {
        Cookie access = WebUtils.getCookie(request, AuthCookieWriter.ACCESS_COOKIE);
        AppUser user = access == null ? null : authenticator.authenticate(access.getValue())
            .map(local -> (AppUser) local.getPrincipal()).orElse(null);
        if (user == null) {
            return intent.errorRedirect("SESSION_EXPIRED");
        }
        if (!user.getId().equals(intent.userId())) {
            return intent.errorRedirect("REAUTH_WRONG_ACCOUNT");
        }
        SsoIdentity identity = SsoIdentity.from((OidcUser) authentication.getPrincipal(), ssoProperties.groupsClaim());
        return switch (intent.kind()) {
            case REAUTH -> {
                if (!ssoAccountService.isLinkedTo(identity, intent.userId())) {
                    yield intent.errorRedirect("REAUTH_WRONG_ACCOUNT");
                }
                if (identity.authTime() == null || identity.authTime().isBefore(intent.startedAt().minusSeconds(60))) {
                    yield intent.errorRedirect("REAUTH_TOO_OLD");
                }
                ssoAccountService.touchLogin(identity);
                ssoRoleSyncService.sync(user, identity.groups(), request);
                cookieWriter.setReauthProof(response, jwtUtil.generateReauthProof(user));
                yield intent.redirect();
            }
            case LINK -> {
                if (!reAuthService.hasValidProof(user, request)) {
                    yield intent.errorRedirect("REAUTH_REQUIRED");
                }
                AppUser linked = ssoAccountService.link(intent.userId(), identity);
                ssoRoleSyncService.sync(linked, identity.groups(), request);
                auditService.record("sso.identity.linked", linked.getUsername(), request, "");
                yield intent.redirect() + (intent.redirect().contains("?") ? "&" : "?") + "sso=linked";
            }
            case LOGIN, ACTIVATE, SETUP -> intent.errorRedirect("IDP_ERROR");
        };
    }

    private String inactiveCode(AppUser user) {
        return adminRecoveryEnabled && user.getRole() == UserRole.ADMIN ? "ADMIN_RECOVERY_ACTIVE" : "ACCOUNT_INACTIVE";
    }
}
