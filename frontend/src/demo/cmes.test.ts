import { afterEach, describe, expect, it, vi } from 'vitest'
import { AxiosHeaders } from 'axios'
import { createDemoAdapter } from './index'
import type { CmesAuthInitResponse, CmesSessionStatus } from '@/types/api'

const disconnected: CmesSessionStatus = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
}

function call(method: string, url: string) {
  return createDemoAdapter()({ method, url, headers: new AxiosHeaders() }).then(r => r.data)
}

describe('CMES demo connector', () => {
  afterEach(() => vi.restoreAllMocks())

  // Sync all lists CMES whenever its status is not IDLE, so a status missing
  // syncStatus would show a phantom expired connection in demo mode.
  it('reports no CMES session', async () => {
    expect(await call('GET', '/cmes/status')).toEqual(disconnected)
  })

  it('answers auth and sync without opening a session', async () => {
    const init: CmesAuthInitResponse = { processId: null, mfaRequired: false, mfaType: null }
    expect(await call('POST', '/cmes/auth/initiate')).toEqual(init)
    expect(await call('POST', '/cmes/auth/complete')).toEqual(disconnected)
    expect(await call('POST', '/cmes/sync')).toEqual(disconnected)
  })

  it('registers every endpoint the CMES API calls', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    await call('GET', '/cmes/status')
    await call('POST', '/cmes/auth/initiate')
    await call('POST', '/cmes/auth/complete')
    await call('POST', '/cmes/sync')
    await call('DELETE', '/cmes/session')
    expect(warn).not.toHaveBeenCalled()
  })
})
