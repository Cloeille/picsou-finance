package com.picsou.service;

import com.picsou.config.SsoProperties;
import com.picsou.dto.LinkedIdentityResponse;
import com.picsou.dto.SignInMethodsResponse;
import com.picsou.dto.SsoInfoResponse;
import com.picsou.model.AppUser;
import com.picsou.model.UserIdentity;
import com.picsou.repository.UserIdentityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Builds the SSO info and the caller's sign-in methods (password, MFA, linked identities). */
@Service
public class SignInMethodsService {

    private final UserIdentityRepository identityRepository;
    private final MfaService mfaService;
    private final SsoProperties ssoProperties;

    public SignInMethodsService(UserIdentityRepository identityRepository, MfaService mfaService,
                                SsoProperties ssoProperties) {
        this.identityRepository = identityRepository;
        this.mfaService = mfaService;
        this.ssoProperties = ssoProperties;
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
            user.getPasswordHash() != null && !user.getPasswordHash().isBlank(),
            mfaService.isEnabled(user),
            identities,
            ssoProperties.enabled(),
            ssoProperties.providerName(),
            ssoProperties.adminGroupManaged());
    }

    private static LinkedIdentityResponse toResponse(UserIdentity identity, String currentIssuer) {
        return new LinkedIdentityResponse(
            identity.getId(), identity.getEmail(), identity.getDisplayName(),
            identity.getCreatedAt(), identity.getLastLoginAt(),
            identity.getIssuer().equals(currentIssuer));
    }
}
