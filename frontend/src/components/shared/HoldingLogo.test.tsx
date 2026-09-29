import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, waitFor } from '@testing-library/react'
import { HoldingLogo } from './HoldingLogo'
import { StubImage } from '@/test/stubImage'

const LOGO = 'https://coin-images.coingecko.com/coins/images/1/small/bitcoin.png'
const BROKEN = 'https://coin-images.coingecko.com/coins/images/1/small/broken.png'

beforeEach(() => {
  vi.stubGlobal('Image', StubImage)
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
