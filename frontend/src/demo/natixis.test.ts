import { afterEach, describe, expect, it, vi } from 'vitest'
import { AxiosHeaders } from 'axios'
import { createDemoAdapter } from './index'
import type { NatixisAuthInitResponse, NatixisSessionStatus } from '@/types/api'

const disconnected: NatixisSessionStatus = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
}

function call(method: string, url: string) {
  return createDemoAdapter()({ method, url, headers: new AxiosHeaders() }).then(r => r.data)
}

describe('Natixis demo connector', () => {
  afterEach(() => vi.restoreAllMocks())

  // Sync all lists Natixis whenever its status is not IDLE, so a status missing
  // syncStatus would show a phantom expired connection in demo mode.
  it('reports no Natixis session', async () => {
    expect(await call('GET', '/natixis/status')).toEqual(disconnected)
  })

  it('answers auth and sync without opening a session', async () => {
    const init: NatixisAuthInitResponse = { processId: null, mfaRequired: false, mfaType: null }
    expect(await call('POST', '/natixis/auth/initiate')).toEqual(init)
    expect(await call('POST', '/natixis/auth/complete')).toEqual(disconnected)
    expect(await call('POST', '/natixis/sync')).toEqual(disconnected)
  })

  it('registers every endpoint the Natixis API calls', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    await call('GET', '/natixis/status')
    await call('POST', '/natixis/auth/initiate')
    await call('POST', '/natixis/auth/complete')
    await call('POST', '/natixis/sync')
    await call('DELETE', '/natixis/session')
    expect(warn).not.toHaveBeenCalled()
  })
})
