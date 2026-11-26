import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { chooseSelectOption, getSelectOptions, getSelectOptionValues } from '@/test/radix-select'
import { StubImage } from '@/test/stubImage'
import type { Account } from '@/types/api'

const { institutionSearch } = vi.hoisted(() => ({
  institutionSearch: { current: { data: undefined as unknown } },
}))

vi.mock('@/features/sync/hooks', () => ({
  useSearchInstitutions: () => institutionSearch.current,
  useBankCountries: () => ({ data: ['FR'] }),
}))

vi.mock('@/features/budget/hooks', () => ({
  useBudgetSettings: () => ({ data: { logoFetchEnabled: false } }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'fr', resolvedLanguage: 'fr' } }),
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
Object.defineProperty(window, 'matchMedia', {
  configurable: true,
  writable: true,
  value: vi.fn((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
})

const { AccountForm } = await import('./AccountForm')
const { AccountCard } = await import('./AccountCard')

afterEach(() => {
  vi.restoreAllMocks()
})

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

  it('lets a member pick a French insurer for a manual assurance vie and renders it by name without a logo image (#798)', async () => {
    institutionSearch.current = { data: undefined }
    const originalImage = globalThis.Image
    vi.stubGlobal('Image', StubImage)
    try {
      const onSubmit = vi.fn()
      render(<AccountForm open onOpenChange={vi.fn()} onSubmit={onSubmit} defaultValues={{ isManual: true }} />)

      chooseSelectOption(screen.getByLabelText('accounts.accountType'), 'ASSURANCE_VIE')
      fireEvent.change(screen.getByLabelText('accounts.accountName'), { target: { value: 'Contrat Avenir' } })

      const providerInput = screen.getByLabelText('accounts.provider')
      expect(providerInput).toHaveAttribute('placeholder', 'accounts.insurerPlaceholder')
      fireEvent.focus(providerInput)
      expect(screen.getByRole('list').querySelector('img')).not.toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: /CNP Assurances/ }))
      fireEvent.click(screen.getByRole('button', { name: 'common.save' }))

      await waitFor(() => expect(onSubmit).toHaveBeenCalled())
      const submitted = onSubmit.mock.calls[0][0]
      expect(submitted).toMatchObject({
        name: 'Contrat Avenir',
        type: 'ASSURANCE_VIE',
        provider: 'CNP Assurances',
        institutionId: undefined,
        isManual: true,
      })

      const createdAccount: Account = {
        id: 99,
        name: submitted.name,
        type: submitted.type,
        provider: submitted.provider,
        currency: submitted.currency,
        currentBalance: 15000,
        currentBalanceEur: 15000,
        lastSyncedAt: null,
        isManual: true,
        color: submitted.color,
        ticker: null,
        logoUrl: null,
        logoKey: null,
        createdAt: '2026-01-01T00:00:00Z',
        hidden: false,
        listPosition: null,
        listCollapsed: false,
      }
      const { container, getByText } = render(<AccountCard account={createdAccount} />)
      expect(getByText('CNP Assurances')).toBeInTheDocument()
      expect(container.querySelector('img')).not.toBeInTheDocument()
      const dot = container.querySelector('[style*="background-color"]')
      expect(dot).toHaveStyle({ backgroundColor: createdAccount.color })
    } finally {
      vi.stubGlobal('Image', originalImage)
    }
  })
})
