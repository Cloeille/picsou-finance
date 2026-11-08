import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

function memoryStorage(): Storage {
  const data = new Map<string, string>()
  return {
    get length() { return data.size },
    clear: () => data.clear(),
    getItem: (k) => data.get(k) ?? null,
    key: (i) => [...data.keys()][i] ?? null,
    removeItem: (k) => { data.delete(k) },
    setItem: (k, v) => { data.set(k, String(v)) },
  }
}

// The auth store reads sessionStorage when it loads, so the storages exist before the import.
vi.stubGlobal('sessionStorage', memoryStorage())
vi.stubGlobal('localStorage', memoryStorage())

const {
  consumeSsoRevalidate,
  markSsoRevalidate,
  rememberSsoPreference,
  restartSsoRevalidation,
  ssoRememberPreference,
} = await import('./sso-revalidate')
const { useAuthStore } = await import('@/stores/auth-store')

beforeEach(() => {
  Object.defineProperty(globalThis, 'sessionStorage', { value: memoryStorage(), configurable: true })
  Object.defineProperty(globalThis, 'localStorage', { value: memoryStorage(), configurable: true })
})

describe('sso revalidation flag', () => {
  it('is false until marked', () => {
    expect(consumeSsoRevalidate()).toBe(false)
  })

  it('is consumed once', () => {
    markSsoRevalidate()
    expect(consumeSsoRevalidate()).toBe(true)
    expect(consumeSsoRevalidate()).toBe(false)
  })
})

describe('sso remember preference', () => {
  it('defaults to false and round-trips both values', () => {
    expect(ssoRememberPreference()).toBe(false)
    rememberSsoPreference(true)
    expect(ssoRememberPreference()).toBe(true)
    rememberSsoPreference(false)
    expect(ssoRememberPreference()).toBe(false)
  })

  it('never throws when the browser refuses to store it, so the sign-in still starts', () => {
    const refusing: Storage = {
      ...memoryStorage(),
      setItem: () => { throw new DOMException('The quota has been exceeded.', 'QuotaExceededError') },
    }
    Object.defineProperty(globalThis, 'localStorage', { value: refusing, configurable: true })

    expect(() => rememberSsoPreference(true)).not.toThrow()
  })

  it('never leaves an earlier Remember me in force when the browser refuses to store an unchecked one', () => {
    rememberSsoPreference(true)
    vi.spyOn(localStorage, 'setItem').mockImplementation(() => {
      throw new DOMException('The quota has been exceeded.', 'QuotaExceededError')
    })

    rememberSsoPreference(false)

    expect(ssoRememberPreference()).toBe(false)
  })
})

describe('restartSsoRevalidation', () => {
  let original: Location

  beforeEach(() => {
    original = window.location
    Object.defineProperty(window, 'location', { value: { assign: vi.fn() }, configurable: true, writable: true })
  })

  afterEach(() => {
    Object.defineProperty(window, 'location', { value: original, configurable: true })
  })

  it('signs out locally, marks the flag and lets /login restart SSO back to the page', () => {
    useAuthStore.getState().login({ username: 'alice', role: 'MEMBER', memberId: 1, displayName: 'Alice' })

    restartSsoRevalidation('/settings')

    expect(window.location.assign).toHaveBeenCalledWith('/login?redirect=%2Fsettings')
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
    expect(consumeSsoRevalidate()).toBe(true)
  })

  it('still signs out and leaves for /login when the flag cannot be stored', () => {
    useAuthStore.getState().login({ username: 'alice', role: 'MEMBER', memberId: 1, displayName: 'Alice' })
    vi.spyOn(sessionStorage, 'setItem').mockImplementation(() => {
      throw new DOMException('The quota has been exceeded.', 'QuotaExceededError')
    })

    restartSsoRevalidation('/settings')

    expect(window.location.assign).toHaveBeenCalledWith('/login?redirect=%2Fsettings')
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
  })
})
