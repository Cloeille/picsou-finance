import type { ArchiveImportAccepted, ArchiveImportStatus, SetupStatus } from './api'
import { getErrorCode, getErrorStatus } from '@/lib/errors'

/** The archive is first checked on its own, then applied by the restarted server. */
export const ARCHIVE_MIN_PASSPHRASE_CHARS = 12
export const ARCHIVE_EXTENSION = '.picsou-archive'

const POLL_INTERVAL_MS = 1500
// Applying a large database after the restart can take a while.
const POLL_TIMEOUT_MS = 5 * 60_000

export type ArchiveImportOutcome =
  | { state: 'done'; tables: number; rows: number }
  | { state: 'failed'; code: string }
  | { state: 'timeout' }

export function passphraseLength(value: string): number {
  // The server counts characters, not UTF-16 units.
  return [...value].length
}

export function canImportArchive(status: SetupStatus | undefined): boolean {
  return Boolean(status && status.needsSetup && status.state === 'PENDING_ADMIN')
}

const KNOWN_CODES = new Set([
  'PASSPHRASE_REQUIRED',
  'PASSPHRASE_TOO_SHORT',
  'WRONG_PASSPHRASE',
  'NOT_AN_ARCHIVE',
  'CORRUPT_ARCHIVE',
  'VERIFICATION_FAILED',
  'ARCHIVE_TOO_NEW',
  'KEY_OVERRIDDEN',
  'IMPORT_UNAVAILABLE',
  'IMPORT_ALREADY_STAGED',
  'IMPORT_IN_PROGRESS',
  'SETUP_ALREADY_STARTED',
  'TARGET_NOT_EMPTY',
  'ARCHIVE_TOO_LARGE',
  'ARCHIVE_FAILED',
])

/** i18n key of the message for a backend archive `code`, `ARCHIVE_FAILED` when it is unknown. */
export function archiveCodeKey(code: string | null | undefined): string {
  return `setup.archive.errors.${code && KNOWN_CODES.has(code) ? code : 'ARCHIVE_FAILED'}`
}

/** i18n key of the message for a failed upload: the stable code first, then the HTTP status. */
export function archiveUploadErrorKey(err: unknown): string {
  const code = getErrorCode(err)
  if (code && KNOWN_CODES.has(code)) return archiveCodeKey(code)
  const status = getErrorStatus(err)
  if (status === 429) return 'setup.archive.errors.rateLimited'
  if (status === 410) return 'setup.archive.errors.setupComplete'
  if (status === 403) return 'setup.archive.errors.forbidden'
  // A reverse proxy refuses an oversized body before Picsou can name it.
  if (status === 413) return archiveCodeKey('ARCHIVE_TOO_LARGE')
  if (!status) return 'setup.archive.errors.interrupted'
  return archiveCodeKey(undefined)
}

interface WaitOptions {
  getStatus: () => Promise<SetupStatus>
  /** The 202 answer: its counts stand in when the outcome was already read by another request. */
  accepted?: ArchiveImportAccepted
  /** `false` while the server does not answer, which is expected during the restart. */
  onReachable?: (reachable: boolean) => void
  signal?: AbortSignal
  intervalMs?: number
  timeoutMs?: number
  sleep?: (ms: number) => Promise<void>
  now?: () => number
}

const defaultSleep = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms))

/**
 * Polls `GET /api/setup/status` until the restarted server reports the outcome of the import.
 * Network errors are tolerated: Docker restarts in-process and the desktop app relaunches its
 * whole window, so the server is unreachable for a while.
 *
 * Returns `null` when `signal` aborts.
 */
export async function waitForArchiveImport({
  getStatus,
  accepted,
  onReachable,
  signal,
  intervalMs = POLL_INTERVAL_MS,
  timeoutMs = POLL_TIMEOUT_MS,
  sleep = defaultSleep,
  now = Date.now,
}: WaitOptions): Promise<ArchiveImportOutcome | null> {
  const deadline = now() + timeoutMs
  while (!signal?.aborted) {
    let status: SetupStatus | null = null
    try {
      status = await getStatus()
      onReachable?.(true)
    } catch {
      onReachable?.(false)
    }
    if (signal?.aborted) return null
    if (status) {
      const outcome = outcomeOf(status, accepted)
      if (outcome) return outcome
    }
    if (now() >= deadline) return { state: 'timeout' }
    await sleep(intervalMs)
  }
  return null
}

function outcomeOf(
  status: SetupStatus,
  accepted: ArchiveImportAccepted | undefined,
): ArchiveImportOutcome | null {
  const reported: ArchiveImportStatus | undefined = status.archiveImport
  if (reported?.state === 'pending') return null
  if (reported?.state === 'done') return reported
  if (reported?.state === 'failed') return reported
  // Nothing to report. Once setup is complete the first read takes the outcome, so another
  // reader of the status (a refetch on reconnect) may have taken it: the imported instance is
  // there. While setup is not complete the outcome stays readable, so its absence means the
  // restarted server did not apply anything.
  return status.needsSetup
    ? { state: 'failed', code: 'ARCHIVE_FAILED' }
    : { state: 'done', tables: accepted?.tables ?? 0, rows: accepted?.rows ?? 0 }
}
