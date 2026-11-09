import { describe, expect, it } from 'vitest'
import { euroFundLabelKey, hasSyncedEuroFund } from './euro-fund'

describe('synced euro fund', () => {
  it('covers a PER and a Caisse d’Epargne assurance vie only', () => {
    expect(hasSyncedEuroFund({ type: 'PER', provider: null, isManual: true })).toBe(true)
    expect(hasSyncedEuroFund({ type: 'ASSURANCE_VIE', provider: "Caisse d'Epargne", isManual: false })).toBe(true)
    // A hand-made account with that provider typed in is not a synced one.
    expect(hasSyncedEuroFund({ type: 'ASSURANCE_VIE', provider: "Caisse d'Epargne", isManual: true })).toBe(false)
    expect(hasSyncedEuroFund({ type: 'ASSURANCE_VIE', provider: 'CORUM', isManual: false })).toBe(false)
    expect(hasSyncedEuroFund({ type: 'ASSURANCE_VIE', provider: null, isManual: true })).toBe(false)
    expect(hasSyncedEuroFund({ type: 'PEA', provider: "Caisse d'Epargne", isManual: false })).toBe(false)
  })

  it('labels a PER and an assurance vie differently', () => {
    expect(euroFundLabelKey({ type: 'PER' })).toBe('accounts.perEuroFundBalance')
    expect(euroFundLabelKey({ type: 'ASSURANCE_VIE' })).toBe('accounts.lifeEuroFundBalance')
  })
})
