import { describe, expect, it } from 'vitest'
import { AxiosHeaders } from 'axios'
import { createDemoAdapter } from './index'
import { mockAccounts } from './data/accounts'
import type { Account, RealEstateSummary } from '@/types/api'

describe('SCPI demo integration', () => {
  it('does not reuse the id of a pre-existing account', () => {
    const ids = mockAccounts.map(account => account.id)
    expect(new Set(ids).size).toBe(ids.length)
  })

  it('links the paper-property summary to the SCPI, not a Revolut pocket', async () => {
    const adapter = createDemoAdapter()
    const summary = await adapter({
      method: 'GET', url: '/real-estate/summary', headers: new AxiosHeaders(),
    })
    const paper = (summary.data as RealEstateSummary).paper[0]
    const detail = await adapter({
      method: 'GET', url: `/accounts/${paper.accountId}`, headers: new AxiosHeaders(),
    })
    const account = detail.data as Account
    expect(account.type).toBe('SCPI')
    expect(account.name).toBe(paper.name)
    expect(account.currentBalanceEur).toBe(paper.grossValue)
  })
})
