package com.picsou.model;

/** Lifecycle of one sidecar bank synchronization, stored in {@code sidecar_session.sync_status}. */
public enum SidecarSyncStatus {
    IDLE,
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED
}
