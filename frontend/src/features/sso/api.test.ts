import { describe, expect, it, vi } from 'vitest'

vi.mock('@/lib/api-client', () => ({
  api: { get: vi.fn().mockResolvedValue({ data: { enabled: true, providerName: 'SSO' } }) },
}))

import { api } from '@/lib/api-client'
import { ssoApi } from './api'

describe('ssoApi.getInfo', () => {
  it('opts out of the global 5xx redirect, so the page can show its retry banner', async () => {
    await ssoApi.getInfo()
    expect(api.get).toHaveBeenCalledWith('/auth/sso', { skipGlobalErrorRedirect: true })
  })
})
