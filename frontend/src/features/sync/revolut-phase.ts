import type { TFunction } from 'i18next'
import type { SyncProgress } from '@/types/api'

/**
 * Human-readable label for the current Revolut sync phase, shared between `RevolutTab`
 * (full form) and `SyncAllModal` (compact row) so the two surfaces never drift.
 * Falls back to a generic "syncing" label for an unrecognized/missing phase — the sidecar
 * only ever reports CHECKING_SESSION | LOGGING_IN | AWAITING_HUMAN | AWAITING_APPROVAL | HARVESTING today,
 * but the string is intentionally loose (backend enum) so an unmapped value degrades
 * gracefully instead of throwing.
 */
export function revolutPhaseLabel(t: TFunction, progress: SyncProgress | undefined): string {
  switch (progress?.phase) {
    case 'CHECKING_SESSION':
      return t('sync.revolut.phase.checkingSession')
    case 'LOGGING_IN':
      return t('sync.revolut.phase.loggingIn')
    case 'AWAITING_HUMAN':
      return t('sync.revolut.phase.awaitingHuman', { remaining: progress?.remainingSeconds ?? 0 })
    case 'AWAITING_APPROVAL':
      return t('sync.revolut.phase.awaitingApproval', { remaining: progress?.remainingSeconds ?? 0 })
    case 'HARVESTING':
      return t('sync.revolut.phase.harvesting', { count: progress?.accountsFound ?? 0 })
    default:
      return t('sync.revolut.syncing')
  }
}

/**
 * Address of the sidecar's remote view while Revolut waits for the user to solve a captcha, or
 * null when no hand-off is open. The view is served by the sidecar on its own port, on the host
 * that serves Picsou. The one-time password and the display options travel in the URL fragment:
 * a fragment is never sent to a server, so it stays out of every access log and proxy.
 */
export function revolutViewerUrl(progress: SyncProgress | undefined, host: string): string | null {
  if (progress?.phase !== 'AWAITING_HUMAN' || !progress.viewerPort || !progress.viewerPassword) return null
  const options = new URLSearchParams({ autoconnect: 'true', resize: 'scale', password: progress.viewerPassword })
  return `http://${host.includes(':') ? `[${host}]` : host}:${progress.viewerPort}/vnc.html#${options}`
}

const ERROR_KEYS: Record<string, string> = {
  HUMAN_VERIFICATION_REQUIRED: 'sync.revolut.errors.humanVerificationRequired',
  LOGIN_PACED: 'sync.revolut.errors.loginPaced',
  UPSTREAM_FORMAT_CHANGED: 'sync.revolut.errors.upstreamFormatChanged',
}

/**
 * Message for a failed Revolut discovery: the translation of the backend's stable `errorCode`
 * when it has one, otherwise the backend's own sentence. Shared like `revolutPhaseLabel`.
 */
export function revolutErrorLabel(t: TFunction, progress: SyncProgress): string {
  const key = progress.errorCode ? ERROR_KEYS[progress.errorCode] : undefined
  return key ? t(key) : (progress.error ?? t('common.error'))
}
