import { describe, expect, it } from 'vitest'
import { euroFundLabelKey, hasSyncedEuroFund } from './euro-fund'

describe('synced euro fund', () => {
  it('covers a PER and a Caisse d’Epargne assurance vie only', () => {
    expect(hasSyncedEuroFund({ type: 'PER', provider: null })).toBe(true)
    expect(hasSyncedEuroFund({ type: 'ASSURANCE_VIE', provider: "Caisse d'Epargne" })).toBe(true)
    expect(hasSyncedEuroFund({ type: 'ASSURANCE_VIE', provider: 'CORUM' })).toBe(false)
    expect(hasSyncedEuroFund({ type: 'ASSURANCE_VIE', provider: null })).toBe(false)
    expect(hasSyncedEuroFund({ type: 'PEA', provider: "Caisse d'Epargne" })).toBe(false)
  })

  it('labels a PER and an assurance vie differently', () => {
    expect(euroFundLabelKey({ type: 'PER' })).toBe('accounts.perEuroFundBalance')
    expect(euroFundLabelKey({ type: 'ASSURANCE_VIE' })).toBe('accounts.lifeEuroFundBalance')
  })
})
