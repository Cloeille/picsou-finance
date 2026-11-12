import { AxiosHeaders } from 'axios'
import { describe, expect, it } from 'vitest'
import type { TrAuthInitResponse, TrSessionStatus } from '@/types/api'
import { createDemoAdapter } from './index'

const inactiveSession = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
  maskedPhone: '+33 * ** ** ** 78',
} satisfies TrSessionStatus

const initiated = {
  processId: 'demo-tr-process',
  flow: 'confirmInApp',
  countdownInSeconds: 120,
} satisfies TrAuthInitResponse

describe('demo Trade Republic session contracts', () => {
  const adapter = createDemoAdapter()

  it.each([
    { method: 'GET', url: '/tr/status', expected: inactiveSession },
    { method: 'POST', url: '/tr/auth/initiate', expected: initiated },
    { method: 'POST', url: '/tr/auth/reconnect', expected: initiated },
    { method: 'POST', url: '/tr/auth/complete', expected: inactiveSession },
    { method: 'POST', url: '/tr/sync', expected: inactiveSession },
    { method: 'DELETE', url: '/tr/session', expected: null },
  ])('answers $method $url', async ({ method, url, expected }) => {
    const response = await adapter({ method, url, headers: new AxiosHeaders() })

    expect(response.status).toBe(200)
    expect(response.data).toEqual(expected)
  })
})
