import { describe, expect, it, vi } from 'vitest'
import { AxiosHeaders } from 'axios'
import { createDemoAdapter } from './index'
import type { SessionItem } from '@/features/mfa/api'

describe('demo sessions', () => {
  it('serves opaque string ids with a kind, including the iPhone app', async () => {
    const adapter = createDemoAdapter()
    const response = await adapter({ method: 'GET', url: '/auth/sessions', headers: new AxiosHeaders() })
    const sessions = response.data as SessionItem[]

    expect(sessions.map(s => [typeof s.id, s.kind, s.current])).toEqual([
      ['string', 'REMEMBER_ME', true],
      ['string', 'IOS_APP', false],
    ])
  })

  it('revokes the iPhone app session by its opaque id', async () => {
    const adapter = createDemoAdapter()
    const response = await adapter({ method: 'GET', url: '/auth/sessions', headers: new AxiosHeaders() })
    const ios = (response.data as SessionItem[]).find(s => s.kind === 'IOS_APP')!

    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})

    await adapter({ method: 'DELETE', url: `/auth/sessions/${ios.id}`, headers: new AxiosHeaders() })

    expect(warn).not.toHaveBeenCalled()
    warn.mockRestore()
  })
})
