package com.picsou.exception;

/**
 * Raised by {@code ImportPreviewStore} when no preview can be stored because every slot is held
 * by an import that is currently running (those are never evicted: a rollback must be able to
 * restore them). Mapped to 429 in {@link GlobalExceptionHandler}.
 */
public class ImportPreviewCapacityException extends RuntimeException {

    public static final String MESSAGE =
        "Too many imports are in progress right now. Please try again in a few moments.";

    public ImportPreviewCapacityException() {
        super(MESSAGE);
    }
}
