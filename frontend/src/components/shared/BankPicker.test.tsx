import '@testing-library/jest-dom'
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

/** Mutable so each test can seed what the institution catalog answers. */
const { institutionSearch } = vi.hoisted(() => ({
  institutionSearch: { current: { data: undefined as unknown } },
}))

vi.mock('@/features/sync/hooks', () => ({
  useSearchInstitutions: () => institutionSearch.current,
  useBankCountries: () => ({ data: ['FR'] }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

const { BankPicker } = await import('./BankPicker')

const CREDIT_AGRICOLE = {
  id: 'Crédit Agricole::FR::personal',
  name: 'Crédit Agricole',
  bic: null,
  logoUrl: 'https://cdn.example/ca.png',
  country: 'FR',
  psuType: 'personal',
}

describe('BankPicker', () => {
  it('reports the picked bank with the catalog id the backend resolves its logo from', () => {
    institutionSearch.current = { data: [CREDIT_AGRICOLE] }
    const onChange = vi.fn()
    render(<BankPicker value="crédit" onChange={onChange} />)

    // The list only arms on a keystroke, so a freshly opened form shows no suggestions.
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'crédit a' } })
    fireEvent.click(screen.getByRole('button', { name: /Crédit Agricole/ }))

    expect(onChange).toHaveBeenLastCalledWith('Crédit Agricole', 'Crédit Agricole::FR::personal')
  })

  it('drops the institution id as soon as the name is edited', () => {
    // Otherwise the account saves under one bank's name and another bank's logo.
    institutionSearch.current = { data: [CREDIT_AGRICOLE] }
    const onChange = vi.fn()
    render(<BankPicker value="Crédit Agricole" onChange={onChange} />)

    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Crédit Agricole Pro' } })

    expect(onChange).toHaveBeenCalledWith('Crédit Agricole Pro', undefined)
  })

  it('stays a plain text field when no catalog is available', () => {
    // Enable Banking unconfigured, or a bank it does not list: the name is still editable.
    institutionSearch.current = { data: undefined }
    const onChange = vi.fn()
    render(<BankPicker value="" onChange={onChange} />)

    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Ma banque locale' } })

    expect(onChange).toHaveBeenCalledWith('Ma banque locale', undefined)
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
  })

  it('closes the suggestions once a bank is picked', () => {
    institutionSearch.current = { data: [CREDIT_AGRICOLE] }
    render(<BankPicker value="crédit" onChange={vi.fn()} />)

    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'crédit a' } })
    expect(screen.getByRole('list')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /Crédit Agricole/ }))
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
  })
})
