import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const { apiGet, apiPost, apiDelete, toastSuccess, toastError } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock('@/lib/api-client', () => ({
  api: { get: apiGet, post: apiPost, delete: apiDelete },
}))

// Key-echo translator: assertions target i18n keys, locale content is covered by locales-parity.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, vars?: { count?: number; label?: string }) =>
      vars?.label ? `${vars.label}:` : vars?.count != null ? `${key}:${vars.count}` : key,
  }),
}))

vi.mock('sonner', () => ({
  toast: { success: toastSuccess, error: toastError },
}))

const { LouvePanel, LOUVE_CALLBACK_PATH } = await import('./LouvePanel')

const IDLE = { connected: false, status: null, lastSyncedAt: null }
const CONNECTED = { connected: true, status: 'CONNECTED', lastSyncedAt: '2026-11-17T08:00:00Z' }

function status(value: Record<string, unknown>) {
  apiGet.mockImplementation((url: string) =>
    Promise.resolve({ data: url === '/louve/status' ? value : [] }))
}

function Where() {
  const location = useLocation()
  return <p data-testid="where">{location.pathname + location.search}</p>
}

/** Moves the member to another page, as a click on the sidebar would. */
function GoElsewhere() {
  const navigate = useNavigate()
  return <button type="button" onClick={() => navigate('/other')}>go-elsewhere</button>
}

function renderAt(url: string, { strict = false } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const Wrapper = strict ? StrictMode : ({ children }: { children: React.ReactNode }) => <>{children}</>
  return render(
    <Wrapper>
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path="/sync" element={<><LouvePanel /><Where /><GoElsewhere /></>} />
          <Route path={LOUVE_CALLBACK_PATH} element={<><LouvePanel /><Where /><GoElsewhere /></>} />
          <Route path="/other" element={<><p>other-page</p><Where /></>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
    </Wrapper>,
  )
}

describe('LouvePanel', () => {
  const assign = vi.fn()
  const originalLocation = window.location

  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    toastSuccess.mockReset()
    toastError.mockReset()
    assign.mockReset()
    Object.defineProperty(window, 'location', { configurable: true, value: { ...originalLocation, assign } })
  })

  afterEach(() => {
    Object.defineProperty(window, 'location', { configurable: true, value: originalLocation })
  })

  it('sends the browser to the consent page Louve answered, typing no credentials', async () => {
    status(IDLE)
    apiPost.mockResolvedValue({ data: { authorizeUrl: 'https://app.louveinvest.com/mcp/oauth/authorize?x=1' } })

    renderAt('/sync?tab=louve')
    fireEvent.click(await screen.findByRole('button', { name: 'sync.louve.connect' }))

    await waitFor(() => expect(assign).toHaveBeenCalledWith('https://app.louveinvest.com/mcp/oauth/authorize?x=1'))
    expect(apiPost).toHaveBeenCalledWith('/louve/auth/start')
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  })

  it('an address missing from the allowed origins says where to add it', async () => {
    status(IDLE)
    apiPost.mockRejectedValue({ response: { status: 400, data: { code: 'ORIGIN_NOT_ALLOWED' } } })

    renderAt('/sync?tab=louve')
    fireEvent.click(await screen.findByRole('button', { name: 'sync.louve.connect' }))

    expect(await screen.findByText('sync.louve.errors.originNotAllowed')).toBeInTheDocument()
    expect(assign).not.toHaveBeenCalled()
  })

  it('posts the callback once, then leaves the callback URL', async () => {
    status(IDLE)
    apiPost.mockResolvedValue({ data: CONNECTED })

    renderAt(`${LOUVE_CALLBACK_PATH}?code=c1&state=s1&iss=https%3A%2F%2Fapp.louveinvest.com`)

    await waitFor(() => expect(toastSuccess).toHaveBeenCalledWith('sync.louve.connectedToast'))
    expect(screen.getByTestId('where')).toHaveTextContent('/sync?tab=louve')
    expect(screen.getByTestId('where').textContent).not.toContain('code=')
    expect(apiPost).toHaveBeenCalledTimes(1)
    expect(apiPost).toHaveBeenCalledWith('/louve/auth/complete',
      { code: 'c1', state: 's1', iss: 'https://app.louveinvest.com' })
  })

  it('under StrictMode the code is posted once and leaves the URL, while the call is still pending', async () => {
    status(IDLE)
    let answer: (value: unknown) => void = () => {}
    apiPost.mockReturnValue(new Promise(resolve => { answer = resolve }))

    renderAt(`${LOUVE_CALLBACK_PATH}?code=c1&state=s1&iss=https%3A%2F%2Fapp.louveinvest.com`, { strict: true })

    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/sync?tab=louve'))
    expect(screen.getByTestId('where').textContent).not.toContain('code=')
    expect(apiPost).toHaveBeenCalledTimes(1)

    answer({ data: CONNECTED })
    await waitFor(() => expect(toastSuccess).toHaveBeenCalledWith('sync.louve.connectedToast'))
    expect(screen.getByTestId('where').textContent).not.toMatch(/code=|state=|iss=/)
    expect(apiPost).toHaveBeenCalledTimes(1)
  })

  it('under StrictMode a refused code is reported and the URL is still clean', async () => {
    status(IDLE)
    apiPost.mockRejectedValue({ response: { data: { code: 'AUTH_ATTEMPT_EXPIRED' } } })

    renderAt(`${LOUVE_CALLBACK_PATH}?code=c1&state=s1&iss=https%3A%2F%2Fapp.louveinvest.com`, { strict: true })

    expect(await screen.findByText('sync.louve.errors.authAttemptExpired')).toBeInTheDocument()
    expect(screen.getByTestId('where').textContent).not.toMatch(/code=|state=|iss=/)
    expect(apiPost).toHaveBeenCalledTimes(1)
  })

  it('a late refusal does not take the member back from the page they went to', async () => {
    status(IDLE)
    let fail: (reason: unknown) => void = () => {}
    apiPost.mockReturnValue(new Promise((_, reject) => { fail = reject }))

    renderAt(`${LOUVE_CALLBACK_PATH}?code=c1&state=s1&iss=https%3A%2F%2Fapp.louveinvest.com`, { strict: true })
    await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByRole('button', { name: 'go-elsewhere' }))
    expect(await screen.findByText('other-page')).toBeInTheDocument()

    await act(async () => {
      fail({ response: { data: { code: 'AUTH_ATTEMPT_EXPIRED' } } })
      await new Promise(resolve => setTimeout(resolve, 20))
    })

    expect(screen.getByTestId('where')).toHaveTextContent('/other')
    expect(toastError).toHaveBeenCalledWith('sync.louve.errors.authAttemptExpired')
  })

  it('a refused consent completes nothing and says so', async () => {
    status(IDLE)

    renderAt(`${LOUVE_CALLBACK_PATH}?error=access_denied&state=s1`)

    expect(await screen.findByText('sync.louve.errors.consentDenied')).toBeInTheDocument()
    expect(screen.getByTestId('where')).toHaveTextContent('/sync?tab=louve')
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('a refused code is reported after leaving the callback URL', async () => {
    status(IDLE)
    apiPost.mockRejectedValue({ response: { data: { code: 'AUTH_ATTEMPT_EXPIRED' } } })

    renderAt(`${LOUVE_CALLBACK_PATH}?code=c1&state=s1&iss=https%3A%2F%2Fapp.louveinvest.com`)

    expect(await screen.findByText('sync.louve.errors.authAttemptExpired')).toBeInTheDocument()
    expect(screen.getByTestId('where')).toHaveTextContent('/sync?tab=louve')
  })

  it('an active connection offers sync and disconnect', async () => {
    status(CONNECTED)
    apiPost.mockResolvedValue({ data: CONNECTED })

    renderAt('/sync?tab=louve')
    fireEvent.click(await screen.findByRole('button', { name: 'sync.louve.sync' }))

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/louve/sync'))
    expect(screen.getByRole('button', { name: 'sync.louve.disconnect' })).toBeInTheDocument()
    expect(screen.getByText('sync.louve.connected')).toBeInTheDocument()
  })

  it('a revoked access asks to reconnect', async () => {
    status({ ...CONNECTED, status: 'REAUTH_REQUIRED' })

    renderAt('/sync?tab=louve')

    expect(await screen.findByRole('button', { name: 'sync.louve.reconnect' })).toBeInTheDocument()
    expect(screen.getByText('sync.louve.needsReconnect')).toBeInTheDocument()
    expect(screen.getByText('sync.louve.errors.sessionExpired')).toBeInTheDocument()
    // The tokens Louve refused are still stored: disconnecting revokes them.
    expect(screen.getByRole('button', { name: 'sync.louve.disconnect' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'sync.louve.sync' })).not.toBeInTheDocument()
  })

  it('a failed last sync is said, and a sync can be run again', async () => {
    status({ ...CONNECTED, status: 'ERROR' })

    renderAt('/sync?tab=louve')

    expect(await screen.findByText('sync.louve.errors.lastSyncFailed')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'sync.louve.sync' })).toBeInTheDocument()
  })

  it('disconnecting calls the connection endpoint', async () => {
    status(CONNECTED)
    apiDelete.mockResolvedValue({ data: null })

    renderAt('/sync?tab=louve')
    fireEvent.click(await screen.findByRole('button', { name: 'sync.louve.disconnect' }))
    fireEvent.click(await screen.findByRole('button', { name: 'common.delete' }))

    await waitFor(() => expect(apiDelete).toHaveBeenCalledWith('/louve/connection'))
  })
})
