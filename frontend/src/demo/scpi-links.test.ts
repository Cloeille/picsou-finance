import { AxiosHeaders } from 'axios'
import { describe, expect, it } from 'vitest'
import type { Account, RealEstateSummary, ScpiPosition, ScpiPositionRequest } from '@/types/api'
import { mockAccounts } from './data/accounts'
import { createDemoAdapter } from './index'

describe('demo SCPI fund links', () => {
  it('persists links, preserves absent codes and detaches explicit blanks on an existing account', async () => {
    const account = mockAccounts.find(account => account.type === 'SCPI')!
    const initial = account.scpi!
    const adapter = createDemoAdapter()
    const save = async (data: ScpiPositionRequest) => (await adapter({
      method: 'PUT', url: `/accounts/${account.id}/scpi`,
      headers: new AxiosHeaders(), data: JSON.stringify(data),
    })).data as ScpiPosition
    const read = async () => (await adapter({
      method: 'GET', url: `/accounts/${account.id}`, headers: new AxiosHeaders(),
    })).data as Account
    try {
      const linked = await save({ ...initial, corumFundCode: 'FUND-42', sofidyFundCode: 'DY' })
      expect(linked.corumFundCode).toBe('FUND-42')
      expect(linked.sofidyFundCode).toBe('DY')
      expect((await read()).scpi).toEqual(linked)

      const corrected = await save({
        ...initial, shareCount: 12, corumFundCode: undefined, sofidyFundCode: undefined,
      })
      expect(corrected.corumFundCode).toBe('FUND-42')
      expect(corrected.sofidyFundCode).toBe('DY')
      expect((await read()).currentBalanceEur).toBe(10560)
      const summary = (await adapter({
        method: 'GET', url: '/real-estate/summary', headers: new AxiosHeaders(),
      })).data as RealEstateSummary
      expect(summary.paperGross).toBe(10560)
      expect(summary.paper[0].shareCount).toBe(12)

      const detached = await save({ ...initial, corumFundCode: '', sofidyFundCode: '' })
      expect(detached.corumFundCode).toBeNull()
      expect(detached.sofidyFundCode).toBeNull()
      expect((await read()).scpi).toEqual(detached)
    } finally {
      await save({ ...initial, corumFundCode: '', sofidyFundCode: '' })
    }
  })
})
