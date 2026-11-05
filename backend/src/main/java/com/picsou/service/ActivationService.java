package com.picsou.service;

import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * The shared sink of new-member activation, admin-initiated password reset and admin-recovery
 * completion. The password and SSO activation paths both end here so they cannot drift.
 */
@Service
public class ActivationService {

    public enum InvitationResult { ACTIVATED, UNKNOWN, EXPIRED }

    private final AppUserRepository userRepository;
    private final PersistentSessionService persistentSessionService;
    private final SetupAuditService auditService;
    private final Clock clock;

    public ActivationService(AppUserRepository userRepository,
                             PersistentSessionService persistentSessionService,
                             SetupAuditService auditService,
                             Clock clock) {
        this.userRepository = userRepository;
        this.persistentSessionService = persistentSessionService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * Completes an invitation while holding its row lock, checking the token on the locked row:
     * one that a concurrent activation already used is UNKNOWN, one that expired meanwhile EXPIRED.
     */
    @Transactional
    public InvitationResult completeInvitation(String token, String newPasswordHash, HttpServletRequest request) {
        Optional<AppUser> invited = userRepository.findByActivationTokenForUpdate(token);
        if (invited.isEmpty()) {
            return InvitationResult.UNKNOWN;
        }
        AppUser user = invited.get();
        if (user.getActivationTokenExpires() != null && user.getActivationTokenExpires().isBefore(clock.instant())) {
            return InvitationResult.EXPIRED;
        }
        complete(user, newPasswordHash, request);
        return InvitationResult.ACTIVATED;
    }

    @Transactional
    public void complete(AppUser user, String newPasswordHash, HttpServletRequest request) {
        user.setPasswordHash(newPasswordHash);
        user.setActivationToken(null);
        user.setActivationTokenExpires(null);
        user.setActivated(true);
        user.setAcknowledgedWarning(true);
        // A reset may be a response to a compromise, so like change-password this invalidates
        // every outstanding session: bump tokenVersion to revoke all access/refresh JWTs and
        // wipe the user's Remember-Me persistent sessions (CWE-613/640).
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);

        persistentSessionService.revokeAllForUser(user.getId());

        if (user.getRole() == UserRole.ADMIN) {
            auditService.record("admin.recovery.completed", user.getUsername(), request, null);
        }
    }
}
