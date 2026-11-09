package com.picsou.model;

/**
 * Where a Sofidy synchronization stands.
 *
 * <p>{@code QUEUED} and {@code RUNNING} are separate so a crash between the two
 * is visible: a job left {@code RUNNING} was interrupted, not finished.
 */
public enum SofidySyncStatus {
    IDLE,
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED
}
