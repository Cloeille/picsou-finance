package com.picsou.exception;

public class SyncException extends RuntimeException {
    private final String code;

    public SyncException(String message) {
        this(message, null, null);
    }

    public SyncException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public SyncException(String message, Throwable cause, String code) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
