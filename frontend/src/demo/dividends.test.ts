import { AxiosHeaders } from 'axios'
import { describe, expect, it } from 'vitest'
import type { Account, ScpiPosition, ScpiPositionRequest, UpcomingDividend } from '@/types/api'
import { mockAccounts } from './data/accounts'
import { mockUpcomingDividends } from './data/dividends'
import { createDemoAdapter } from './index'

describe('demo upcoming dividends', () => {
  it('serves the horizon, keeps a PEA out of the budget flag, and drops later dates', async () => {
    const adapter = createDemoAdapter()
    const within60 = (await adapter({
      method: 'GET', url: '/dividends/upcoming', headers: new AxiosHeaders(),
      params: { horizonDays: 60 },
    })).data as UpcomingDividend[]

    expect(within60).toEqual(mockUpcomingDividends(60))
    expect(within60.map((row) => row.expectedDate)).toEqual([...within60.map((row) => row.expectedDate)].sort())
    expect(within60.some((row) => row.name === "L'Oréal")).toBe(false)
    const pea = within60.find((row) => row.accountType === 'PEA')
    expect(pea?.countsInBudget).toBe(false)
    expect(pea?.reinvested).toBe(false)
    const scpi = within60.find((row) => row.source === 'SCPI')
    expect(scpi?.reinvested).toBe(true)
    expect(scpi?.countsInBudget).toBe(false)

    const within30 = (await adapter({
      method: 'GET', url: '/dividends/upcoming', headers: new AxiosHeaders(),
      params: { horizonDays: 30 },
    })).data as UpcomingDividend[]
    expect(within30.some((row) => row.name === 'Sanofi')).toBe(false)
    expect(within30.length).toBeLessThan(within60.length)
  })

  it('defaults the horizon to 60 days when the query omits it', async () => {
    const adapter = createDemoAdapter()
    const data = (await adapter({
      method: 'GET', url: '/dividends/upcoming', headers: new AxiosHeaders(),
    })).data as UpcomingDividend[]
    expect(data).toEqual(mockUpcomingDividends(60))
  })

  it('filters a compte-titres and a PEA through accountId', async () => {
    const adapter = createDemoAdapter()
    const read = async (accountId: number) => (await adapter({
      method: 'GET', url: '/dividends/upcoming', headers: new AxiosHeaders(),
      params: { horizonDays: 366, accountId },
    })).data as UpcomingDividend[]

    const cto = await read(3)
    expect(cto).toEqual(mockUpcomingDividends(366, 3))
    expect(cto.length).toBeGreaterThan(0)
    expect(cto.every((row) => row.accountId === 3 && row.accountType === 'COMPTE_TITRES')).toBe(true)
    const total = cto.find((row) => row.ticker === 'TTE.PA')
    expect(total?.exDate).toBe(total?.expectedDate)
    expect(total?.paymentDateEstimated).toBe(true)
    expect(cto.find((row) => row.ticker === 'SAN.PA')?.paymentDate).toBeNull()
    const msft = cto.find((row) => row.ticker === 'MSFT')
    expect(msft?.basis).toBe('ANNOUNCED')
    expect(msft?.paymentDateEstimated).toBe(false)
    expect(msft?.paymentDate).toBeTruthy()

    const pea = await read(2)
    expect(pea).toEqual(mockUpcomingDividends(366, 2))
    expect(pea.length).toBeGreaterThan(0)
    expect(pea.every((row) => row.accountId === 2 && row.countsInBudget === false)).toBe(true)
    expect(await read(99)).toEqual([])
  })

  it('gives the demo SCPI a declared distribution and keeps it on save', async () => {
    const account = mockAccounts.find((item) => item.type === 'SCPI')!
    expect(account.scpi?.annualDistributionPerShareEur).toBe(12)
    expect(account.scpi?.distributionFrequency).toBe('QUARTERLY')

    const adapter = createDemoAdapter()
    const save = async (data: ScpiPositionRequest) => (await adapter({
      method: 'PUT', url: `/accounts/${account.id}/scpi`,
      headers: new AxiosHeaders(), data: JSON.stringify(data),
    })).data as ScpiPosition
    const read = async () => (await adapter({
      method: 'GET', url: `/accounts/${account.id}`, headers: new AxiosHeaders(),
    })).data as Account
    try {
      const saved = await save({
        ...account.scpi!, shareCount: account.scpi!.shareCount,
        annualDistributionPerShareEur: 15, distributionFrequency: 'MONTHLY',
      })
      expect(saved.annualDistributionPerShareEur).toBe(15)
      expect(saved.distributionFrequency).toBe('MONTHLY')
      expect((await read()).scpi?.annualDistributionPerShareEur).toBe(15)

      const cleared = await save({
        ...account.scpi!, shareCount: account.scpi!.shareCount,
        annualDistributionPerShareEur: null, distributionFrequency: null,
      })
      expect(cleared.annualDistributionPerShareEur).toBeNull()
      expect(cleared.distributionFrequency).toBeNull()
    } finally {
      await save({
        ...account.scpi!, shareCount: account.scpi!.shareCount,
        annualDistributionPerShareEur: 12, distributionFrequency: 'QUARTERLY',
      })
    }
  })
})
