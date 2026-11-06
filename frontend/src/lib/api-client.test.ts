import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import type { AxiosAdapter } from 'axios'

// zustand's `persist` (profile-store) needs a working localStorage; jsdom here
// doesn't provide one, so install a tiny in-memory shim before importing stores.
function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => void m.set(k, String(v)),
    removeItem: (k) => void m.delete(k),
    clear: () => m.clear(),
    key: (i) => [...m.keys()][i] ?? null,
    get length() { return m.size },
  } as Storage
}
vi.stubGlobal('localStorage', memoryStorage())
vi.stubGlobal('sessionStorage', memoryStorage())

const { api, isSetupRequiredResponse } = await import('./api-client')
const { useAuthStore } = await import('@/stores/auth-store')
const { useProfileStore } = await import('@/stores/profile-store')
const { consumeSsoRevalidate } = await import('./sso-revalidate')

describe('isSetupRequiredResponse', () => {
  it('detects setup-required ProblemDetail code responses', () => {
    expect(isSetupRequiredResponse(503, {
      code: 'setup_required',
      detail: 'Picsou is not configured yet.',
    })).toBe(true)
  })

  it('keeps compatibility with setup-required detail responses', () => {
    expect(isSetupRequiredResponse(503, { detail: 'setup_required' })).toBe(true)
  })

  it('ignores generic 503 responses', () => {
    expect(isSetupRequiredResponse(503, { detail: 'Connector unavailable' })).toBe(false)
  })

  it('requires a 503 status', () => {
    expect(isSetupRequiredResponse(502, { code: 'setup_required' })).toBe(false)
  })
})

/**
 * The request interceptor must only attach `?memberId=` for admins (the backend
 * ignores the override for non-admins and rejects it for activated members). This
 * stops a stale persisted `activeMemberId` on a shared browser from ever scoping a
 * regular member's requests to someone else's data.
 */
describe('api-client memberId interceptor', () => {
  let captured: Record<string, unknown> | undefined

  beforeEach(() => {
    captured = undefined
    // Capture the outgoing params instead of hitting the network.
    const echoAdapter: AxiosAdapter = async (config) => {
      captured = config.params
      return {
        data: null,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      }
    }
    api.defaults.adapter = echoAdapter
  })

  afterEach(() => {
    useAuthStore.getState().logout()
    useProfileStore.getState().reset()
  })

  function asUser(role: 'ADMIN' | 'MEMBER') {
    useAuthStore.getState().login({ username: 'u', role, memberId: 1, displayName: 'U' })
  }

  it('attaches memberId when an admin is impersonating a managed profile', async () => {
    asUser('ADMIN')
    useProfileStore.getState().setActiveMember(5)
    await api.get('/dashboard')
    expect(captured?.memberId).toBe(5)
  })

  it('does NOT attach memberId for a non-admin, even with a stale activeMemberId', async () => {
    asUser('MEMBER')
    useProfileStore.getState().setActiveMember(5)
    await api.get('/dashboard')
    expect(captured?.memberId).toBeUndefined()
  })

  it('does NOT attach memberId when no profile is active', async () => {
    asUser('ADMIN')
    useProfileStore.getState().reset()
    await api.get('/dashboard')
    expect(captured?.memberId).toBeUndefined()
  })

  it('does not redirect global 5xx errors when a GET opts out', async () => {
    const href = window.location.href
    api.defaults.adapter = async (config) => Promise.reject({
      config,
      response: { status: 502, data: { detail: 'Connector unavailable' } },
    })

    await expect(
      api.get('/sync/institutions', { params: { query: 'boursobank' }, skipGlobalErrorRedirect: true }),
    ).rejects.toMatchObject({ response: { status: 502 } })

    expect(window.location.href).toBe(href)
  })
})

/**
 * The 401 refresh loop is the SSO session's death detector: when the backend refuses the
 * refresh with SSO_REVALIDATE (the re-validation window expired, todo 14), the client must
 * mark the one-shot flag so /login restarts SSO silently instead of showing a bare form.
 */
describe('api-client SSO revalidation flag', () => {
  let original: Location

  beforeEach(() => {
    sessionStorage.clear()
    original = window.location
    Object.defineProperty(window, 'location', {
      value: { assign: vi.fn(), href: 'http://localhost/', pathname: '/', search: '' },
      configurable: true,
      writable: true,
    })
  })

  afterEach(() => {
    Object.defineProperty(window, 'location', { value: original, configurable: true })
  })

  // Real axios errors carry their request config, which the interceptor reads.
  function failEveryRequest(refreshCode: string) {
    api.defaults.adapter = async (config) =>
      Promise.reject({
        config,
        response: { status: 401, data: config.url === '/auth/refresh' ? { code: refreshCode } : {} },
      })
  }

  it('marks the flag when the refresh fails with SSO_REVALIDATE', async () => {
    failEveryRequest('SSO_REVALIDATE')

    await expect(api.get('/dashboard')).rejects.toMatchObject({ response: { status: 401 } })

    expect(consumeSsoRevalidate()).toBe(true)
  })

  it('does not mark the flag when the refresh fails for another reason', async () => {
    failEveryRequest('SESSION_EXPIRED')

    await expect(api.get('/dashboard')).rejects.toMatchObject({ response: { status: 401 } })

    expect(consumeSsoRevalidate()).toBe(false)
  })

  it('refreshes an expired session for the SSO sign-in-method calls and retries them', async () => {
    const calls: string[] = []
    let refreshed = false
    api.defaults.adapter = async (config) => {
      calls.push(`${config.method} ${config.url}`)
      if (config.url === '/auth/refresh') refreshed = true
      else if (!refreshed) return Promise.reject({ config, response: { status: 401, data: {} } })
      return { data: { hasPassword: true }, status: 200, statusText: 'OK', headers: {}, config }
    }

    await expect(api.get('/auth/sign-in-methods')).resolves.toMatchObject({ data: { hasPassword: true } })

    expect(calls).toEqual(['get /auth/sign-in-methods', 'post /auth/refresh', 'get /auth/sign-in-methods'])
  })

  it('never refreshes after a rejected re-authentication, so a wrong password is not replayed', async () => {
    const calls: string[] = []
    api.defaults.adapter = async (config) => {
      calls.push(`${config.method} ${config.url}`)
      return Promise.reject({ config, response: { status: 401, data: { code: 'REAUTH_FAILED' } } })
    }

    await expect(api.post('/auth/reauth', { password: 'wrong' })).rejects.toMatchObject({ response: { status: 401 } })

    expect(calls).toEqual(['post /auth/reauth'])
  })

  it('refreshes an expired session for the password re-authentication and retries it', async () => {
    const calls: string[] = []
    let refreshed = false
    api.defaults.adapter = async (config) => {
      calls.push(`${config.method} ${config.url}`)
      if (config.url === '/auth/refresh') refreshed = true
      else if (!refreshed) return Promise.reject({ config, response: { status: 401, data: {} } })
      return { data: '', status: 204, statusText: 'No Content', headers: {}, config }
    }

    await expect(api.post('/auth/reauth', { password: 'right' })).resolves.toMatchObject({ status: 204 })

    expect(calls).toEqual(['post /auth/reauth', 'post /auth/refresh', 'post /auth/reauth'])
  })

  it.each([
    ['put', '/auth/password'],
    ['delete', '/auth/password'],
    ['delete', '/auth/identities/7'],
  ])('hands a refused re-authentication on %s %s straight to the caller, without a refresh', async (method, url) => {
    const calls: string[] = []
    api.defaults.adapter = async (config) => {
      calls.push(`${config.method} ${config.url}`)
      return Promise.reject({ config, response: { status: 401, data: { code: 'REAUTH_FAILED' } } })
    }

    await expect(api.request({ method, url })).rejects.toMatchObject({ response: { data: { code: 'REAUTH_FAILED' } } })

    expect(calls).toEqual([`${method} ${url}`])
  })
})
