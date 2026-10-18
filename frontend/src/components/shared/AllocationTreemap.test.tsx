import '@testing-library/jest-dom'
import { describe, it, expect, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { DistributionPie } from './DistributionPie'
import type { DistributionItem } from './distribution-labels'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'en', resolvedLanguage: 'en' },
  }),
}))

vi.stubGlobal('ResizeObserver', class {
  observe() {}
  unobserve() {}
  disconnect() {}
})

const LONG_NAME = 'LIVRET DEVELOPPEMENT DURABLE ET SOLIDAIRE DE MADAME ET MONSIEUR'

const data: DistributionItem[] = [
  { accountId: 1, name: LONG_NAME, accountType: 'LDDS', color: '#123456', balanceEur: 600, percentage: 60 },
  { accountId: 2, name: 'PEA', accountType: 'PEA', color: '#654321', balanceEur: 400, percentage: 40 },
]

describe('AllocationTreemap', () => {
  it('keeps a long name inside its tile and exposes it in full on hover', () => {
    render(<DistributionPie data={data} />)
    // Radix tabs switch on mousedown, not click.
    fireEvent.mouseDown(screen.getByRole('tab', { name: 'dashboard.allocation' }), { button: 0 })

    const label = screen.getByText(LONG_NAME)
    expect(label).toHaveClass('truncate', 'max-w-full')
    expect(label.parentElement).toHaveClass('w-full', 'min-w-0', 'overflow-hidden')

    const tile = label.closest('[title]')
    expect(tile).toHaveAttribute('title', LONG_NAME)
  })

  it('does not clip the hover ring or the gap hitbox that sit outside the tile box', () => {
    render(<DistributionPie data={data} />)
    fireEvent.mouseDown(screen.getByRole('tab', { name: 'dashboard.allocation' }), { button: 0 })

    const tile = screen.getByText(LONG_NAME).closest('[title]')
    expect(tile).not.toHaveClass('overflow-hidden')
  })
})
