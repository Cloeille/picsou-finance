import { beforeEach, describe, expect, it } from 'vitest'
import {
  consumeSsoRevalidate,
  markSsoRevalidate,
  rememberSsoPreference,
  ssoRememberPreference,
} from './sso-revalidate'

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
})
