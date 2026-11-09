package com.picsou.service;

import com.picsou.port.SidecarErrorCode;

/** The two facts the daily scheduler reads from a bank's session status. */
public interface SidecarSessionView {

    boolean isActive();

    SidecarErrorCode lastSyncError();
}
