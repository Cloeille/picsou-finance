import { describe, expect, it, vi } from 'vitest'
import type { SetupStatus } from './api'
import {
  archiveCodeKey,
  archiveUploadErrorKey,
  canImportArchive,
  passphraseLength,
  waitForArchiveImport,
} from './archive-import'
import en from '@/i18n/locales/en.json'
import fr from '@/i18n/locales/fr.json'

const status = (over: Partial<SetupStatus> = {}): SetupStatus => ({
  state: 'PENDING_ADMIN',
  needsSetup: true,
  integrations: {},
  ...over,
})

const apiError = (httpStatus: number | undefined, code?: string) =>
  httpStatus === undefined
    ? { code: 'ERR_NETWORK' }
    : { response: { status: httpStatus, data: { code } } }

function lookup(tree: unknown, key: string): unknown {
  return key.split('.').reduce<unknown>((acc, part) => (acc as Record<string, unknown> | undefined)?.[part], tree)
}

describe('canImportArchive', () => {
  it('allows the first step only', () => {
    expect(canImportArchive(status())).toBe(true)
    expect(canImportArchive(status({ state: 'IN_PROGRESS' }))).toBe(false)
    expect(canImportArchive(status({ state: 'COMPLETE', needsSetup: false }))).toBe(false)
    expect(canImportArchive(undefined)).toBe(false)
  })
})

describe('passphraseLength', () => {
  it('counts characters like the server, not UTF-16 units', () => {
    expect(passphraseLength('abcdefghijkl')).toBe(12)
    expect(passphraseLength('😀'.repeat(12))).toBe(12)
  })
})

describe('error mapping', () => {
  const backendCodes = [
    'PASSPHRASE_REQUIRED', 'PASSPHRASE_TOO_SHORT', 'WRONG_PASSPHRASE', 'NOT_AN_ARCHIVE',
    'CORRUPT_ARCHIVE', 'VERIFICATION_FAILED', 'ARCHIVE_TOO_NEW', 'KEY_OVERRIDDEN',
    'IMPORT_UNAVAILABLE', 'IMPORT_ALREADY_STAGED', 'IMPORT_IN_PROGRESS', 'SETUP_ALREADY_STARTED',
    'TARGET_NOT_EMPTY', 'ARCHIVE_TOO_LARGE', 'ARCHIVE_FAILED',
  ]

  it.each(backendCodes)('maps %s to a message in every language', (code) => {
    const key = archiveCodeKey(code)
    expect(key).toBe(`setup.archive.errors.${code}`)
    expect(typeof lookup(en, key)).toBe('string')
    expect(typeof lookup(fr, key)).toBe('string')
  })

  it('falls back to the generic failure for an unknown or missing code', () => {
    expect(archiveCodeKey('SOMETHING_NEW')).toBe('setup.archive.errors.ARCHIVE_FAILED')
    expect(archiveCodeKey(undefined)).toBe('setup.archive.errors.ARCHIVE_FAILED')
  })

  it('prefers the stable code over the HTTP status', () => {
    expect(archiveUploadErrorKey(apiError(400, 'WRONG_PASSPHRASE'))).toBe('setup.archive.errors.WRONG_PASSPHRASE')
    expect(archiveUploadErrorKey(apiError(409, 'KEY_OVERRIDDEN'))).toBe('setup.archive.errors.KEY_OVERRIDDEN')
    expect(archiveUploadErrorKey(apiError(413, 'ARCHIVE_TOO_LARGE'))).toBe('setup.archive.errors.ARCHIVE_TOO_LARGE')
  })

  it('maps the statuses that carry no code', () => {
    expect(archiveUploadErrorKey(apiError(429))).toBe('setup.archive.errors.rateLimited')
    expect(archiveUploadErrorKey(apiError(410))).toBe('setup.archive.errors.setupComplete')
    expect(archiveUploadErrorKey(apiError(403))).toBe('setup.archive.errors.forbidden')
    // A reverse proxy answers 413 with an HTML page.
    expect(archiveUploadErrorKey(apiError(413))).toBe('setup.archive.errors.ARCHIVE_TOO_LARGE')
    expect(archiveUploadErrorKey(apiError(undefined))).toBe('setup.archive.errors.interrupted')
    expect(archiveUploadErrorKey(apiError(500))).toBe('setup.archive.errors.ARCHIVE_FAILED')
  })

  it('has every key of the dialog in all four locales', async () => {
    const [de, es] = await Promise.all([
      import('@/i18n/locales/de.json'),
      import('@/i18n/locales/es.json'),
    ])
    for (const locale of [en, fr, de.default, es.default]) {
      const messages = (locale as unknown as { setup: { archive: { errors: Record<string, string> } } }).setup.archive.errors
      for (const key of [...backendCodes, 'rateLimited', 'setupComplete', 'forbidden', 'interrupted']) {
        expect(messages[key], key).toBeTruthy()
      }
    }
  })
})

describe('waitForArchiveImport', () => {
  const fast = { sleep: () => Promise.resolve(), intervalMs: 0 }

  it('keeps polling while pending and through network errors, then returns done', async () => {
    const getStatus = vi
      .fn<() => Promise<SetupStatus>>()
      .mockResolvedValueOnce(status({ archiveImport: { state: 'pending' } }))
      .mockRejectedValueOnce(new Error('Network Error'))
      .mockRejectedValueOnce(new Error('Network Error'))
      .mockResolvedValueOnce(status({ needsSetup: false, state: 'COMPLETE', archiveImport: { state: 'done', tables: 42, rows: 1234 } }))
    const reachable: boolean[] = []

    const outcome = await waitForArchiveImport({ getStatus, onReachable: (r) => reachable.push(r), ...fast })

    expect(outcome).toEqual({ state: 'done', tables: 42, rows: 1234 })
    expect(getStatus).toHaveBeenCalledTimes(4)
    expect(reachable).toEqual([true, false, false, true])
  })

  it('returns the code of a failed import, the instance staying on the first screen', async () => {
    const getStatus = vi.fn().mockResolvedValue(status({ archiveImport: { state: 'failed', code: 'CORRUPT_ARCHIVE' } }))
    expect(await waitForArchiveImport({ getStatus, ...fast })).toEqual({ state: 'failed', code: 'CORRUPT_ARCHIVE' })
  })

  it('treats a complete setup without a report as done, using the counts of the 202', async () => {
    // The first read of a completed instance takes the outcome: another reader may have had it.
    const getStatus = vi.fn().mockResolvedValue(status({ needsSetup: false, state: 'COMPLETE' }))
    const outcome = await waitForArchiveImport({ getStatus, accepted: { restarting: true, tables: 7, rows: 70 }, ...fast })
    expect(outcome).toEqual({ state: 'done', tables: 7, rows: 70 })
  })

  it('treats a first screen without a report as failed', async () => {
    const getStatus = vi.fn().mockResolvedValue(status())
    expect(await waitForArchiveImport({ getStatus, ...fast })).toEqual({ state: 'failed', code: 'ARCHIVE_FAILED' })
  })

  it('gives up when the server never answers', async () => {
    const getStatus = vi.fn().mockRejectedValue(new Error('Network Error'))
    let clock = 0
    const outcome = await waitForArchiveImport({
      getStatus,
      timeoutMs: 100,
      intervalMs: 10,
      now: () => clock,
      sleep: async (ms) => {
        clock += ms
      },
    })
    expect(outcome).toEqual({ state: 'timeout' })
  })

  it('stops quietly when aborted', async () => {
    const controller = new AbortController()
    const getStatus = vi.fn().mockImplementation(async () => {
      controller.abort()
      return status({ archiveImport: { state: 'pending' } })
    })
    expect(await waitForArchiveImport({ getStatus, signal: controller.signal, ...fast })).toBeNull()
  })
})
