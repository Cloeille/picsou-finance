package com.picsou.adapter.sidecar;

import com.picsou.exception.SyncException;

/**
 * The sidecar refused the shared secret the backend presented.
 *
 * <p>This is always a deployment fault, never a user one: the bank was never
 * contacted, the credentials the user typed were never wrong, and retrying
 * changes nothing until {@code APP_SIDECAR_API_KEY} matches on both sides. It is a
 * {@link SyncException} so that every adapter's existing
 * {@code instanceof SyncException} short-circuit passes it through untouched
 * instead of relabelling it as an upstream outage or a bad password.
 */
public class SidecarAuthenticationException extends SyncException {

    /** Surfaced to the API as {@code code}; deliberately outside every per-connector error enum. */
    public static final String CODE = "SIDECAR_UNAUTHORIZED";

    public SidecarAuthenticationException(String sidecarName) {
        super(
            "Picsou could not authenticate to the " + sidecarName + " sidecar. The backend and the "
                + "sidecar must share the same APP_SIDECAR_API_KEY -- check that both containers were "
                + "started with it.",
            null,
            CODE
        );
    }
}
