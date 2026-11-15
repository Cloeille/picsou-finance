import { describe, expect, it } from 'vitest'
import { AxiosHeaders } from 'axios'
import { createDemoAdapter } from './index'
import type { Account } from '@/types/api'

function put(adapter: ReturnType<typeof createDemoAdapter>, url: string, body: unknown) {
  return adapter({
    method: 'PUT', url, headers: new AxiosHeaders(), data: JSON.stringify(body),
  })
}

describe('display prefs demo integration', () => {
  it('persists a collapse flip through the refetch that follows the mutation', async () => {
    const adapter = createDemoAdapter()
    const updated = await put(adapter, '/accounts/1/display', { collapsed: true })
    expect((updated.data as Account).listCollapsed).toBe(true)

    const list = await adapter({ method: 'GET', url: '/accounts', headers: new AxiosHeaders() })
    expect((list.data as Account[]).find(a => a.id === 1)?.listCollapsed).toBe(true)
    const detail = await adapter({ method: 'GET', url: '/accounts/1', headers: new AxiosHeaders() })
    expect((detail.data as Account).listCollapsed).toBe(true)

    await put(adapter, '/accounts/1/display', { collapsed: false })
    const reverted = await adapter({ method: 'GET', url: '/accounts', headers: new AxiosHeaders() })
    expect((reverted.data as Account[]).find(a => a.id === 1)?.listCollapsed).toBe(false)
  })

  it('persists the manual order and clears positions for unlisted accounts', async () => {
    const adapter = createDemoAdapter()
    await put(adapter, '/accounts/display-order', { accountIds: [3, 1] })

    const list = await adapter({ method: 'GET', url: '/accounts', headers: new AxiosHeaders() })
    const accounts = list.data as Account[]
    expect(accounts.find(a => a.id === 3)?.listPosition).toBe(0)
    expect(accounts.find(a => a.id === 1)?.listPosition).toBe(1)
    expect(accounts.find(a => a.id === 2)?.listPosition).toBeNull()
    const detail = await adapter({ method: 'GET', url: '/accounts/3', headers: new AxiosHeaders() })
    expect((detail.data as Account).listPosition).toBe(0)

    await put(adapter, '/accounts/display-order', { accountIds: [] })
    const cleared = await adapter({ method: 'GET', url: '/accounts', headers: new AxiosHeaders() })
    expect((cleared.data as Account[]).every(a => a.listPosition == null)).toBe(true)
  })
})
