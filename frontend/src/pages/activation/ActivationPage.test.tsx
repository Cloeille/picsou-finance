import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import en from '@/i18n/locales/en.json'

// Real-English lookup by default: a wrong key path fails the assertion instead of echoing the key.
// `keysOnly` makes every translated string a bracketed key, so untranslated copy stands out.
const i18n = vi.hoisted(() => ({ keysOnly: false }))
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) => {
      if (i18n.keysOnly) return `[${key}]`
      const value = key
        .split('.')
        .reduce<unknown>((acc, part) => (acc as Record<string, unknown> | undefined)?.[part], en)
      const str = typeof value === 'string' ? value : key
      return Object.entries(opts ?? {}).reduce((acc, [k, v]) => acc.replace(`{{${k}}}`, String(v)), str)
    },
  }),
}))

// zustand stores read localStorage/sessionStorage at module evaluation (node 26 jsdom gap).
function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => void m.set(k, String(v)),
    removeItem: (k) => void m.delete(k),
    clear: () => m.clear(),
    key: (i) => [...m.keys()][i] ?? null,
    get length() { return m.size },
  } as Storage
}
vi.stubGlobal('localStorage', memoryStorage())
vi.stubGlobal('sessionStorage', memoryStorage())

const { getInfo } = vi.hoisted(() => ({ getInfo: vi.fn() }))
vi.mock('@/features/sso/api', () => ({ ssoApi: { getInfo } }))
const { submitSsoStart } = vi.hoisted(() => ({ submitSsoStart: vi.fn() }))
vi.mock('@/features/sso/start', () => ({ submitSsoStart }))
const { activate } = vi.hoisted(() => ({ activate: vi.fn() }))
vi.mock('@/features/auth/api', () => ({ authApi: { activate } }))

const { ActivationPage } = await import('./ActivationPage')
const { ssoKeys } = await import('@/features/sso/hooks')

const SSO_ON = { enabled: true, providerName: 'Zitadel' }

function renderPage(entry = '/activate/tok-123') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/activate/:token" element={<ActivationPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return queryClient
}

/** Visible text and placeholders that did not come out of the translator. */
function untranslatedCopy(root: HTMLElement): string[] {
  const translated = /^\[[\w.]+\]$/
  const found: string[] = []
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const text = node.textContent?.trim() ?? ''
    if (text && !translated.test(text)) found.push(text)
  }
  root.querySelectorAll('[placeholder]').forEach(element => {
    const placeholder = element.getAttribute('placeholder') ?? ''
    if (!translated.test(placeholder)) found.push(placeholder)
  })
  return found
}

describe('ActivationPage', () => {
  beforeEach(() => {
    getInfo.mockReset()
    submitSsoStart.mockReset()
    activate.mockReset()
  })
  afterEach(() => {
    i18n.keysOnly = false
  })

  it('keeps Activate with the provider disabled until the acknowledgement is ticked', async () => {
    getInfo.mockResolvedValue(SSO_ON)
    renderPage()

    const ssoButton = await screen.findByRole('button', { name: 'Activate with Zitadel' })
    fireEvent.click(ssoButton)

    expect(ssoButton).toBeDisabled()
    expect(submitSsoStart).not.toHaveBeenCalled()
  })

  it('starts the SSO activation with the link token once the acknowledgement is ticked', async () => {
    getInfo.mockResolvedValue(SSO_ON)
    renderPage()
    const ssoButton = await screen.findByRole('button', { name: 'Activate with Zitadel' })
    fireEvent.click(screen.getByLabelText(en.activation.acknowledge))

    fireEvent.click(ssoButton)

    expect(submitSsoStart).toHaveBeenCalledTimes(1)
    expect(submitSsoStart).toHaveBeenCalledWith({ intent: 'activate', token: 'tok-123', ack: true })
  })

  it('disables Activate with the provider again when the acknowledgement is unticked', async () => {
    getInfo.mockResolvedValue(SSO_ON)
    renderPage()
    const ssoButton = await screen.findByRole('button', { name: 'Activate with Zitadel' })
    const acknowledgement = screen.getByLabelText(en.activation.acknowledge)
    fireEvent.click(acknowledgement)
    expect(ssoButton).toBeEnabled()

    fireEvent.click(acknowledgement)

    expect(ssoButton).toBeDisabled()
  })

  it('offers only the password when SSO is off on the instance', async () => {
    getInfo.mockResolvedValue({ enabled: false, providerName: 'SSO' })
    const queryClient = renderPage()

    await waitFor(() => expect(queryClient.getQueryState(ssoKeys.info)?.status).toBe('success'))
    expect(screen.queryByRole('button', { name: /^Activate with/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: en.activation.submit })).toBeInTheDocument()
  })

  it.each([
    ['ACTIVATION_INVALID', en.ssoErrors.ACTIVATION_INVALID],
    ['NOT_A_REAL_CODE', en.ssoErrors.generic],
  ])('translates the %s refusal from the IdP round trip, never showing the raw code', async (code, message) => {
    getInfo.mockResolvedValue(SSO_ON)
    renderPage(`/activate/tok-123?ssoError=${code}`)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(message)
    expect(alert).not.toHaveTextContent(code)
  })

  it('reports a refused password activation as a failed link, not as raw transport text', async () => {
    getInfo.mockResolvedValue(SSO_ON)
    activate.mockRejectedValue(
      Object.assign(new Error('Request failed with status code 401'), {
        response: { status: 401, data: { detail: 'Invalid credentials' } },
      }),
    )
    renderPage()
    fireEvent.click(screen.getByLabelText(en.activation.acknowledge))
    fireEvent.change(screen.getByLabelText(en.activation.password), { target: { value: 'correct-horse-1' } })
    fireEvent.change(screen.getByLabelText(en.activation.confirmPassword), { target: { value: 'correct-horse-1' } })

    fireEvent.click(screen.getByRole('button', { name: en.activation.submit }))

    expect(await screen.findByRole('alert')).toHaveTextContent(en.activation.errors.failed)
    expect(activate).toHaveBeenCalledWith('tok-123', 'correct-horse-1', true)
  })

  it('renders every visible string through the translator', async () => {
    i18n.keysOnly = true
    getInfo.mockResolvedValue(SSO_ON)
    renderPage('/activate/tok-123?ssoError=ACTIVATION_INVALID')

    await screen.findByRole('button', { name: '[activation.ssoButton]' })

    expect(untranslatedCopy(document.body)).toEqual([])
  })
})
