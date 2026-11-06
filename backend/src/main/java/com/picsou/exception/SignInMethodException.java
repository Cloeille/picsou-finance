package com.picsou.exception;

import org.springframework.http.HttpStatus;

/** A refused sign-in-method change (lockout guard); rendered as a ProblemDetail with a stable {@code code}. */
public class SignInMethodException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public SignInMethodException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
