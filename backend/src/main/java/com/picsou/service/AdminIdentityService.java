package com.picsou.service;

import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SignInMethodException;
import com.picsou.model.AppUser;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.UserIdentityRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminIdentityService {
    private final AppUserRepository userRepository;
    private final UserIdentityRepository identityRepository;
    private final PersistentSessionService persistentSessionService;
    private final SetupAuditService auditService;

    public AdminIdentityService(AppUserRepository userRepository, UserIdentityRepository identityRepository,
                                PersistentSessionService persistentSessionService, SetupAuditService auditService) {
        this.userRepository = userRepository;
        this.identityRepository = identityRepository;
        this.persistentSessionService = persistentSessionService;
        this.auditService = auditService;
    }

    @Transactional
    public void unlinkAll(AppUser admin, Long memberId, HttpServletRequest request) {
        AppUser target = userRepository.findByMemberId(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
        if (target.getId().equals(admin.getId())) {
            throw new SignInMethodException(HttpStatus.FORBIDDEN, "SELF_UNLINK_FORBIDDEN",
                "Use your sign-in settings to remove your own single sign-on identities");
        }
        identityRepository.deleteByUserId(target.getId());
        target.setTokenVersion(target.getTokenVersion() + 1);
        userRepository.save(target);
        persistentSessionService.revokeAllForUser(target.getId());
        auditService.record("admin.sso.identities-unlinked", admin.getUsername(), request,
            "targetUserId=" + target.getId());
    }
}
