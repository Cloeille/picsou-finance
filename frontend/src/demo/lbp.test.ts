import { AxiosHeaders } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  LbpAuthCompleteResponse,
  LbpAuthInitResponse,
  LbpAuthKeypadResponse,
  LbpDeleteResponse,
  LbpSessionStatus,
} from '@/types/api'

const idleSession = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
  unsupported: [],
  unsupportedCount: 0,
  savedIdentifier: null,
} satisfies LbpSessionStatus

describe('demo LBP session contracts', () => {
  let call: <T = unknown>(method: string, url: string) => Promise<T>

  beforeEach(async () => {
    vi.resetModules()
    const { createDemoAdapter } = await import('./index')
    const adapter = createDemoAdapter()
    call = <T = unknown>(method: string, url: string) =>
      adapter({ method, url, headers: new AxiosHeaders() }).then((response) => response.data as T)
  })

  it.each([
    { method: 'GET', url: '/lbp/status', expected: idleSession },
    {
      method: 'POST', url: '/lbp/auth/initiate',
      expected: {
        processId: 'demo',
        keypad: { keys: ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'], columns: 5 },
        expiresInSeconds: 300,
      } satisfies LbpAuthInitResponse,
    },
    {
      method: 'POST', url: '/lbp/auth/keypad',
      expected: { processId: 'demo', status: 'APPROVAL_PENDING' } satisfies LbpAuthKeypadResponse,
    },
    {
      method: 'DELETE', url: '/lbp/session',
      expected: { removed: true, bankSessionRevoked: false, message: '' } satisfies LbpDeleteResponse,
    },
  ])('answers $method $url', async ({ method, url, expected }) => {
    expect(await call(method, url)).toEqual(expected)
  })

  it('walks the keypad-approval flow: approval then sync, idle again after disconnect', async () => {
    expect(await call<LbpSessionStatus>('GET', '/lbp/status')).toEqual(idleSession)

    await call('POST', '/lbp/auth/initiate')
    expect(await call<LbpAuthKeypadResponse>('POST', '/lbp/auth/keypad')).toEqual({
      processId: 'demo',
      status: 'APPROVAL_PENDING',
    })

    // The mobile approval is a human step: complete activates without syncing yet.
    const completed = await call<LbpAuthCompleteResponse>('POST', '/lbp/auth/complete')
    expect(completed).toMatchObject({ isActive: true, syncStatus: 'IDLE', lastSyncCompletedAt: null })
    expect(await call<LbpSessionStatus>('GET', '/lbp/status')).toMatchObject({
      isActive: true,
      syncStatus: 'IDLE',
    })

    const synced = await call<LbpSessionStatus>('POST', '/lbp/sync')
    expect(synced).toMatchObject({
      isActive: true,
      syncStatus: 'SUCCESS',
      lastSyncError: null,
      lastSyncCompletedAt: '2026-11-06T12:00:00Z',
    })
    expect(await call<LbpSessionStatus>('GET', '/lbp/status')).toEqual(synced)

    await call('DELETE', '/lbp/session')
    expect(await call<LbpSessionStatus>('GET', '/lbp/status')).toEqual(idleSession)
  })

  it('registers every endpoint the LBP API calls', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      await call('GET', '/lbp/status')
      await call('POST', '/lbp/auth/initiate')
      await call('POST', '/lbp/auth/keypad')
      await call('POST', '/lbp/auth/complete')
      await call('POST', '/lbp/sync')
      await call('DELETE', '/lbp/session')
      expect(warn).not.toHaveBeenCalled()
    } finally {
      warn.mockRestore()
    }
  })
})
