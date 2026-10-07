import '@testing-library/jest-dom'
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { mockAccounts as demoAccounts } from '@/demo/data/accounts'
import { CardNatureField } from './CardNatureField'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

describe('CardNatureField', () => {
  it('shows the label and the translated nature', () => {
    render(<CardNatureField nature="DEFERRED_DEBIT" />)
    expect(screen.getByText('accounts.cardNature.label')).toBeInTheDocument()
    expect(screen.getByText('accounts.cardNature.DEFERRED_DEBIT')).toBeInTheDocument()
  })

  it.each(['IMMEDIATE_DEBIT', 'CREDIT'] as const)('renders %s', (nature) => {
    render(<CardNatureField nature={nature} />)
    expect(screen.getByText(`accounts.cardNature.${nature}`)).toBeInTheDocument()
  })

  it('renders nothing without a nature', () => {
    const { container } = render(<CardNatureField nature={null} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('the demo credit card carries a nature', () => {
    const card = demoAccounts.find((a) => a.type === 'CREDIT_CARD')
    expect(card?.cardNature).toBe('DEFERRED_DEBIT')
  })
})
