package com.picsou.adapter.sidecar;

/** Answer to a sidecar's {@code /complete}: the serialized session, opaque to the domain. */
public record SidecarSession(String sessionState) {}
