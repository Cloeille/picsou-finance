package com.picsou.controller;

import com.picsou.model.AppUser;
import com.picsou.service.AdminIdentityService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    private final AdminIdentityService service;

    public AdminIdentityController(AdminIdentityService service) {
        this.service = service;
    }

    @DeleteMapping("/{memberId}/identities")
    public ResponseEntity<Void> unlinkAll(
        @AuthenticationPrincipal AppUser admin,
        @PathVariable Long memberId,
        HttpServletRequest request
    ) {
        service.unlinkAll(admin, memberId, request);
        return ResponseEntity.noContent().build();
    }
}
