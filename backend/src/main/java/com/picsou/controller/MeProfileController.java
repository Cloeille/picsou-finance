package com.picsou.controller;

import com.picsou.dto.MemberProfileRequest;
import com.picsou.dto.MemberProfileResponse;
import com.picsou.service.MemberProfileService;
import com.picsou.service.UserContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authenticated member's personal and fiscal profile.
 *
 * <p>Under {@code /api/me} rather than {@code /api/settings}: this is data about the person, not
 * an application preference, and it is what {@code MeExportController} already namespaces.
 */
@RestController
@RequestMapping("/api/me/profile")
public class MeProfileController {

    private final MemberProfileService profileService;
    private final UserContext userContext;

    public MeProfileController(MemberProfileService profileService, UserContext userContext) {
        this.profileService = profileService;
        this.userContext = userContext;
    }

    /** The member's profile, or an all-null one when they have never stated anything. */
    @GetMapping
    public MemberProfileResponse get() {
        return profileService.get(userContext.currentMemberId());
    }

    /** Replaces the whole profile. A null field clears it — see {@code MemberProfileRequest}. */
    @PutMapping
    public MemberProfileResponse replace(@Valid @RequestBody MemberProfileRequest request) {
        return profileService.replace(userContext.currentMemberId(), request);
    }
}
