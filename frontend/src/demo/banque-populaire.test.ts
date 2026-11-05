import { AxiosHeaders } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type {
  BanquePopulaireAuthCompleteResponse,
  BanquePopulaireAuthKeypadResponse,
  BanquePopulaireDeleteResponse,
  BanquePopulaireSessionStatus,
} from '@/types/api'
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
