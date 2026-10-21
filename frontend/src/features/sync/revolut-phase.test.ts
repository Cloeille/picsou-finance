import { describe, expect, it } from 'vitest'
import type { TFunction } from 'i18next'
import type { SyncProgress } from '@/types/api'
import { revolutErrorLabel, revolutPhaseLabel, revolutViewerUrl } from './revolut-phase'

/** Echoes the key so assertions can see which translation fired. */
const t = ((key: string, options?: Record<string, unknown>) =>
  options ? `${key} ${JSON.stringify(options)}` : key) as unknown as TFunction

const failed: SyncProgress = {
  running: false, phase: 'LOGGING_IN', elapsedSeconds: 12, remainingSeconds: null,
  accountsFound: null, done: true, error: 'English sentence', errorCode: null,
  viewerPort: null, viewerPassword: null, discovered: [],
}

describe('revolutErrorLabel', () => {
  it('translates a captcha instead of showing the backend sentence', () => {
    expect(revolutErrorLabel(t, { ...failed, errorCode: 'HUMAN_VERIFICATION_REQUIRED' }))
      .toBe('sync.revolut.errors.humanVerificationRequired')
  })

  it('translates a login refused because one already failed today', () => {
    expect(revolutErrorLabel(t, { ...failed, errorCode: 'LOGIN_PACED' }))
      .toBe('sync.revolut.errors.loginPaced')
  })

  it('translates an unrecognised sign-in page', () => {
    expect(revolutErrorLabel(t, { ...failed, errorCode: 'UPSTREAM_FORMAT_CHANGED' }))
      .toBe('sync.revolut.errors.upstreamFormatChanged')
  })

  it('falls back to the backend sentence for a code it does not know or no code', () => {
    expect(revolutErrorLabel(t, { ...failed, errorCode: 'SOMETHING_NEW' })).toBe('English sentence')
    expect(revolutErrorLabel(t, failed)).toBe('English sentence')
  })
})

const handOff: SyncProgress = {
  ...failed, running: true, done: false, error: null, phase: 'AWAITING_HUMAN', remainingSeconds: 200,
  viewerPort: 6080, viewerPassword: 'Ab3dEf9Z',
}

describe('revolutPhaseLabel', () => {
  it('asks the user to solve the captcha while the hand-off is open', () => {
    expect(revolutPhaseLabel(t, handOff)).toBe('sync.revolut.phase.awaitingHuman {"remaining":200}')
  })
})

describe('revolutViewerUrl', () => {
  it('points at the sidecar view on the host that serves Picsou, with the password in the fragment only', () => {
    const url = new URL(revolutViewerUrl(handOff, 'picsou.local')!)

    expect(`${url.protocol}//${url.host}${url.pathname}`).toBe('http://picsou.local:6080/vnc.html')
    expect(url.search).toBe('')
    expect(new URLSearchParams(url.hash.slice(1)).get('password')).toBe('Ab3dEf9Z')
    expect(new URLSearchParams(url.hash.slice(1)).get('autoconnect')).toBe('true')
  })

  it('brackets an IPv6 host', () => {
    expect(revolutViewerUrl(handOff, '::1')).toMatch(/^http:\/\/\[::1\]:6080\//)
  })

  it('offers nothing outside a hand-off or without the view\'s port and password', () => {
    expect(revolutViewerUrl(undefined, 'h')).toBeNull()
    expect(revolutViewerUrl({ ...handOff, phase: 'AWAITING_APPROVAL' }, 'h')).toBeNull()
    expect(revolutViewerUrl({ ...handOff, viewerPassword: null }, 'h')).toBeNull()
    expect(revolutViewerUrl({ ...handOff, viewerPort: null }, 'h')).toBeNull()
  })
})
