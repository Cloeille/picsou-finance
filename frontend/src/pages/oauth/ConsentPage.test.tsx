import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import { scopeI18nKey } from '@/features/accessKeys/scopes'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) => {
      if (opts) {
        return Object.entries(opts).reduce(
          (acc, [k, v]) => acc.replace(`{{${k}}}`, String(v)),
          key,
        )
      }
      return key
    },
  }),
}))

const { getConsentInfo } = vi.hoisted(() => ({ getConsentInfo: vi.fn() }))
vi.mock('@/features/oauthConsent/api', () => ({
  oauthConsentApi: { getConsentInfo },
}))

const { ConsentPage } = await import('./ConsentPage')

function makeClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

function renderConsentPage(search: string) {
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={makeClient()}>
        <MemoryRouter initialEntries={[`/consent${search}`]}>
          <Routes>
            <Route path="/consent" element={children} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    )
  }
  return render(<ConsentPage />, { wrapper: Wrapper })
}

const CONSENT_INFO = {
  clientName: 'Claude Desktop',
  requestedScopes: ['accounts:read', 'transactions:write'],
  state: 'xyz123',
}

describe('ConsentPage', () => {
  beforeEach(() => {
    getConsentInfo.mockReset()
    getConsentInfo.mockResolvedValue(CONSENT_INFO)
  })

  it('renders scopes from a mocked consent-info, labelled via the shared scopes helper', async () => {
    renderConsentPage('?client_id=claude-ai&scope=accounts%3Aread%20transactions%3Awrite&state=xyz123')

    await waitFor(() => expect(getConsentInfo).toHaveBeenCalledWith({
      clientId: 'claude-ai',
      scope: 'accounts:read transactions:write',
      state: 'xyz123',
    }))

    // Scope labels come from features/accessKeys/scopes.ts's i18n-key helper, not a
    // separately-maintained label set — parity is asserted against the real (unmocked) helper.
    expect(
      await screen.findByText(`accessKeys.scopes.${scopeI18nKey('accounts:read')}.label`),
    ).toBeInTheDocument()
    expect(
      screen.getByText(`accessKeys.scopes.${scopeI18nKey('transactions:write')}.label`),
    ).toBeInTheDocument()

    // Every requested scope starts pre-approved.
    const readCheckbox = document.querySelector<HTMLInputElement>('input[value="accounts:read"]')
    const writeCheckbox = document.querySelector<HTMLInputElement>('input[value="transactions:write"]')
    expect(readCheckbox?.checked).toBe(true)
    expect(writeCheckbox?.checked).toBe(true)
  })

  it('checking/unchecking a scope updates which scope inputs the form will submit', async () => {
    renderConsentPage('?client_id=claude-ai&scope=accounts%3Aread%20transactions%3Awrite&state=xyz123')

    await screen.findByText(`accessKeys.scopes.${scopeI18nKey('accounts:read')}.label`)

    const writeCheckbox = document.querySelector<HTMLInputElement>('input[value="transactions:write"]')!
    expect(writeCheckbox.checked).toBe(true)

    fireEvent.click(writeCheckbox)
    expect(writeCheckbox.checked).toBe(false)

    fireEvent.click(writeCheckbox)
    expect(writeCheckbox.checked).toBe(true)
  })

  it('Approve submits only the selected scopes, plus client_id and state', async () => {
    renderConsentPage('?client_id=claude-ai&scope=accounts%3Aread%20transactions%3Awrite&state=xyz123')

    await screen.findByText(`accessKeys.scopes.${scopeI18nKey('accounts:read')}.label`)

    // Uncheck one scope before approving — only the remaining one should travel with the form.
    const writeCheckbox = document.querySelector<HTMLInputElement>('input[value="transactions:write"]')!
    fireEvent.click(writeCheckbox)

    const form = document.querySelector('[data-testid="consent-form"]') as HTMLFormElement
    expect(form).toHaveAttribute('method', 'post')
    expect(form).toHaveAttribute('action', '/oauth2/authorize')

    let captured: FormData | null = null
    form.addEventListener('submit', (e) => {
      e.preventDefault()
      captured = new FormData(form)
    })

    fireEvent.click(screen.getByRole('button', { name: 'oauthConsent.approve' }))

    expect(captured).not.toBeNull()
    expect(captured!.getAll('scope')).toEqual(['accounts:read'])
    expect(captured!.get('client_id')).toBe('claude-ai')
    expect(captured!.get('state')).toBe('xyz123')
  })

  it('Deny submits the same form with no scope fields', async () => {
    renderConsentPage('?client_id=claude-ai&scope=accounts%3Aread%20transactions%3Awrite&state=xyz123')

    await screen.findByText(`accessKeys.scopes.${scopeI18nKey('accounts:read')}.label`)

    const form = document.querySelector('[data-testid="consent-form"]') as HTMLFormElement
    let captured: FormData | null = null
    form.addEventListener('submit', (e) => {
      e.preventDefault()
      captured = new FormData(form)
    })

    fireEvent.click(screen.getByRole('button', { name: 'oauthConsent.deny' }))

    expect(captured).not.toBeNull()
    expect(captured!.getAll('scope')).toEqual([])
    expect(captured!.get('client_id')).toBe('claude-ai')
  })
})
