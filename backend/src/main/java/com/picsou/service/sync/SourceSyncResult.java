package com.picsou.service.sync;

import com.picsou.exception.SyncException;

public record SourceSyncResult(String source, Status status, String message) {
  public enum Status { SYNCED, QUEUED, SKIPPED_NOT_CONNECTED, NEEDS_REAUTH, FAILED, SKIPPED }

  /** Classifies only provider-defined authentication/session signals. */
  public static SourceSyncResult fromSyncException(String source, SyncException exception) {
    String code = exception.getCode();
    String message = exception.getMessage();
    boolean needsReauth = switch (source) {
      case "trade-republic" -> "SESSION_EXPIRED".equals(code)
          || (code == null && "AUTHENTICATION_ERROR".equals(message));
      case "revolut" -> "SESSION_EXPIRED".equals(code)
          || (code == null && "SESSION_EXPIRED".equals(message));
      case "ibkr" -> code == null && (endsWithProviderCode(message, "1012")
          || endsWithProviderCode(message, "1015"));
      case "bourso" -> "SESSION_EXPIRED".equals(code) || "INVALID_CREDENTIALS".equals(code);
      case "bourse-direct", "amundi", "fortuneo", "amex" -> "SESSION_EXPIRED".equals(code);
      default -> false;
    };
    String shortMessage = message == null ? "" : message.length() > 120 ? message.substring(0, 120) : message;
    return new SourceSyncResult(source, needsReauth ? Status.NEEDS_REAUTH : Status.FAILED, shortMessage);
  }

  private static boolean endsWithProviderCode(String message, String code) {
    return message != null && message.endsWith("(code " + code + ")");
  }
}
