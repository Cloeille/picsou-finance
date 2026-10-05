import '@testing-library/jest-dom'
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { BankCountrySelect, DEFAULT_BANK_COUNTRY } from './BankCountrySelect'
import { useBankCountries } from '@/features/sync/hooks'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

vi.mock('@/features/sync/hooks', () => ({
  useBankCountries: vi.fn(),
}))

const mockedUseBankCountries = vi.mocked(useBankCountries)

describe('BankCountrySelect', () => {
  it('falls back to the default country only while loading (no data yet), with no error message', () => {
    mockedUseBankCountries.mockReturnValue({ data: undefined, isError: false, isSuccess: false } as ReturnType<typeof useBankCountries>)

    render(<BankCountrySelect value={DEFAULT_BANK_COUNTRY} onChange={vi.fn()} />)

    const options = screen.getAllByRole('option')
    expect(options).toHaveLength(1)
    expect(options[0]).toHaveValue('FR')
    expect(screen.queryByText('sync.banks.countriesLoadError')).not.toBeInTheDocument()
  })

  it('renders live options with the default country pinned first', () => {
    mockedUseBankCountries.mockReturnValue({ data: ['DE', 'EE', 'FR'], isError: false, isSuccess: true } as ReturnType<typeof useBankCountries>)

    render(<BankCountrySelect value={DEFAULT_BANK_COUNTRY} onChange={vi.fn()} />)

    const options = screen.getAllByRole('option')
    expect(options.map((o) => (o as HTMLOptionElement).value)).toEqual(['FR', 'DE', 'EE'])
  })

  it('calls onChange when the user picks a different option', () => {
    mockedUseBankCountries.mockReturnValue({ data: ['DE', 'EE', 'FR'], isError: false, isSuccess: true } as ReturnType<typeof useBankCountries>)
    const onChange = vi.fn()

    render(<BankCountrySelect value={DEFAULT_BANK_COUNTRY} onChange={onChange} />)
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'EE' } })

    expect(onChange).toHaveBeenCalledWith('EE')
  })

  it('shows a visible error message when the countries request fails with no prior data, without disabling the select', () => {
    mockedUseBankCountries.mockReturnValue({ data: undefined, isError: true, isSuccess: false } as ReturnType<typeof useBankCountries>)

    render(<BankCountrySelect value={DEFAULT_BANK_COUNTRY} onChange={vi.fn()} />)

    expect(screen.getByText('sync.banks.countriesLoadError')).toBeInTheDocument()
    expect(screen.getByRole('combobox')).toBeEnabled()
  })

  it('shows the error message when the request succeeds but returns an empty list', () => {
    mockedUseBankCountries.mockReturnValue({ data: [] as string[], isError: false, isSuccess: true } as ReturnType<typeof useBankCountries>)

    render(<BankCountrySelect value={DEFAULT_BANK_COUNTRY} onChange={vi.fn()} />)

    expect(screen.getByText('sync.banks.countriesLoadError')).toBeInTheDocument()
  })

  it('does not show the error message when a background refetch fails but prior data is still available', () => {
    mockedUseBankCountries.mockReturnValue({ data: ['DE', 'EE', 'FR'], isError: true, isSuccess: false } as ReturnType<typeof useBankCountries>)

    render(<BankCountrySelect value={DEFAULT_BANK_COUNTRY} onChange={vi.fn()} />)

    // The full list is still shown — claiming "showing France only" here would be false.
    expect(screen.queryByText('sync.banks.countriesLoadError')).not.toBeInTheDocument()
    expect(screen.getAllByRole('option')).toHaveLength(3)
  })

  it('snaps to the first available option when the current value is not in the loaded list', () => {
    mockedUseBankCountries.mockReturnValue({ data: ['DE', 'EE'], isError: false, isSuccess: true } as ReturnType<typeof useBankCountries>)
    const onChange = vi.fn()

    // Current value 'FR' isn't in the loaded (non-FR) list — the effect should correct it.
    render(<BankCountrySelect value={DEFAULT_BANK_COUNTRY} onChange={onChange} />)

    expect(onChange).toHaveBeenCalledWith('DE')
  })
})
