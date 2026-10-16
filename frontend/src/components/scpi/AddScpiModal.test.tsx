import '@testing-library/jest-dom'
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { AddScpiModal } from './AddScpiModal'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

vi.mock('react-router-dom', () => ({
  useNavigate: () => vi.fn(),
}))

vi.mock('@/features/accounts/hooks', () => ({
  useCreateAccount: () => ({ mutateAsync: vi.fn(), isPending: false, error: null }),
  useUpdateScpiPosition: () => ({ mutateAsync: vi.fn(), isPending: false, error: null }),
  useScpiFunds: () => ({ data: [
    { provider: 'CORUM', fundCode: 'XL', label: 'CORUM XL', ignored: false, lastOutcome: 'AMBIGUOUS',
      lastSeenAt: null, lastSyncedAt: null, accountId: null, accountName: null },
    { provider: 'CORUM', fundCode: 'US', label: 'CORUM USA', ignored: true, lastOutcome: 'IGNORED',
      lastSeenAt: null, lastSyncedAt: null, accountId: null, accountName: null },
    { provider: 'SOFIDY', fundCode: 'DY', label: 'SOFIDYNAMIC', ignored: false, lastOutcome: 'UPDATED',
      lastSeenAt: null, lastSyncedAt: null, accountId: 5, accountName: 'SOFIDYNAMIC' },
  ] }),
}))

vi.mock('@/components/shared/DateInput', () => ({
  DateInput: () => <input aria-label="scpi.jouissanceDate" />,
}))

describe('AddScpiModal', () => {
  it('asks for the two prices and never for an address', () => {
    render(<AddScpiModal open onOpenChange={() => {}} />)

    expect(screen.getByLabelText('scpi.shareCount')).toBeInTheDocument()
    expect(screen.getByLabelText('scpi.withdrawalPrice')).toBeInTheDocument()
    expect(screen.getByLabelText('scpi.subscriptionPrice')).toBeInTheDocument()
    expect(screen.queryByLabelText(/address/i)).toBeNull()
  })

  it('offers only unlinked funds a sync has seen, and fills the name and code from one', () => {
    render(<AddScpiModal open onOpenChange={() => {}} />)

    const picker = screen.getByLabelText('scpi.seenFund')
    expect(screen.queryByRole('option', { name: /SOFIDYNAMIC/ })).toBeNull()
    expect(screen.getByRole('option', { name: /CORUM USA.*scpi.seenFundIgnored/ })).toBeInTheDocument()
    fireEvent.change(picker, { target: { value: 'CORUM:XL' } })
    expect(screen.getByLabelText('scpi.name')).toHaveValue('CORUM XL')
    expect(screen.getByLabelText('scpi.corumFundCode')).toHaveValue('XL')
    expect(screen.queryByLabelText(/surface/i)).toBeNull()
  })
})
