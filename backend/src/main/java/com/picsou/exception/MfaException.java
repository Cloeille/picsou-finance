package com.picsou.exception;

/**
 * Domain error from {@link com.picsou.service.MfaService}: invalid TOTP code,
 * wrong reauth password, MFA already enabled, etc. Mapped to 400 in
 * {@link GlobalExceptionHandler}; the controller may translate to 401/403/409
 * by catching specific cases when needed.
 */
public class MfaException extends RuntimeException {
    public MfaException(String message) {
        super(message);
    }

    public MfaException(String message, Throwable cause) {
        super(message, cause);
    }
}
