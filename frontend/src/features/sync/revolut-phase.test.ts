import { describe, expect, it } from 'vitest'
import type { TFunction } from 'i18next'
import type { SyncProgress } from '@/types/api'
import { revolutErrorLabel, revolutPhaseLabel } from './revolut-phase'

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

  it('falls back to the backend sentence for a code it does not know or no code', () => {
    expect(revolutErrorLabel(t, { ...failed, errorCode: 'SOMETHING_NEW' })).toBe('English sentence')
    expect(revolutErrorLabel(t, failed)).toBe('English sentence')
  })
})

describe('revolutPhaseLabel', () => {
  it('never shows the approval countdown for a failed discovery', () => {
    const label = revolutPhaseLabel(t, { ...failed, errorCode: 'HUMAN_VERIFICATION_REQUIRED' })
    expect(label).not.toBe('sync.revolut.phase.awaitingApproval')
  })
})
