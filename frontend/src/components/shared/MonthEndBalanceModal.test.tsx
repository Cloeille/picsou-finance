import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MonthEndBalanceModal } from './MonthEndBalanceModal'

const mutateAsync = vi.fn()

vi.mock('@/features/accounts/hooks', () => ({
  useAddSnapshot: () => ({ mutateAsync }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, fallback?: string) => fallback ?? key,
    i18n: { language: 'en', resolvedLanguage: 'en' },
  }),
}))

function renderModal(onClose = vi.fn()) {
  render(<MonthEndBalanceModal open onClose={onClose} accountId={1} history={[]} />)
  return { onClose }
}

function editFirstMonth(value = '1234') {
  // NumericInput renders type="text" inputMode="decimal" (role "textbox"),
  // not type="number" — so the month fields are textboxes, not spinbuttons.
  const input = screen.getAllByRole('textbox')[0]
  fireEvent.change(input, { target: { value } })
  return input
}

function saveButton() {
  return screen.getByRole('button', { name: /addSnapshot/i })
}

describe('MonthEndBalanceModal', () => {
  beforeEach(() => {
    mutateAsync.mockReset()
  })

  it('shows the server error message in the UI when a save fails', async () => {
    mutateAsync.mockRejectedValue({
      response: { data: { detail: "Method 'POST' is not supported." } },
    })
    const { onClose } = renderModal()

    editFirstMonth()
    fireEvent.click(saveButton())

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent("Method 'POST' is not supported.")
    // failure keeps the modal open and re-enables the save button (spinner cleared)
    expect(onClose).not.toHaveBeenCalled()
    await waitFor(() => expect(saveButton()).not.toBeDisabled())
  })

  it('closes the modal after a successful save', async () => {
    mutateAsync.mockResolvedValue({})
    const { onClose } = renderModal()

    editFirstMonth()
    fireEvent.click(saveButton())

    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows and takes a card debt as the positive amount owed', async () => {
    mutateAsync.mockResolvedValue({})
    const now = new Date()
    const date = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-01`
    render(
      <MonthEndBalanceModal
        open
        onClose={vi.fn()}
        accountId={1}
        history={[{ id: 1, date, balance: -800 }]}
        amountOwed
      />,
    )

    expect(screen.getByText('accounts.currentDebt')).toBeInTheDocument()
    expect(screen.getAllByRole('textbox')[0]).toHaveValue('800')

    editFirstMonth('900')
    fireEvent.click(saveButton())

    await waitFor(() => expect(mutateAsync).toHaveBeenCalledWith({ id: 1, balance: 900, date }))
  })
})
