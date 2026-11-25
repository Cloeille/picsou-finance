import '@testing-library/jest-dom'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { chooseSelectOption, getSelectOptions, getSelectOptionValues } from '@/test/radix-select'

const { institutionSearch } = vi.hoisted(() => ({
  institutionSearch: { current: { data: undefined as unknown } },
}))

vi.mock('@/features/sync/hooks', () => ({
  useSearchInstitutions: () => institutionSearch.current,
  useBankCountries: () => ({ data: ['FR'] }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'fr' } }),
}))

vi.stubGlobal('ResizeObserver', class {
  observe() {}
  unobserve() {}
  disconnect() {}
})
Object.defineProperty(document, 'elementFromPoint', {
  configurable: true,
  value: vi.fn(() => document.body),
})

const { AccountForm } = await import('./AccountForm')

const CREDIT_AGRICOLE = {
  id: 'Crédit Agricole::FR::personal',
  name: 'Crédit Agricole',
  bic: null,
  logoUrl: 'https://cdn.example/ca.png',
  country: 'FR',
  psuType: 'personal',
}

describe('AccountForm bank field', () => {
  /**
   * `provider` is written by BankPicker through setValue rather than being registered by the
   * input itself, so this is what proves the picked bank actually reaches the submitted
   * request — and that its catalog id rides along for the server-side logo lookup.
   */
  it('submits the picked bank and its institution id', async () => {
    institutionSearch.current = { data: [CREDIT_AGRICOLE] }
    const onSubmit = vi.fn()
    render(<AccountForm open onOpenChange={vi.fn()} onSubmit={onSubmit} />)

    fireEvent.change(screen.getByLabelText('accounts.accountName'), {
      target: { value: 'Compte joint' },
    })
    fireEvent.change(screen.getByLabelText('accounts.provider'), { target: { value: 'crédit a' } })
    fireEvent.click(screen.getByRole('button', { name: /Crédit Agricole/ }))
    fireEvent.click(screen.getByRole('button', { name: 'common.save' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect(onSubmit.mock.calls[0][0]).toMatchObject({
      name: 'Compte joint',
      provider: 'Crédit Agricole',
      institutionId: 'Crédit Agricole::FR::personal',
    })
  })

  it('submits a hand-typed bank with no institution id', async () => {
    // Nothing picked from the catalog: the backend falls back to matching on the name alone.
    institutionSearch.current = { data: undefined }
    const onSubmit = vi.fn()
    render(<AccountForm open onOpenChange={vi.fn()} onSubmit={onSubmit} />)

    fireEvent.change(screen.getByLabelText('accounts.accountName'), {
      target: { value: 'Livret' },
    })
    fireEvent.change(screen.getByLabelText('accounts.provider'), {
      target: { value: 'Ma banque locale' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'common.save' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect(onSubmit.mock.calls[0][0]).toMatchObject({
      provider: 'Ma banque locale',
      institutionId: undefined,
    })
  })

  it('offers Livret A among the account types', () => {
    institutionSearch.current = { data: undefined }
    render(<AccountForm open onOpenChange={vi.fn()} onSubmit={vi.fn()} />)

    const options = getSelectOptions(screen.getByLabelText('accounts.accountType'))
    expect(options.map(o => o.textContent)).toContain('accountTypes.livretA')
  })

  it('submits an account in a Gulf currency (#718)', async () => {
    institutionSearch.current = { data: undefined }
    const onSubmit = vi.fn()
    render(<AccountForm open onOpenChange={vi.fn()} onSubmit={onSubmit} />)

    const currency = screen.getByLabelText('common.currency')
    expect(getSelectOptionValues(currency)).toEqual(
      expect.arrayContaining(['AED', 'SAR', 'QAR', 'KWD', 'BHD', 'OMR']),
    )

    fireEvent.change(screen.getByLabelText('accounts.accountName'), { target: { value: 'Emirates NBD' } })
    chooseSelectOption(currency, 'AED')
    fireEvent.click(screen.getByRole('button', { name: 'common.save' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect(onSubmit.mock.calls[0][0]).toMatchObject({ name: 'Emirates NBD', currency: 'AED' })
  })

  it('asks for the amount owed on a credit card, like the remaining capital of a loan', async () => {
    institutionSearch.current = { data: undefined }
    const onSubmit = vi.fn()
    render(<AccountForm open onOpenChange={vi.fn()} onSubmit={onSubmit} />)

    chooseSelectOption(screen.getByLabelText('accounts.accountType'), 'CREDIT_CARD')
    fireEvent.change(screen.getByLabelText('accounts.accountName'), { target: { value: 'Card' } })
    fireEvent.change(screen.getByLabelText('accounts.currentDebt'), { target: { value: '800' } })
    fireEvent.click(screen.getByRole('button', { name: 'common.save' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect(onSubmit.mock.calls[0][0]).toMatchObject({ type: 'CREDIT_CARD', currentBalance: 800 })
  })
})
