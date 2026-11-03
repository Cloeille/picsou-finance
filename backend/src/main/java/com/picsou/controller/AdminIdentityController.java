package com.picsou.controller;

import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.AppUser;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.UserIdentityRepository;
import com.picsou.service.PersistentSessionService;
import com.picsou.service.SetupAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only removal of every SSO identity of another member (e.g. a compromised IdP account).
 * Under {@code /api/admin/**}, so {@code SecurityConfig} requires ADMIN. Like the MFA reset it
 * needs no re-authentication proof, and an admin cannot target themselves.
 */
@RestController
@RequestMapping("/api/admin/members")
public class AdminIdentityController {

    private final AppUserRepository userRepository;
    private final UserIdentityRepository identityRepository;
    private final PersistentSessionService persistentSessionService;
    private final SetupAuditService auditService;

    public AdminIdentityController(
        AppUserRepository userRepository,
        UserIdentityRepository identityRepository,
        PersistentSessionService persistentSessionService,
        SetupAuditService auditService
    ) {
        this.userRepository = userRepository;
        this.identityRepository = identityRepository;
        this.persistentSessionService = persistentSessionService;
        this.auditService = auditService;
    }

    @DeleteMapping("/{memberId}/identities")
    @Transactional
    public ResponseEntity<Void> unlinkAll(
        @AuthenticationPrincipal AppUser admin,
        @PathVariable Long memberId,
        HttpServletRequest request
    ) {
        AppUser target = userRepository.findByMemberId(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Member not found"));

        if (target.getId().equals(admin.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        identityRepository.deleteByUserId(target.getId());
        target.setTokenVersion(target.getTokenVersion() + 1);
        userRepository.save(target);
        persistentSessionService.revokeAllForUser(target.getId());
        auditService.record("admin.sso.identities-unlinked", admin.getUsername(), request,
            "targetUserId=" + target.getId());
        return ResponseEntity.noContent().build();
    }
}
