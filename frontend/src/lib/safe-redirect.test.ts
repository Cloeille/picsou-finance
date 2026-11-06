import { describe, expect, it, vi } from 'vitest'

// utils.ts pulls in stores/app-store (zustand persist), which reads localStorage at
// module eval; install in-memory shims before the dynamic import (node/jsdom gap).
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

const { safeRedirect } = await import('./utils')

describe('safeRedirect', () => {
  it('returns the fallback for null, empty and non-path values', () => {
    expect(safeRedirect(null)).toBe('/')
    expect(safeRedirect('')).toBe('/')
    expect(safeRedirect('settings')).toBe('/')
    expect(safeRedirect('https://evil.com')).toBe('/')
    expect(safeRedirect('javascript:alert(1)')).toBe('/')
  })

  it('keeps same-origin absolute paths, including the OAuth authorize endpoint', () => {
    expect(safeRedirect('/settings?resume=export')).toBe('/settings?resume=export')
    expect(safeRedirect('/oauth2/authorize?client_id=x')).toBe('/oauth2/authorize?client_id=x')
  })

  it('rejects protocol-relative and backslash-prefixed targets (open redirect)', () => {
    expect(safeRedirect('//evil.com')).toBe('/')
    expect(safeRedirect('/\\evil.com')).toBe('/')
    expect(safeRedirect('\t//evil.com')).toBe('/')
    expect(safeRedirect('//evil.com', '/login')).toBe('/login')
    expect(safeRedirect('/\\evil.com', '/login')).toBe('/login')
  })

  it('rejects targets containing control characters', () => {
    expect(safeRedirect('/\tx')).toBe('/')
    expect(safeRedirect('/settings?next=/a\nb')).toBe('/')
    expect(safeRedirect('/nul\u0000x')).toBe('/')
    expect(safeRedirect('/del\u007F')).toBe('/')
  })

  it('honours a custom fallback', () => {
    expect(safeRedirect(null, '/login')).toBe('/login')
  })
})
