import '@testing-library/jest-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { InstitutionSearch } from './InstitutionSearch'
import { useBankCountries, useSearchInstitutions } from '@/features/sync/hooks'
import { useAuthStore } from '@/stores/auth-store'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

vi.mock('@/features/sync/hooks', () => ({
  useBankCountries: vi.fn(),
  useSearchInstitutions: vi.fn(),
}))

const mockedUseBankCountries = vi.mocked(useBankCountries)
const mockedUseSearchInstitutions = vi.mocked(useSearchInstitutions)

function defaultSearchResult() {
  return {
    data: undefined,
    isError: false,
    isLoading: false,
    error: null,
  } as unknown as ReturnType<typeof useSearchInstitutions>
}

describe('InstitutionSearch', () => {
  afterEach(() => {
    cleanup()
    useAuthStore.getState().logout()
  })

  it('shows a full-width configuration banner instead of the cramped countries error when Enable Banking is not configured', () => {
    useAuthStore.getState().login({ username: 'admin', role: 'ADMIN', memberId: 1, displayName: 'Admin' })
    const notConfiguredErr = {
      response: {
        status: 422,
        data: {
          code: 'ENABLE_BANKING_NOT_CONFIGURED',
          detail: 'Enable Banking is not fully configured: Key ID is missing.',
        },
      },
    }
    mockedUseBankCountries.mockReturnValue({
      data: undefined,
      isError: true,
      isSuccess: false,
      error: notConfiguredErr,
    } as unknown as ReturnType<typeof useBankCountries>)
    mockedUseSearchInstitutions.mockReturnValue({
      data: undefined,
      isError: true,
      isLoading: false,
      error: notConfiguredErr,
    } as unknown as ReturnType<typeof useSearchInstitutions>)

    render(<InstitutionSearch layout="modal" onConnect={vi.fn()} connecting={false} />)

    expect(screen.getByRole('alert')).toHaveTextContent('sync.banks.notConfigured')
    expect(screen.queryByText('sync.banks.countriesLoadError')).not.toBeInTheDocument()
    const link = screen.getByRole('link', { name: 'sync.banks.configureEnableBanking' })
    expect(link).toHaveAttribute('href', '/admin?tab=integrations')

    // Typing a query should not stack a duplicate raw-English backend error banner.
    fireEvent.change(screen.getByPlaceholderText('sync.banks.searchPlaceholder'), {
      target: { value: 'Revolut' },
    })
    expect(screen.getAllByRole('alert')).toHaveLength(1)
  })

  it('omits the admin configure link for non-admin members', () => {
    useAuthStore.getState().login({ username: 'member', role: 'MEMBER', memberId: 2, displayName: 'Member' })
    mockedUseBankCountries.mockReturnValue({
      data: undefined,
      isError: true,
      isSuccess: false,
      error: {
        response: {
          status: 422,
          data: { detail: 'Enable Banking private key is missing on the server.' },
        },
      },
    } as unknown as ReturnType<typeof useBankCountries>)
    mockedUseSearchInstitutions.mockReturnValue(defaultSearchResult())

    render(<InstitutionSearch layout="modal" onConnect={vi.fn()} connecting={false} />)

    // Members cannot open Administration: they are told to ask the administrator instead.
    expect(screen.getByRole('alert')).toHaveTextContent('sync.banks.notConfiguredMember')
    expect(screen.queryByRole('link', { name: 'sync.banks.configureEnableBanking' })).not.toBeInTheDocument()
  })

  it('renders transient countries load errors full-width outside the narrow country select wrapper', () => {
    mockedUseBankCountries.mockReturnValue({
      data: undefined,
      isError: true,
      isSuccess: false,
      error: { response: { status: 502, data: { detail: 'Upstream timeout' } } },
    } as unknown as ReturnType<typeof useBankCountries>)
    mockedUseSearchInstitutions.mockReturnValue(defaultSearchResult())

    render(<InstitutionSearch layout="modal" onConnect={vi.fn()} connecting={false} />)

    const errNode = screen.getByText('sync.banks.countriesLoadError')
    expect(errNode).toBeInTheDocument()
    // Must not sit inside the narrow w-32 country select column.
    expect(errNode.closest('.w-32')).toBeNull()
  })

  it('renders no countries error when the country list loads normally', () => {
    mockedUseBankCountries.mockReturnValue({
      data: ['FR', 'DE'],
      isError: false,
      isSuccess: true,
      error: null,
    } as unknown as ReturnType<typeof useBankCountries>)
    mockedUseSearchInstitutions.mockReturnValue(defaultSearchResult())

    render(<InstitutionSearch layout="page" onConnect={vi.fn()} connecting={false} />)

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText('sync.banks.countriesLoadError')).not.toBeInTheDocument()
  })
})
