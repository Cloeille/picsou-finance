import '@testing-library/jest-dom'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { HoldingResponse } from '@/types/api'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

const { EditHoldingModal } = await import('./EditHoldingModal')

function holding(): HoldingResponse {
  return { ticker: 'BTC', name: 'Bitcoin', quantity: 2, averageBuyIn: 30000 } as unknown as HoldingResponse
}

describe('EditHoldingModal', () => {
  it('derives total invested and average buy-in from each other via the quantity', () => {
    render(<EditHoldingModal open holding={holding()} onSubmit={vi.fn()} onOpenChange={() => {}} />)

    // qty 2, avg 30000 → total seeded to 60000. Editing total updates the average.
    fireEvent.change(screen.getByDisplayValue('60000'), { target: { value: '80000' } })
    expect(screen.getByDisplayValue('40000')).toBeInTheDocument()
  })

  it('disables the quantity field for synced accounts', () => {
    render(<EditHoldingModal open holding={holding()} onSubmit={vi.fn()} onOpenChange={() => {}} quantityReadOnly />)
    expect(screen.getByDisplayValue('2')).toBeDisabled()
  })

  it('submits the unchanged synced quantity with the edited cost basis', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined)
    render(<EditHoldingModal open holding={holding()} onSubmit={onSubmit} onOpenChange={() => {}} quantityReadOnly />)

    fireEvent.change(screen.getByDisplayValue('30000'), { target: { value: '25000' } })
    fireEvent.click(screen.getByText('common.save'))

    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith('BTC', 2, 25000))
  })

  it('blocks submit and shows an error for a zero quantity', () => {
    const onSubmit = vi.fn()
    render(<EditHoldingModal open holding={holding()} onSubmit={onSubmit} onOpenChange={() => {}} />)

    // 0 passes the HTML `required` gate but must be rejected by the JS validation.
    fireEvent.change(screen.getByDisplayValue('2'), { target: { value: '0' } })
    fireEvent.click(screen.getByText('common.save'))

    expect(onSubmit).not.toHaveBeenCalled()
    expect(screen.getByText('holdings.quantityInvalid')).toBeInTheDocument()
  })

  it('keeps the form open when submission fails', async () => {
    const onSubmit = vi.fn().mockRejectedValue(new Error('boom'))
    const onOpenChange = vi.fn()
    render(<EditHoldingModal open holding={holding()} onSubmit={onSubmit} onOpenChange={onOpenChange} />)

    fireEvent.click(screen.getByText('common.save'))

    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
  })
})
