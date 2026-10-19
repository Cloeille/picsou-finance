import type { TFunction } from 'i18next'
import type { SyncProgress } from '@/types/api'

/**
 * Human-readable label for the current Revolut sync phase, shared between `RevolutTab`
 * (full form) and `SyncAllModal` (compact row) so the two surfaces never drift.
 * Falls back to a generic "syncing" label for an unrecognized/missing phase — the sidecar
 * only ever reports CHECKING_SESSION | LOGGING_IN | AWAITING_APPROVAL | HARVESTING today,
 * but the string is intentionally loose (backend enum) so an unmapped value degrades
 * gracefully instead of throwing.
 */
const ERROR_KEYS: Record<string, string> = {
  HUMAN_VERIFICATION_REQUIRED: 'sync.revolut.errors.humanVerificationRequired',
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

export function revolutPhaseLabel(t: TFunction, progress: SyncProgress | undefined): string {
  switch (progress?.phase) {
    case 'CHECKING_SESSION':
      return t('sync.revolut.phase.checkingSession')
    case 'LOGGING_IN':
      return t('sync.revolut.phase.loggingIn')
    case 'AWAITING_APPROVAL':
      return t('sync.revolut.phase.awaitingApproval', { remaining: progress?.remainingSeconds ?? 0 })
    case 'HARVESTING':
      return t('sync.revolut.phase.harvesting', { count: progress?.accountsFound ?? 0 })
    default:
      return t('sync.revolut.syncing')
  }
}
