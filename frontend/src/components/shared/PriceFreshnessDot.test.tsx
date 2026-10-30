import '@testing-library/jest-dom'
import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, unknown>) =>
      options ? `${key} ${Object.values(options).join(' ')}` : key,
  }),
}))

vi.mock('@/lib/utils', async (importOriginal) => ({
  ...await importOriginal<typeof import('@/lib/utils')>(),
  formatDate: (value: string) => value,
  formatTimeAgo: (value: string) => value,
}))

const { PriceFreshnessDot } = await import('./PriceFreshnessDot')

const NOW = new Date('2026-08-19T12:00:00Z')

describe('PriceFreshnessDot', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(NOW)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('marks a recently synced bank fallback amber when its NAV date is old', () => {
    render(
      <PriceFreshnessDot
        priceUpdatedAt={new Date(NOW.getTime() - 60_000).toISOString()}
        staleAsOf="2026-08-18"
      />,
    )

    expect(screen.getByRole('img', { name: 'accounts.priceAsOf 2026-08-18' }))
      .toHaveClass('bg-amber-500')
    expect(screen.queryByRole('img', { name: 'accounts.priceLive' })).not.toBeInTheDocument()
  })

  it('marks a recent price live when no stale backend date is present', () => {
    render(
      <PriceFreshnessDot
        priceUpdatedAt={new Date(NOW.getTime() - 60_000).toISOString()}
        staleAsOf={null}
      />,
    )

    expect(screen.getByRole('img', { name: 'accounts.priceLive' })).toHaveClass('bg-emerald-500')
  })
})
