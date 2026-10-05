package com.picsou.model;

public enum DegiroSessionStatus {
    ACTIVE,
    /** Session expired (DEGIRO times out after ~30 min of inactivity) — user must reconnect. */
    REAUTH_REQUIRED,
    FAILED
}
