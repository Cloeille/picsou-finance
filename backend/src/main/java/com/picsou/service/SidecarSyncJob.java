package com.picsou.service;

/**
 * One queued sidecar import: the session row it is fenced to, its member, the decrypted session
 * state, and whether it fetches the provider's full history instead of the routine window.
 */
public record SidecarSyncJob(Long sessionId, Long memberId, String plainState, boolean history) {}
