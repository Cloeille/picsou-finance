import { describe, expect, it } from 'vitest'
import { accountInvestedAt, accountPnlAt, hasMeasurableGain } from './pnl'
import type { AccountPoint } from '@/features/history/api'
import type { Account, AccountType, RealEstateMetadata } from '@/types/api'

function account(type: AccountType, realEstate?: Partial<RealEstateMetadata>): Account {
  return {
    id: 1,
    name: 'test',
    type,
    provider: null,
    currency: 'EUR',
    currentBalance: 0,
    currentBalanceEur: 0,
    lastSyncedAt: null,
    isManual: true,
    color: '#6366f1',
    ticker: null,
    logoUrl: null,
    logoKey: null,
    createdAt: '2026-01-01T00:00:00Z',
    ...(realEstate ? { realEstate: realEstate as RealEstateMetadata } : {}),
  } as Account
}

const point = (total: number, invested: number, pnl: number): AccountPoint =>
  ({ total, invested, pnl })

describe('accountPnlAt / accountInvestedAt', () => {
  it('passes an investment account straight through', () => {
    const pea = account('PEA')
    const p = point(12000, 10000, 2000)
    expect(accountPnlAt(pea, p)).toBe(2000)
    expect(accountInvestedAt(pea, p)).toBe(10000)
  })

  it('measures a property against its cost basis, not the snapshot invested amount', () => {
    const flat = account('REAL_ESTATE', { costBasis: 250000 })
    // The backend reports invested = balance for a property, so its snapshot pnl is 0.
    const p = point(300000, 300000, 0)
    expect(accountPnlAt(flat, p)).toBe(50000)
    expect(accountInvestedAt(flat, p)).toBe(250000)
  })

  it('reports a loss when the property is worth less than it cost', () => {
    const flat = account('REAL_ESTATE', { costBasis: 250000 })
    expect(accountPnlAt(flat, point(230000, 230000, 0))).toBe(-20000)
  })

  it('keeps a property with no purchase price out of the gain, on both sides', () => {
    const described = account('REAL_ESTATE')
    const p = point(300000, 300000, 0)
    // Counting the balance as gain while contributing nothing to the basis would report a
    // gain the size of the whole property.
    expect(accountPnlAt(described, p)).toBe(0)
    expect(accountInvestedAt(described, p)).toBe(300000)
  })

  it('treats a zero cost basis as no cost basis', () => {
    const zeroed = account('REAL_ESTATE', { costBasis: 0 })
    const p = point(300000, 300000, 0)
    expect(accountPnlAt(zeroed, p)).toBe(0)
    expect(accountInvestedAt(zeroed, p)).toBe(300000)
  })

  it('treats an SCPI account as any other basis-less account', () => {
    const scpi = account('SCPI')
    const p = point(50000, 50000, 0)
    expect(accountPnlAt(scpi, p)).toBe(0)
    expect(accountInvestedAt(scpi, p)).toBe(50000)
  })
})

describe('hasMeasurableGain', () => {
  it('is true only for a property carrying a positive cost basis', () => {
    expect(hasMeasurableGain(account('REAL_ESTATE', { costBasis: 250000 }))).toBe(true)
    expect(hasMeasurableGain(account('REAL_ESTATE', { costBasis: 0 }))).toBe(false)
    expect(hasMeasurableGain(account('REAL_ESTATE'))).toBe(false)
    expect(hasMeasurableGain(account('SCPI'))).toBe(false)
    // An investment account's gain comes from HOLDING_ACCOUNT_TYPES, not from here.
    expect(hasMeasurableGain(account('PEA'))).toBe(false)
  })
})
