package com.picsou.dto;

import com.picsou.model.SetupState;

import java.util.Map;

public record SetupStatusResponse(
    SetupState state,
    boolean needsSetup,
    Map<String, Boolean> integrations,
    SetupSsoInfo sso
) {
}
