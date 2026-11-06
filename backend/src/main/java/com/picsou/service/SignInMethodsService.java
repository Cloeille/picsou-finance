package com.picsou.service;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.SsoProperties;
import com.picsou.dto.LinkedIdentityResponse;
import com.picsou.dto.SignInMethodsResponse;
import com.picsou.dto.SsoInfoResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SignInMethodException;
import com.picsou.model.AppUser;
import com.picsou.model.UserIdentity;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.UserIdentityRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Builds the SSO info and the caller's sign-in methods (password, MFA, linked identities). */
@Service
public class SignInMethodsService {

    private final UserIdentityRepository identityRepository;
    private final MfaService mfaService;
    private final SsoProperties ssoProperties;
    private final ReAuthService reAuthService;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PersistentSessionService persistentSessionService;
    private final SessionIssuer sessionIssuer;
    private final AuthCookieWriter cookieWriter;
    private final SetupAuditService auditService;

    public SignInMethodsService(UserIdentityRepository identityRepository, MfaService mfaService,
                                SsoProperties ssoProperties, ReAuthService reAuthService,
                                AppUserRepository userRepository, PasswordEncoder passwordEncoder,
                                PersistentSessionService persistentSessionService,
                                SessionIssuer sessionIssuer, AuthCookieWriter cookieWriter,
                                SetupAuditService auditService) {
        this.identityRepository = identityRepository;
        this.mfaService = mfaService;
        this.ssoProperties = ssoProperties;
        this.reAuthService = reAuthService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.persistentSessionService = persistentSessionService;
        this.sessionIssuer = sessionIssuer;
        this.cookieWriter = cookieWriter;
        this.auditService = auditService;
    }

    /** Configuration only: never a network call. */
    public SsoInfoResponse ssoInfo() {
        return new SsoInfoResponse(ssoProperties.enabled(), ssoProperties.providerName());
    }

    @Transactional(readOnly = true)
    public SignInMethodsResponse signInMethods(AppUser user) {
        String currentIssuer = ssoProperties.issuerUri();
        List<LinkedIdentityResponse> identities = identityRepository
            .findByUserIdOrderByCreatedAtAsc(user.getId()).stream()
            .map(identity -> toResponse(identity, currentIssuer))
            .toList();
        return new SignInMethodsResponse(
            hasPassword(user),
            mfaService.isEnabled(user),
            identities,
            ssoProperties.enabled(),
            ssoProperties.providerName(),
            ssoProperties.adminGroupManaged());
    }

    /**
     * Turns password login off. Guarded so it can never lock the account out: SSO must be usable
     * now, and an identity of the CURRENT issuer must already have signed in (an old issuer's
     * identity can never sign in again).
     */
    @Transactional
    public void removePassword(AppUser caller, HttpServletRequest request, HttpServletResponse response) {
        AppUser user = userRepository.findByIdForUpdate(caller.getId())
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        reAuthService.requireProof(user, request);
        if (!ssoProperties.enabled()) {
            throw new SignInMethodException(HttpStatus.CONFLICT, "SSO_DISABLED",
                "Single sign-on is not available, so password login cannot be turned off");
        }
        String issuer = ssoProperties.issuerUri();
        if (!identityRepository.existsByUserIdAndIssuerAndLastLoginAtIsNotNull(user.getId(), issuer)) {
            throw new SignInMethodException(HttpStatus.CONFLICT, "IDENTITY_NOT_USED_YET",
                "Sign in once with single sign-on before turning password login off");
        }
        if (!hasPassword(user)) {
            return;
        }
        user.setPasswordHash("");
        if (mfaService.isEnabled(user)) {
            mfaService.disable(user);
        }
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
        persistentSessionService.revokeAllForUser(user.getId());
        sessionIssuer.issue(user, false, false,
            identityRepository.findLatestLoginAt(user.getId(), issuer), request, response);
        // Every series was just revoked; a surviving persistent cookie would answer the next refresh "Session revoked".
        cookieWriter.clearPersistent(response);
        auditService.record("auth.password.removed", user.getUsername(), request, null);
    }

    @Transactional
    public void setPassword(AppUser caller, String newPassword, HttpServletRequest request,
                            HttpServletResponse response) {
        AppUser user = userRepository.findByIdForUpdate(caller.getId())
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        reAuthService.requireProof(user, request);
        if (hasPassword(user)) {
            throw new SignInMethodException(HttpStatus.CONFLICT, "PASSWORD_ALREADY_SET",
                "This account already has a password");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
        persistentSessionService.revokeAllForUser(user.getId());
        sessionIssuer.issue(user, false, false, sessionIssuer.currentSsoAt(request), request, response);
        cookieWriter.clearPersistent(response);
        auditService.record("auth.password.set", user.getUsername(), request, null);
    }

    @Transactional
    public void unlinkIdentity(AppUser caller, Long identityId, HttpServletRequest request) {
        AppUser user = userRepository.findByIdForUpdate(caller.getId())
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        reAuthService.requireProof(user, request);
        UserIdentity identity = identityRepository.findByIdAndUserId(identityId, user.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Identity not found"));
        if (!hasPassword(user)) {
            String issuer = ssoProperties.issuerUri();
            long currentIssuerLeft = identityRepository.countByUserIdAndIssuer(user.getId(), issuer)
                - (identity.getIssuer().equals(issuer) ? 1 : 0);
            if (currentIssuerLeft <= 0) {
                throw new SignInMethodException(HttpStatus.CONFLICT, "LAST_SIGN_IN_METHOD",
                    "Set a password before removing your last single sign-on identity");
            }
        }
        identityRepository.delete(identity);
        auditService.record("sso.identity.unlinked", user.getUsername(), request, "identityId=" + identityId);
    }

    private static boolean hasPassword(AppUser user) {
        return user.getPasswordHash() != null && !user.getPasswordHash().isBlank();
    }

    private static LinkedIdentityResponse toResponse(UserIdentity identity, String currentIssuer) {
        return new LinkedIdentityResponse(
            identity.getId(), identity.getEmail(), identity.getDisplayName(),
            identity.getCreatedAt(), identity.getLastLoginAt(),
            identity.getIssuer().equals(currentIssuer));
    }
}
