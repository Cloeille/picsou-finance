import { AxiosHeaders } from 'axios'
import { describe, expect, it } from 'vitest'
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
    { method: 'POST', url: '/banque-populaire/sync', expected: inactiveSession },
    {
      method: 'DELETE', url: '/banque-populaire/session',
      expected: { removed: true, bankSessionRevoked: false, message: '' } satisfies BanquePopulaireDeleteResponse,
    },
  ])('answers $method $url', async ({ method, url, expected }) => {
    const response = await adapter({ method, url, headers: new AxiosHeaders() })

    expect(response.status).toBe(200)
    expect(response.data).toEqual(expected)
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
