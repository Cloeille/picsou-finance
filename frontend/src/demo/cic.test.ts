import { AxiosHeaders } from 'axios'
import { describe, expect, it } from 'vitest'
import type { CicAuthInitResponse, CicSessionStatus } from '@/types/api'
import { createDemoAdapter } from './index'

const inactiveSession = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
  canReconnect: false,
} satisfies CicSessionStatus

const noConfirmation = {
  processId: null,
  mfaRequired: false,
  mfaType: null,
} satisfies CicAuthInitResponse

describe('demo CIC session contracts', () => {
  const adapter = createDemoAdapter()

  it.each([
    { method: 'GET', url: '/cic/status', expected: inactiveSession },
    { method: 'POST', url: '/cic/auth/initiate', expected: noConfirmation },
    { method: 'POST', url: '/cic/auth/reconnect', expected: noConfirmation },
    { method: 'POST', url: '/cic/auth/complete', expected: inactiveSession },
    { method: 'POST', url: '/cic/sync', expected: inactiveSession },
    { method: 'DELETE', url: '/cic/session', expected: null },
  ])('answers $method $url', async ({ method, url, expected }) => {
    const response = await adapter({ method, url, headers: new AxiosHeaders() })

    expect(response.status).toBe(200)
    expect(response.data).toEqual(expected)
  })
})
