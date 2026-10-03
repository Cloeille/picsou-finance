import '@testing-library/jest-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { Account } from '@/types/api'
import { ScpiDetailSection } from './ScpiDetailSection'

const { mutateAsync } = vi.hoisted(() => ({ mutateAsync: vi.fn() }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key, i18n: { resolvedLanguage: 'en' } }),
}))
vi.mock('@/features/accounts/hooks', () => ({
  useUpdateScpiPosition: () => ({ mutateAsync, isPending: false, error: null }),
}))
vi.mock('@/components/shared/DateInput', () => ({
  DateInput: ({ id, value }: { id: string; value: string }) => <input id={id} value={value} readOnly />,
}))

const account: Account = {
  id: 42, name: 'Example SCPI', type: 'SCPI', provider: null, currency: 'EUR',
  currentBalance: 8800, currentBalanceEur: 8800, lastSyncedAt: null, isManual: true,
  color: '#000000', ticker: null, logoUrl: null, logoKey: null, createdAt: '2026-01-01',
  isOwner: true, hidden: false,
  scpi: {
    isin: 'FR0012345678', managementCompany: 'Example', shareCount: 10,
    subscriptionPriceEur: 1000, withdrawalPriceEur: 880, withdrawalValueEur: 8800,
    dividendPolicy: 'REINVEST', jouissanceDate: '2026-02-01', valuationStatus: 'OK',
    corumFundCode: 'FUND-42', sofidyFundCode: 'DY',
  },
}

beforeEach(() => {
  mutateAsync.mockReset().mockResolvedValue({})
})

describe('ScpiDetailSection fund links', () => {
  it('shows existing provider links and preserves them when correcting shares', async () => {
    render(<ScpiDetailSection account={account} />)
    expect(screen.getByText('FUND-42')).toBeInTheDocument()
    expect(screen.getByText('DY')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'scpi.edit' }))
    expect(screen.getByLabelText('scpi.corumFundCode')).toHaveValue('FUND-42')
    expect(screen.getByLabelText('scpi.sofidyFundCode')).toHaveValue('DY')
    fireEvent.change(screen.getByLabelText('scpi.shareCount'), { target: { value: '12' } })
    fireEvent.click(screen.getByRole('button', { name: 'scpi.save' }))
    await waitFor(() => expect(mutateAsync).toHaveBeenCalledWith({
      id: 42,
      data: {
        isin: 'FR0012345678', managementCompany: 'Example', shareCount: 12,
        subscriptionPriceEur: 1000, withdrawalPriceEur: 880,
        dividendPolicy: 'REINVEST', jouissanceDate: '2026-02-01',
        corumFundCode: 'FUND-42', sofidyFundCode: 'DY',
      },
    }))
  })

  it('links an existing unlinked account without replacing its other position fields', async () => {
    render(<ScpiDetailSection account={{
      ...account, scpi: { ...account.scpi!, corumFundCode: null, sofidyFundCode: null },
    }} />)
    fireEvent.click(screen.getByRole('button', { name: 'scpi.edit' }))
    fireEvent.change(screen.getByLabelText('scpi.corumFundCode'), { target: { value: ' FUND-99 ' } })
    fireEvent.change(screen.getByLabelText('scpi.sofidyFundCode'), { target: { value: ' DY ' } })
    fireEvent.click(screen.getByRole('button', { name: 'scpi.save' }))
    await waitFor(() => expect(mutateAsync).toHaveBeenCalledWith({
      id: 42, data: {
        isin: 'FR0012345678', managementCompany: 'Example', shareCount: 10,
        subscriptionPriceEur: 1000, withdrawalPriceEur: 880,
        dividendPolicy: 'REINVEST', jouissanceDate: '2026-02-01',
        corumFundCode: 'FUND-99', sofidyFundCode: 'DY',
      },
    }))
  })

  it.each(['corumFundCode', 'sofidyFundCode'] as const)('detaches %s with an explicit empty string', async (field) => {
    render(<ScpiDetailSection account={account} />)
    fireEvent.click(screen.getByRole('button', { name: 'scpi.edit' }))
    fireEvent.change(screen.getByLabelText(`scpi.${field}`), { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: 'scpi.save' }))
    await waitFor(() => expect(mutateAsync).toHaveBeenCalledWith({
      id: 42, data: expect.objectContaining({
        corumFundCode: 'FUND-42', sofidyFundCode: 'DY', [field]: '',
      }),
    }))
  })

  it('shows links without editing controls for a shared read-only account', () => {
    render(<ScpiDetailSection account={{ ...account, isOwner: false }} />)
    expect(screen.getByText('FUND-42')).toBeInTheDocument()
    expect(screen.getByText('DY')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'scpi.edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'scpi.save' })).not.toBeInTheDocument()
    expect(mutateAsync).not.toHaveBeenCalled()
  })

  it('starts a new edit from the latest synced position, not stale or cancelled values', () => {
    const { rerender } = render(<ScpiDetailSection account={account} />)
    fireEvent.click(screen.getByRole('button', { name: 'scpi.edit' }))
    fireEvent.change(screen.getByLabelText('scpi.corumFundCode'), { target: { value: 'CANCELLED' } })
    fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }))
    rerender(<ScpiDetailSection account={{
      ...account, scpi: { ...account.scpi!, corumFundCode: 'FUND-99', shareCount: 12 },
    }} />)
    fireEvent.click(screen.getByRole('button', { name: 'scpi.edit' }))
    expect(screen.getByLabelText('scpi.corumFundCode')).toHaveValue('FUND-99')
    expect(screen.getByLabelText('scpi.shareCount')).toHaveValue('12')
  })

  it('removes an open form when ownership becomes read-only', () => {
    const { rerender } = render(<ScpiDetailSection account={account} />)
    fireEvent.click(screen.getByRole('button', { name: 'scpi.edit' }))
    rerender(<ScpiDetailSection account={{ ...account, isOwner: false }} />)
    expect(screen.queryByRole('button', { name: 'scpi.save' })).not.toBeInTheDocument()
    expect(mutateAsync).not.toHaveBeenCalled()
  })
})
