package com.picsou.service.sso;

import com.picsou.config.SsoProperties;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.UserIdentity;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.UserIdentityRepository;
import com.picsou.service.ActivationService;
import com.picsou.service.MfaService;
import com.picsou.service.SetupAuditService;
import com.picsou.service.SetupService;
import com.picsou.service.UsernameDeriver;
import com.picsou.util.LogSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Resolves, provisions and links SSO identities. An account is bound by {@code (issuer, subject)}
 * only, never by email or username: those are IdP-controlled and a match would be an account takeover.
 */
@Service
public class SsoAccountService {

    private static final Logger log = LoggerFactory.getLogger(SsoAccountService.class);
    private static final String NOT_LINKED = "NOT_LINKED";
    private static final String DEFAULT_DISPLAY_NAME = "SSO user";
    private static final int MAX_MEMBER_NAME = 100;
    private static final int MAX_IDENTITY_NAME = 255;
    private static final int MAX_EMAIL = 320;

    public sealed interface LoginOutcome {
        record Resolved(AppUser user, boolean provisioned) implements LoginOutcome {}

        record Refused(String code) implements LoginOutcome {}
    }

    private final UserIdentityRepository identityRepository;
    private final AppUserRepository userRepository;
    private final FamilyMemberRepository memberRepository;
    private final SetupService setupService;
    private final SetupAuditService auditService;
    private final UsernameDeriver usernameDeriver;
    private final SsoProperties ssoProperties;
    private final Clock clock;
    private final ActivationService activationService;
    private final MfaService mfaService;

    public SsoAccountService(UserIdentityRepository identityRepository,
                             AppUserRepository userRepository,
                             FamilyMemberRepository memberRepository,
                             SetupService setupService,
                             SetupAuditService auditService,
                             UsernameDeriver usernameDeriver,
                             SsoProperties ssoProperties,
                             Clock clock,
                             ActivationService activationService,
                             MfaService mfaService) {
        this.identityRepository = identityRepository;
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.setupService = setupService;
        this.auditService = auditService;
        this.usernameDeriver = usernameDeriver;
        this.ssoProperties = ssoProperties;
        this.clock = clock;
        this.activationService = activationService;
        this.mfaService = mfaService;
    }

    @Transactional
    public LoginOutcome resolveLogin(SsoIdentity identity, HttpServletRequest request) {
        var linked = identityRepository.findByIssuerAndSubject(identity.issuer(), identity.subject());
        if (linked.isPresent()) {
            UserIdentity row = linked.get();
            AppUser user = userRepository.findByIdWithMember(row.getUser().getId()).orElseThrow();
            touchLogin(identity);
            return new LoginOutcome.Resolved(user, false);
        }
        if (ssoProperties.adminGroupManaged()
            && identity.groups().filter(g -> g.contains(ssoProperties.adminGroup())).isPresent()
            && setupService.isComplete()) {
            return new LoginOutcome.Resolved(provision(identity, request), true);
        }
        return new LoginOutcome.Refused(NOT_LINKED);
    }

    private AppUser provision(SsoIdentity identity, HttpServletRequest request) {
        FamilyMember member = FamilyMember.builder()
            .displayName(displayNameFor(identity))
            .managed(false)
            .build();
        memberRepository.save(member);
        AppUser user = AppUser.builder()
            .username(usernameFor(identity))
            .passwordHash("")
            .member(member)
            .role(UserRole.ADMIN)
            .activated(true)
            .acknowledgedWarning(true)
            .build();
        userRepository.save(user);
        identityRepository.save(UserIdentity.builder()
            .user(user)
            .issuer(identity.issuer())
            .subject(identity.subject())
            .email(truncate(identity.email(), MAX_EMAIL))
            .displayName(truncate(identity.displayName(), MAX_IDENTITY_NAME))
            .lastLoginAt(clock.instant())
            .build());
        auditService.record("sso.user.provisioned", user.getUsername(), request, "role=ADMIN");
        log.info("sso.user.provisioned username={}", LogSanitizer.safe(user.getUsername()));
        return user;
    }

    public String displayNameFor(SsoIdentity identity) {
        String name = firstNonBlank(identity.displayName(), identity.preferredUsername(), DEFAULT_DISPLAY_NAME);
        return truncate(name, MAX_MEMBER_NAME);
    }

    public String usernameFor(SsoIdentity identity) {
        String preferred = identity.preferredUsername();
        if (preferred == null || preferred.isBlank()) {
            return usernameDeriver.derive(displayNameFor(identity));
        }
        int at = preferred.indexOf('@');
        return usernameDeriver.derive(at >= 0 ? preferred.substring(0, at) : preferred);
    }

    @Transactional
    public AppUser link(Long userId, SsoIdentity identity) {
        var existing = identityRepository.findByIssuerAndSubject(identity.issuer(), identity.subject());
        if (existing.isPresent()) {
            UserIdentity row = existing.get();
            if (!row.getUser().getId().equals(userId)) {
                throw new SsoFlowException("IDENTITY_IN_USE");
            }
            refresh(row, identity);
            return userRepository.findByIdWithMember(userId).orElseThrow();
        }
        AppUser user = userRepository.findByIdWithMember(userId).orElseThrow();
        identityRepository.save(UserIdentity.builder()
            .user(user)
            .issuer(identity.issuer())
            .subject(identity.subject())
            .email(truncate(identity.email(), MAX_EMAIL))
            .displayName(truncate(identity.displayName(), MAX_IDENTITY_NAME))
            .build());
        return user;
    }

    /**
     * Activates an invited account and binds the IdP identity to it in one transaction, so a failure
     * never leaves an activated account without a sign-in method. The account is found by its
     * invitation token only, never by the identity's email or username, and its row stays locked
     * until commit: a concurrent activation then finds the token gone instead of activating twice.
     */
    @Transactional
    public AppUser activateWithIdentity(String token, SsoIdentity identity, HttpServletRequest request) {
        AppUser user = userRepository.findByActivationTokenForUpdate(token == null ? "" : token)
            .filter(invited -> invited.getActivationTokenExpires() == null
                || !invited.getActivationTokenExpires().isBefore(clock.instant()))
            .orElseThrow(() -> new SsoFlowException("ACTIVATION_INVALID"));
        identityRepository.findByIssuerAndSubject(identity.issuer(), identity.subject())
            .filter(row -> !row.getUser().getId().equals(user.getId()))
            .ifPresent(row -> {
                throw new SsoFlowException("IDENTITY_IN_USE");
            });
        activationService.complete(user, "", request);
        if (mfaService.isEnabled(user)) {
            // Picsou's TOTP only protects a password, and an SSO-activated account has none.
            mfaService.disable(user);
        }
        link(user.getId(), identity);
        touchLogin(identity);
        return user;
    }

    @Transactional(readOnly = true)
    public boolean isLinkedTo(SsoIdentity identity, Long userId) {
        return identityRepository.findByIssuerAndSubject(identity.issuer(), identity.subject())
            .filter(row -> row.getUser().getId().equals(userId)).isPresent();
    }

    @Transactional
    public void touchLogin(SsoIdentity identity) {
        identityRepository.findByIssuerAndSubject(identity.issuer(), identity.subject()).ifPresent(row -> {
            row.setLastLoginAt(clock.instant());
            refresh(row, identity);
        });
    }

    private static void refresh(UserIdentity row, SsoIdentity identity) {
        row.setEmail(truncate(identity.email(), MAX_EMAIL));
        row.setDisplayName(truncate(identity.displayName(), MAX_IDENTITY_NAME));
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return DEFAULT_DISPLAY_NAME;
    }

    public static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
