package com.picsou.service;

import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The shared sink of new-member activation, admin-initiated password reset and admin-recovery
 * completion. The password and SSO activation paths both end here so they cannot drift.
 */
@Service
public class ActivationService {

    private final AppUserRepository userRepository;
    private final PersistentSessionService persistentSessionService;
    private final SetupAuditService auditService;

    public ActivationService(AppUserRepository userRepository,
                             PersistentSessionService persistentSessionService,
                             SetupAuditService auditService) {
        this.userRepository = userRepository;
        this.persistentSessionService = persistentSessionService;
        this.auditService = auditService;
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
