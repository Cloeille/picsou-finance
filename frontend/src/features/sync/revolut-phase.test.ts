import { describe, expect, it } from 'vitest'
import type { TFunction } from 'i18next'
import type { SyncProgress } from '@/types/api'
import { isRevolutReady, revolutErrorLabel } from './revolut-phase'

/** Echoes the key so assertions can see which translation fired. */
const t = ((key: string) => key) as unknown as TFunction

const failed: SyncProgress = {
  running: false, phase: 'LOGGING_IN', elapsedSeconds: 12, remainingSeconds: null,
  accountsFound: null, done: true, error: 'English sentence', errorCode: null, discovered: [],
}

describe('revolutErrorLabel', () => {
  it('translates a captcha instead of showing the backend sentence', () => {
    expect(revolutErrorLabel(t, { ...failed, errorCode: 'HUMAN_VERIFICATION_REQUIRED' }))
      .toBe('sync.revolut.errors.humanVerificationRequired')
  })

  it('translates an unrecognised sign-in page', () => {
    expect(revolutErrorLabel(t, { ...failed, errorCode: 'UPSTREAM_FORMAT_CHANGED' }))
      .toBe('sync.revolut.errors.upstreamFormatChanged')
  })

  it('translates an expired session instead of showing the backend sentence', () => {
    expect(revolutErrorLabel(t, { ...failed, errorCode: 'SESSION_EXPIRED' }))
      .toBe('sync.revolut.errors.sessionExpired')
  })

  it('falls back to the backend sentence for a code it does not know or no code', () => {
    expect(revolutErrorLabel(t, { ...failed, errorCode: 'SOMETHING_NEW' })).toBe('English sentence')
    expect(revolutErrorLabel(t, failed)).toBe('English sentence')
  })
})

describe('isRevolutReady', () => {
  it('is true when credentials are remembered in server mode', () => {
    expect(isRevolutReady({ connected: false, remembered: true, lastSyncedAt: null })).toBe(true)
  })

  it('is false in server mode when credentials are not remembered, even if connected is true', () => {
    expect(isRevolutReady({ connected: true, remembered: false, lastSyncedAt: null })).toBe(false)
    expect(isRevolutReady({ connected: false, remembered: false, lastSyncedAt: null })).toBe(false)
  })

  it('is true on desktop when webviewLoginSupported and connected are both true', () => {
    expect(isRevolutReady({ connected: true, remembered: false, lastSyncedAt: null, webviewLoginSupported: true })).toBe(true)
  })

  it('is false on desktop after Forget (connected false) or when status is missing', () => {
    expect(isRevolutReady({ connected: false, remembered: false, lastSyncedAt: null, webviewLoginSupported: true })).toBe(false)
    expect(isRevolutReady(undefined)).toBe(false)
  })
})
