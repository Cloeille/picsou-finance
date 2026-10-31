import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, waitFor } from '@testing-library/react'
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

  it("shows the name's initials when there is no logo", () => {
    // A share whose mark was never stored. The caller renders the ticker as text beside this, so
    // the fallback must not repeat it.
    const { container } = render(<HoldingLogo logoUrl={null} ticker="IWDA" name="iShares Core MSCI World" />)

    expect(container.querySelector('img')).not.toBeInTheDocument()
    expect(container.textContent).toBe('IC')
    expect(container.textContent).not.toContain('IWDA')
  })

  it('keeps an empty disc when there is neither a logo nor a name', () => {
    // An exchange coin line: its only initials would be the ticker's, printed right beside it.
    const { container } = render(<HoldingLogo logoUrl={null} ticker="BTC" />)

    expect(container.textContent).toBe('')
    expect(container.querySelector('[data-slot="avatar-fallback"]')).toBeInTheDocument()
  })

  it('keeps an empty disc when the name is blank', () => {
    const { container } = render(<HoldingLogo logoUrl={null} ticker="BTC" name="   " />)

    expect(container.textContent).toBe('')
    expect(container.querySelector('[data-slot="avatar-fallback"]')).toBeInTheDocument()
  })

  it('keeps an empty disc when the name is only the ticker', () => {
    // A dividend without a known name: the backend fills the name with the ticker.
    const { container } = render(<HoldingLogo logoUrl={null} ticker="IWDA" name="iwda" />)

    expect(container.textContent).toBe('')
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

describe('HoldingLogo with a stored share mark', () => {
  const LIGHT = '/api/instrument-logos/AAPL?v=1759400000'
  const DARK = '/api/instrument-logos/AAPL?v=1759400000&variant=dark'

  afterEach(() => {
    document.documentElement.classList.remove('dark')
  })

  it('loads the mark from Picsou itself', async () => {
    const { container } = render(<HoldingLogo logoUrl={LIGHT} ticker="AAPL" />)

    await waitFor(() => {
      expect(container.querySelector('img')).toHaveAttribute('src', LIGHT)
    })
  })

  it('uses the dark variant under the dark palette, and follows a theme switch', async () => {
    document.documentElement.classList.add('dark')
    const { container } = render(<HoldingLogo logoUrl={LIGHT} logoUrlDark={DARK} ticker="AAPL" />)

    await waitFor(() => {
      expect(container.querySelector('img')).toHaveAttribute('src', DARK)
    })

    act(() => {
      document.documentElement.classList.remove('dark')
    })
    await waitFor(() => {
      expect(container.querySelector('img')).toHaveAttribute('src', LIGHT)
    })
  })

  it('keeps the light mark under the dark palette when there is no dark variant', async () => {
    document.documentElement.classList.add('dark')
    const { container } = render(<HoldingLogo logoUrl={LIGHT} logoUrlDark={null} ticker="AAPL" />)

    await waitFor(() => {
      expect(container.querySelector('img')).toHaveAttribute('src', LIGHT)
    })
  })
})
