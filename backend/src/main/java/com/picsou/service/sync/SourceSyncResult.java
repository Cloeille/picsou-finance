package com.picsou.service.sync;

public record SourceSyncResult(String source, Status status, String message) {
  public enum Status { SYNCED, QUEUED, SKIPPED_NOT_CONNECTED, NEEDS_REAUTH, FAILED, SKIPPED }
}
