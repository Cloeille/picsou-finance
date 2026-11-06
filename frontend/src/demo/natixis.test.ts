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
  afterEach(async () => {
    vi.restoreAllMocks()
    await call('DELETE', '/natixis/session')
  })

  // Sync all lists Natixis whenever its status is not IDLE, so a status missing
  // syncStatus would show a phantom expired connection in demo mode.
  it('reports no Natixis session', async () => {
    expect(await call('GET', '/natixis/status')).toEqual(disconnected)
  })

  it('simulates the SMS challenge, then an active session until it is deleted', async () => {
    await call('DELETE', '/natixis/session')
    const init: NatixisAuthInitResponse = { processId: 'demo-natixis', mfaRequired: true, mfaType: 'SMS' }
    expect(await call('POST', '/natixis/auth/initiate')).toEqual(init)
    expect(await call('GET', '/natixis/status')).toEqual(disconnected)

    const done = await call('POST', '/natixis/auth/complete')
    expect(done).toMatchObject({ isActive: true, syncStatus: 'SUCCESS', lastSyncError: null })
    expect(done.lastSyncCompletedAt).toEqual(expect.any(String))
    expect(await call('GET', '/natixis/status')).toMatchObject({ isActive: true, syncStatus: 'SUCCESS' })
    expect(await call('POST', '/natixis/sync')).toMatchObject({ isActive: true, syncStatus: 'SUCCESS' })

    await call('DELETE', '/natixis/session')
    expect(await call('GET', '/natixis/status')).toEqual(disconnected)
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
