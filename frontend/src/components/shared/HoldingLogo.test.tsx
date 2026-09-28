import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, waitFor } from '@testing-library/react'
import { HoldingLogo } from './HoldingLogo'

const LOGO = 'https://coin-images.coingecko.com/coins/images/1/small/bitcoin.png'
const BROKEN = 'https://coin-images.coingecko.com/coins/images/1/small/broken.png'

/**
 * Radix probes the image with a synthetic `new Image()` rather than reading the rendered
 * <img>, so jsdom never mounts it on its own. Same stub as AccountCard.test.tsx: a src
 * containing "broken" reports a load failure, anything else reports success.
 */
class MockImage {
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  complete = false
  naturalWidth = 0
  private listeners = new Map<string, Set<(event: { currentTarget: MockImage }) => void>>()
  private _src = ''

  addEventListener(type: string, listener: (event: { currentTarget: MockImage }) => void) {
    const listeners = this.listeners.get(type) ?? new Set()
    listeners.add(listener)
    this.listeners.set(type, listeners)
  }

  removeEventListener(type: string, listener: (event: { currentTarget: MockImage }) => void) {
    this.listeners.get(type)?.delete(listener)
  }

  set src(value: string) {
    this._src = value
    this.complete = false
    this.naturalWidth = 0
    queueMicrotask(() => {
      this.complete = true
      if (value.includes('broken')) {
        this.naturalWidth = 0
        this.onerror?.()
        this.listeners.get('error')?.forEach(listener => listener({ currentTarget: this }))
      } else {
        this.naturalWidth = 1
        this.onload?.()
        this.listeners.get('load')?.forEach(listener => listener({ currentTarget: this }))
      }
    })
  }

  get src() {
    return this._src
  }
}

beforeEach(() => {
  vi.stubGlobal('Image', MockImage)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('HoldingLogo', () => {
  it('shows the image when the backend resolved one', async () => {
    const { container } = render(<HoldingLogo logoUrl={LOGO} ticker="BTC" />)

    await waitFor(() => {
      expect(container.querySelector('img')).toHaveAttribute('src', LOGO)
    })
    // The ticker is the alt text, so the asset is named whether or not the mark loads.
    expect(container.querySelector('img')).toHaveAttribute('alt', 'BTC')
  })

  it('renders an empty mark when there is no logo', () => {
    // The equity case: no source resolves a URL today. The caller renders the ticker as text
    // beside this, so the fallback must not repeat it.
    const { container } = render(<HoldingLogo logoUrl={null} ticker="AAPL" />)

    expect(container.querySelector('img')).not.toBeInTheDocument()
    expect(container.textContent).toBe('')
    // The disc is still there, so the column does not jump when a mark is missing.
    expect(container.querySelector('[data-slot="avatar-fallback"]')).toBeInTheDocument()
  })

  it('falls back to an empty mark when the image fails to load', async () => {
    const { container } = render(<HoldingLogo logoUrl={BROKEN} ticker="BTC" />)

    // Radix keeps a failed image mounted, so the component drops the src itself -- otherwise a
    // broken mark would sit there for the rest of the session.
    await waitFor(() => {
      expect(container.querySelector('img')).not.toBeInTheDocument()
    })
    expect(container.querySelector('[data-slot="avatar-fallback"]')).toBeInTheDocument()
  })

  it('keeps the ticker as the image alt text', async () => {
    const { container } = render(<HoldingLogo logoUrl={LOGO} ticker="GOOGL" />)

    await waitFor(() => {
      expect(container.querySelector('img')).toBeInTheDocument()
    })
    expect(container.querySelector('img')).toHaveAttribute('alt', 'GOOGL')
  })
})
