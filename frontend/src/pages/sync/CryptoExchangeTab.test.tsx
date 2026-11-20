import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

const { apiGet, apiPost, apiDelete } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
}))

vi.mock('@/lib/api-client', () => ({
  api: {
    get: apiGet,
    post: apiPost,
    delete: apiDelete,
  },
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}))

// ConfirmDialog is Radix-based; jsdom lacks these.
vi.stubGlobal(
  'ResizeObserver',
  class {
    observe() {}
    unobserve() {}
    disconnect() {}
  },
)
Object.defineProperty(document, 'elementFromPoint', {
  configurable: true,
  value: vi.fn(() => document.body),
})

const { CryptoExchangeTab } = await import('./CryptoExchangeTab')

function renderTab() {
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider
        client={
          new QueryClient({
            defaultOptions: {
              queries: { retry: false },
              mutations: { retry: false },
            },
          })
        }
      >
        {children}
      </QueryClientProvider>
    )
  }

  render(<CryptoExchangeTab />, { wrapper: Wrapper })
}

async function openAddForm() {
  renderTab()
  // With no exchange connected, the empty state offers the same action as the toolbar button.
  fireEvent.click((await screen.findAllByText('sync.exchanges.add'))[0])
}

beforeEach(() => {
  apiGet.mockReset()
  apiPost.mockReset()
  apiDelete.mockReset()
  apiGet.mockResolvedValue({ data: [] })
  apiPost.mockResolvedValue({ data: {} })
})

/**
 * The exchange picker drives which credentials the form asks for. Meria authenticates with a
 * single read-only API key, and the backend rejects a stray secret with a 400 — so the form
 * hiding the field is not cosmetic, it is what keeps that error unreachable.
 */
describe('CryptoExchangeTab credential fields', () => {
  it('offers every supported exchange', async () => {
    await openAddForm()

    expect(screen.getByRole('button', { name: 'BINANCE' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'KRAKEN' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'MERIA' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'OKX' })).toBeInTheDocument()
  })

  it('asks for a secret for Binance and hides it for Meria', async () => {
    await openAddForm()

    expect(screen.getByLabelText('sync.exchanges.apiSecret')).toBeRequired()
    expect(screen.queryByText('sync.exchanges.apiKeyOnly')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'MERIA' }))

    expect(screen.queryByLabelText('sync.exchanges.apiSecret')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('sync.exchanges.apiPassphrase')).not.toBeInTheDocument()
    // Explain the absence rather than letting the field vanish silently.
    expect(screen.getByText('sync.exchanges.apiKeyOnly')).toBeInTheDocument()
  })

  it('submits a Meria connection with the API key alone', async () => {
    await openAddForm()

    fireEvent.click(screen.getByRole('button', { name: 'MERIA' }))
    fireEvent.change(screen.getByLabelText('sync.exchanges.apiKey'), {
      target: { value: 'meria-key' },
    })
    fireEvent.click(screen.getByText('sync.exchanges.connect'))

    await waitFor(() => expect(apiPost).toHaveBeenCalled())
    const [url, body] = apiPost.mock.calls[0]
    expect(url).toBe('/crypto/exchange')
    expect(body.type).toBe('MERIA')
    expect(body.apiKey).toBe('meria-key')
    expect(body.apiSecret).toBeUndefined()
    expect(body).not.toHaveProperty('apiPassphrase')
    expect(body).not.toHaveProperty('region')
  })

  it('drops a secret typed before switching to a single-key exchange', async () => {
    // Otherwise the leftover value would be posted and rejected with a 400 the user never
    // caused on purpose.
    await openAddForm()

    fireEvent.change(screen.getByLabelText('sync.exchanges.apiKey'), { target: { value: 'k' } })
    fireEvent.change(screen.getByLabelText('sync.exchanges.apiSecret'), {
      target: { value: 'left-over-secret' },
    })

    fireEvent.click(screen.getByRole('button', { name: 'MERIA' }))

    // Cleared, not merely hidden: coming back shows an empty field.
    fireEvent.click(screen.getByRole('button', { name: 'BINANCE' }))
    expect(screen.getByLabelText('sync.exchanges.apiSecret')).toHaveValue('')

    fireEvent.click(screen.getByRole('button', { name: 'MERIA' }))
    fireEvent.click(screen.getByText('sync.exchanges.connect'))

    await waitFor(() => expect(apiPost).toHaveBeenCalled())
    expect(apiPost.mock.calls[0][1].apiSecret).toBeUndefined()
  })

  it('still sends both credentials for a two-credential exchange', async () => {
    await openAddForm()

    fireEvent.change(screen.getByLabelText('sync.exchanges.apiKey'), { target: { value: 'k' } })
    fireEvent.change(screen.getByLabelText('sync.exchanges.apiSecret'), { target: { value: 's' } })
    fireEvent.click(screen.getByText('sync.exchanges.connect'))

    await waitFor(() => expect(apiPost).toHaveBeenCalled())
    expect(apiPost.mock.calls[0][1]).toMatchObject({ type: 'BINANCE', apiKey: 'k', apiSecret: 's' })
  })

  it('shows the OKX setup hint and requires and submits all four connection fields', async () => {
    await openAddForm()
    expect(screen.queryByLabelText('sync.exchanges.apiPassphrase')).not.toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: 'sync.exchanges.region' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'OKX' }))
    expect(screen.getByText('sync.exchanges.okxHint')).toBeInTheDocument()
    const key = screen.getByLabelText('sync.exchanges.apiKey')
    const secret = screen.getByLabelText('sync.exchanges.apiSecret')
    expect(key).toBeRequired()
    expect(secret).toBeRequired()
    fireEvent.change(key, { target: { value: 'test-key' } })
    fireEvent.change(secret, { target: { value: 'test-secret' } })

    const passphrase = screen.getByLabelText('sync.exchanges.apiPassphrase')
    expect(passphrase).toHaveAttribute('type', 'password')
    expect(passphrase).toBeRequired()
    expect(passphrase).toHaveAttribute('maxlength', '200')
    fireEvent.change(passphrase, { target: { value: 'test-passphrase' } })

    const region = screen.getByRole('combobox', { name: 'sync.exchanges.region' })
    const connect = screen.getByRole('button', { name: 'sync.exchanges.connect' })
    expect(region).toBeRequired()
    expect(region).toHaveTextContent('sync.exchanges.regionPlaceholder')
    expect(connect).toBeDisabled()
    fireEvent.submit(connect.closest('form')!)
    expect(apiPost).not.toHaveBeenCalled()

    fireEvent.keyDown(region, { key: 'ArrowDown' })
    const options = screen.getAllByRole('option')
    expect(options.map(option => option.textContent)).toEqual([
      'sync.exchanges.regions.GLOBAL', 'sync.exchanges.regions.EEA', 'sync.exchanges.regions.US',
    ])
    fireEvent.click(options[1])
    fireEvent.click(connect)

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/crypto/exchange', {
      type: 'OKX', apiKey: 'test-key', apiSecret: 'test-secret', apiPassphrase: 'test-passphrase', region: 'EEA',
    }))
  })

  it('shows the stored region on the connection card', async () => {
    apiGet.mockResolvedValue({ data: [{ id: 1, exchangeType: 'OKX', status: 'CONNECTED', region: 'EEA' }] })
    renderTab()

    expect(await screen.findByText(/sync\.exchanges\.regions\.EEA/)).toBeInTheDocument()
  })
})
