import { describe, expect, it, vi } from 'vitest'

vi.mock('@/lib/api-client', () => ({
  api: { get: vi.fn().mockResolvedValue({ data: { currentVersion: '1.1.0', update: null, issues: [] } }) },
}))

import { api } from '@/lib/api-client'
import { adminApi } from './api'

describe('adminApi.getInstanceStatus', () => {
  it('opts out of the global error redirect, since the check is optional', async () => {
    await adminApi.getInstanceStatus()
    expect(api.get).toHaveBeenCalledWith('/admin/instance-status', { skipGlobalErrorRedirect: true })
  })
})
