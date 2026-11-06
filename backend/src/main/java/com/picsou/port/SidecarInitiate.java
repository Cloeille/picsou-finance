package com.picsou.port;

/**
 * Answer to the first authentication step of a connector sidecar's {@code /initiate}.
 *
 * @param processId    pending challenge identifier, or {@code null} when MFA is not required
 * @param mfaRequired  whether the connector's {@code completeAuth} must be called
 * @param mfaType      provider MFA type, or {@code null} when authentication is complete
 * @param sessionState serialized session state, populated only when {@code mfaRequired == false}
 *                     (a login can open a session in one round-trip when no code is required)
 */
public record SidecarInitiate(String processId, boolean mfaRequired, String mfaType, String sessionState) {}
