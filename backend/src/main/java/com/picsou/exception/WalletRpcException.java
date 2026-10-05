package com.picsou.exception;

/**
 * Raised when a blockchain JSON-RPC endpoint returns an error, an empty
 * envelope, or a response missing its {@code result}. Unchecked so wallet
 * adapters keep the {@code WalletPort} signature; {@code WalletSyncService}
 * catches it and re-wraps into a {@link SyncException} (HTTP 422). This exists
 * to stop adapters from silently reporting a 0 balance on RPC failure.
 *
 * <p>This is a <em>technical</em> adapter-level signal, not a business
 * exception — it deliberately carries no user-facing message and is normally
 * wrapped by the service into a friendly {@code SyncException}. As a
 * defense-in-depth backstop, {@code GlobalExceptionHandler} also maps it to a
 * generic {@code 422} so a future {@code WalletPort} caller that forgets to
 * wrap it cannot surface a raw {@code 500}. See
 * {@code docs/conventions/error-handling.md}.
 */
public class WalletRpcException extends RuntimeException {
    public WalletRpcException(String message) {
        super(message);
    }

    /**
     * Wraps a transport-level failure (connection refused, HTTP 5xx, timeout,
     * retries exhausted) so callers treat it as an expected sync failure — a
     * {@code WARN} + {@code 422} — rather than an unexpected bug.
     */
    public WalletRpcException(String message, Throwable cause) {
        super(message, cause);
    }
}
