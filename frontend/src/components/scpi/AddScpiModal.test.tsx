import '@testing-library/jest-dom'
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { AddScpiModal } from './AddScpiModal'

const { createAccount, updatePosition } = vi.hoisted(() => ({
  createAccount: vi.fn(),
  updatePosition: vi.fn(),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

vi.mock('react-router', () => ({
  useNavigate: () => vi.fn(),
}))

vi.mock('@/features/accounts/hooks', () => ({
  useCreateAccount: () => ({ mutateAsync: createAccount, isPending: false, error: null }),
  useUpdateScpiPosition: () => ({ mutateAsync: updatePosition, isPending: false, error: null }),
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

  it('keeps save disabled when only the distribution amount is set', () => {
    render(<AddScpiModal open onOpenChange={() => {}} />)
    fireEvent.change(screen.getByLabelText('scpi.name'), { target: { value: 'Example SCPI' } })
    fireEvent.change(screen.getByLabelText('scpi.shareCount'), { target: { value: '10' } })
    expect(screen.getByRole('button', { name: 'scpi.save' })).toBeEnabled()

    fireEvent.change(screen.getByLabelText('scpi.annualDistribution'), { target: { value: '12.5' } })
    expect(screen.getByRole('button', { name: 'scpi.save' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'scpi.save' }))
    expect(updatePosition).not.toHaveBeenCalled()
  })

  it('sends an optional distribution and frequency with the position', async () => {
    createAccount.mockReset().mockResolvedValue({ id: 9 })
    updatePosition.mockReset().mockResolvedValue({})
    render(<AddScpiModal open onOpenChange={() => {}} />)

    fireEvent.change(screen.getByLabelText('scpi.name'), { target: { value: 'Example SCPI' } })
    fireEvent.change(screen.getByLabelText('scpi.shareCount'), { target: { value: '10' } })
    fireEvent.change(screen.getByLabelText('scpi.annualDistribution'), { target: { value: '12.5' } })
    fireEvent.change(screen.getByLabelText('scpi.distributionFrequency'), { target: { value: 'QUARTERLY' } })
    fireEvent.click(screen.getByRole('button', { name: 'scpi.save' }))

    await waitFor(() => expect(updatePosition).toHaveBeenCalledWith({
      id: 9,
      data: expect.objectContaining({
        shareCount: 10,
        annualDistributionPerShareEur: 12.5,
        distributionFrequency: 'QUARTERLY',
      }),
    }))
  })
})
