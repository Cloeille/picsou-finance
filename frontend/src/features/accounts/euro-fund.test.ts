import { describe, expect, it } from 'vitest'
import { euroFundLabelKey, hasSyncedEuroFund } from './euro-fund'

describe('synced euro fund', () => {
  it('covers PER accounts and synced BPCE insurer assurance vie contracts only', () => {
    expect(hasSyncedEuroFund({ type: 'PER', isManual: true })).toBe(true)
    expect(hasSyncedEuroFund({ type: 'PER', isManual: false, isInsurerContract: true })).toBe(true)
    expect(hasSyncedEuroFund({ type: 'ASSURANCE_VIE', isManual: false, isInsurerContract: true })).toBe(true)
    // A hand-made account never qualifies, even if the backend flag were somehow set.
    expect(hasSyncedEuroFund({ type: 'ASSURANCE_VIE', isManual: true, isInsurerContract: true })).toBe(false)
    expect(hasSyncedEuroFund({ type: 'ASSURANCE_VIE', isManual: true })).toBe(false)
    // Synced assurance vie from another provider (CORUM, Bourso, etc.) does not qualify.
    expect(hasSyncedEuroFund({ type: 'ASSURANCE_VIE', isManual: false, isInsurerContract: false })).toBe(false)
    expect(hasSyncedEuroFund({ type: 'ASSURANCE_VIE', isManual: false })).toBe(false)
    // Account-type check stays enforced.
    expect(hasSyncedEuroFund({ type: 'PEA', isManual: false, isInsurerContract: true })).toBe(false)
  })

  it('labels a PER and an assurance vie differently', () => {
    expect(euroFundLabelKey({ type: 'PER' })).toBe('accounts.perEuroFundBalance')
    expect(euroFundLabelKey({ type: 'ASSURANCE_VIE' })).toBe('accounts.lifeEuroFundBalance')
  })
})
