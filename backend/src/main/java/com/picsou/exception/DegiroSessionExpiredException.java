package com.picsou.exception;

/**
 * DEGIRO's session cookie expired (the sidecar answered HTTP 401 on /portfolio).
 *
 * <p>A dedicated type rather than a {@code "SESSION_EXPIRED"} message sentinel: the
 * service branches on this condition to flip the stored session to
 * {@code REAUTH_REQUIRED}, and matching on message text would break silently the
 * first time the wording changes, with no compile error. It also keeps the internal
 * marker out of any user-facing error surface — the message carried here is the one
 * we actually want the user to read.
 */
public class DegiroSessionExpiredException extends SyncException {

    public DegiroSessionExpiredException() {
        super("Your DEGIRO session has expired. Please reconnect from the DEGIRO page.");
    }
}
