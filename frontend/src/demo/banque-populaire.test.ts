import { AxiosHeaders } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  Account,
  BanquePopulaireAuthCompleteResponse,
  BanquePopulaireAuthKeypadResponse,
  BanquePopulaireDeleteResponse,
  BanquePopulaireSessionStatus,
  DashboardData,
} from '@/types/api'
import { mockAccounts, mockBanquePopulaireAccounts } from './data/accounts'
import { createDemoAdapter } from './index'

const inactiveSession = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
  unsupported: [],
  unsupportedCount: 0,
} satisfies BanquePopulaireSessionStatus

describe('demo Banque Populaire session contracts', () => {
  const adapter = createDemoAdapter()
  const call = (method: string, url: string) =>
    adapter({ method, url, headers: new AxiosHeaders() }).then((response) => response.data)

  afterEach(() => {
    vi.useRealTimers()
  })

  it.each([
    { method: 'GET', url: '/banque-populaire/status', expected: inactiveSession },
    {
      method: 'POST', url: '/banque-populaire/auth/keypad',
      expected: { processId: 'demo', status: 'SECURPASS_PENDING' } satisfies BanquePopulaireAuthKeypadResponse,
    },
    {
      method: 'POST', url: '/banque-populaire/auth/complete',
      expected: { connected: true } satisfies BanquePopulaireAuthCompleteResponse,
    },
    {
      method: 'DELETE', url: '/banque-populaire/session',
      expected: { removed: true, bankSessionRevoked: false, message: '' } satisfies BanquePopulaireDeleteResponse,
    },
  ])('answers $method $url', async ({ method, url, expected }) => {
    const response = await adapter({ method, url, headers: new AxiosHeaders() })

    expect(response.status).toBe(200)
    expect(response.data).toEqual(expected)
  })

  it('reports SUCCESS only after the first sync, and idle again after disconnect', async () => {
    await call('POST', '/banque-populaire/auth/complete')
    // Live but not synced yet: a status read before the sync never shows SUCCESS.
    expect(await call('GET', '/banque-populaire/status')).toMatchObject({ isActive: true, syncStatus: 'IDLE' })
    const synced = await call('POST', '/banque-populaire/sync')
    expect(synced).toMatchObject({ isActive: true, syncStatus: 'SUCCESS', lastSyncError: null })
    expect(await call('GET', '/banque-populaire/status')).toMatchObject({ isActive: true, syncStatus: 'SUCCESS' })

    await call('DELETE', '/banque-populaire/session')
    expect(await call('GET', '/banque-populaire/status')).toEqual(inactiveSession)
  })

  it('stamps the sync time once, whatever the number of status reads', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-11-05T10:00:00Z'))
    await call('DELETE', '/banque-populaire/session')
    await call('POST', '/banque-populaire/auth/complete')

    const synced = await call('POST', '/banque-populaire/sync')
    vi.setSystemTime(new Date('2026-11-05T10:05:00Z'))
    const firstRead = await call('GET', '/banque-populaire/status')
    vi.setSystemTime(new Date('2026-11-05T10:10:00Z'))
    const secondRead = await call('GET', '/banque-populaire/status')

    expect(synced.lastSyncCompletedAt).toBe('2026-11-05T10:00:00.000Z')
    expect(firstRead).toMatchObject({
      lastSyncStartedAt: '2026-11-05T10:00:00.000Z',
      lastSyncCompletedAt: '2026-11-05T10:00:00.000Z',
    })
    expect(secondRead).toEqual(firstRead)

    const resynced = await call('POST', '/banque-populaire/sync')
    expect(resynced.lastSyncCompletedAt).toBe('2026-11-05T10:10:00.000Z')
    expect(await call('GET', '/banque-populaire/status')).toEqual(resynced)

    await call('DELETE', '/banque-populaire/session')
    expect(await call('GET', '/banque-populaire/status')).toMatchObject({
      lastSyncStartedAt: null,
      lastSyncCompletedAt: null,
    })
  })

  it('answers initiate with a ten-key pad, whatever the region', async () => {
    const response = await adapter({
      method: 'POST',
      url: '/banque-populaire/auth/initiate',
      data: JSON.stringify({ customerId: '123', region: 'BRED' }),
      headers: new AxiosHeaders(),
    })

    expect(response.status).toBe(200)
    expect(response.data.processId).toBe('demo')
    expect(response.data.keypad.images).toHaveLength(10)
    expect(response.data.expiresInSeconds).toBe(90)
  })
})

describe('demo Banque Populaire accounts', () => {
  let call: <T = unknown>(method: string, url: string) => Promise<T>
  const fixtureIds = mockBanquePopulaireAccounts.map((account) => account.id)
  const demoAccounts = () => call<Account[]>('GET', '/accounts')
  const idsOf = (accounts: Account[]) => accounts.map((account) => account.id)

  beforeEach(async () => {
    vi.resetModules()
    const { createDemoAdapter: freshAdapter } = await import('./index')
    const adapter = freshAdapter()
    call = <T = unknown>(method: string, url: string) =>
      adapter({ method, url, headers: new AxiosHeaders() }).then((response) => response.data as T)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('takes ids no other demo account uses', () => {
    const taken = new Set(mockAccounts.map((account) => account.id))
    expect(fixtureIds.every((id) => !taken.has(id))).toBe(true)
    expect(new Set(fixtureIds).size).toBe(fixtureIds.length)
  })

  it('adds a current account and a Livret A, stamped with the sync time, on the first sync only', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-11-05T10:00:00Z'))
    const before = await demoAccounts()
    expect(before.filter((account) => fixtureIds.includes(account.id))).toEqual([])

    await call('POST', '/banque-populaire/auth/complete')
    await call('POST', '/banque-populaire/sync')
    const after = await demoAccounts()

    expect(before).toHaveLength(mockAccounts.length)
    expect(after).toHaveLength(mockAccounts.length + fixtureIds.length)
    const added = after.filter((account) => fixtureIds.includes(account.id))
    expect(added.map((account) => account.type).sort()).toEqual(['CHECKING', 'LIVRET_A'])
    for (const account of added) {
      expect(account).toMatchObject({
        provider: 'Banque Populaire Rives de Paris',
        isManual: false,
        currency: 'EUR',
        createdAt: '2026-11-05T10:00:00.000Z',
        lastSyncedAt: '2026-11-05T10:00:00.000Z',
      })
      expect(await call('GET', `/accounts/${account.id}`)).toEqual(account)
      expect(await call('GET', `/accounts/${account.id}/transactions`)).toEqual([])
    }

    // A later sync adds nothing, keeps createdAt and restamps lastSyncedAt.
    vi.setSystemTime(new Date('2026-11-05T10:10:00Z'))
    await call('POST', '/banque-populaire/sync')
    const resynced = await demoAccounts()
    expect(idsOf(resynced)).toEqual(idsOf(after))
    for (const account of resynced.filter((a) => fixtureIds.includes(a.id))) {
      expect(account).toMatchObject({
        createdAt: '2026-11-05T10:00:00.000Z',
        lastSyncedAt: '2026-11-05T10:10:00.000Z',
      })
    }
    expect(resynced.filter((a) => !fixtureIds.includes(a.id)))
      .toEqual(after.filter((a) => !fixtureIds.includes(a.id)))
    // Every demo call waits 200-600 ms, and this one makes about ten.
  }, 15_000)

  it('keeps one of each account through a disconnect, a reconnect and a new sync', async () => {
    await call('POST', '/banque-populaire/auth/complete')
    await call('POST', '/banque-populaire/sync')
    await call('POST', '/banque-populaire/sync')
    await call('DELETE', '/banque-populaire/session')
    expect(idsOf(await demoAccounts())).toEqual(expect.arrayContaining(fixtureIds))

    await call('POST', '/banque-populaire/auth/complete')
    await call('POST', '/banque-populaire/sync')

    const ids = idsOf(await demoAccounts())
    for (const id of fixtureIds) expect(ids.filter((accountId) => accountId === id)).toHaveLength(1)
    expect(new Set(ids).size).toBe(ids.length)
  })

  it('counts the new accounts in the dashboard and gives them a balance history', async () => {
    await call('POST', '/banque-populaire/auth/complete')
    await call('POST', '/banque-populaire/sync')

    const dashboard = await call<DashboardData>('GET', '/dashboard')
    expect(dashboard.distribution.map((row) => row.accountId)).toEqual(expect.arrayContaining(fixtureIds))
    for (const account of mockBanquePopulaireAccounts) {
      const history = await call<{ balance: number }[]>('GET', `/accounts/${account.id}/history`)
      expect(history.at(-1)?.balance).toBe(account.currentBalanceEur)
      expect(history.length).toBeGreaterThan(1)
    }
  })
})
