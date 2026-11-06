package com.picsou.dto;

import java.util.List;

public record SignInMethodsResponse(
    boolean hasPassword,
    boolean mfaEnabled,
    List<LinkedIdentityResponse> identities,
    boolean ssoEnabled,
    String providerName,
    boolean adminGroupManaged
) {}
