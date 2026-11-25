import { beforeEach, describe, expect, it, vi } from 'vitest'
import { isChunkLoadError, reloadOnceOnChunkError } from './chunk-reload'

describe('chunk-reload', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  it('recognizes WebKit, Chromium, and Firefox dynamic import errors', () => {
    expect(isChunkLoadError(new TypeError('Importing a module script failed.'))).toBe(true)
    expect(isChunkLoadError(new TypeError('Failed to fetch dynamically imported module: /assets/Foo.js'))).toBe(true)
    expect(isChunkLoadError(new Error('error loading dynamically imported module'))).toBe(true)
    expect(isChunkLoadError(new Error('Ordinary runtime error'))).toBe(false)
  })

  it('reloads at most once for the same location to avoid infinite loops', () => {
    const reload = vi.fn()
    expect(reloadOnceOnChunkError(reload)).toBe(true)
    expect(reload).toHaveBeenCalledTimes(1)
    expect(reloadOnceOnChunkError(reload)).toBe(false)
    expect(reload).toHaveBeenCalledTimes(1)
  })
})
