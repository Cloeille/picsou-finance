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

const { CryptoWalletTab } = await import('./CryptoWalletTab')

const WALLETS = [
  { id: 1, chain: 'EVM', address: '0x1111111111111111111111111111111111111111', label: 'Ledger', lastSyncedAt: null },
  { id: 2, chain: 'BITCOIN', address: 'bc1qxxxxxxxxxxxxxxxxxxxxxxx', label: 'Cold', lastSyncedAt: null },
]

/**
 * A 4xx with a user-safe `detail`. Deliberately not 5xx: formatApiError maps status >= 500
 * to a generic translated string and discards the backend detail, so a 500-based test would
 * pass whether or not the component renders the real message.
 */
function badRequest(detail: string) {
  return { response: { status: 400, data: { detail } } }
}

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

  render(<CryptoWalletTab />, { wrapper: Wrapper })
}

beforeEach(() => {
  apiGet.mockReset()
  apiPost.mockReset()
  apiDelete.mockReset()
  apiGet.mockResolvedValue({ data: WALLETS })
})

describe('CryptoWalletTab error handling', () => {
  it('shows why adding a wallet failed', async () => {
    apiPost.mockRejectedValue(badRequest("Invalid EVM address '0x123'"))
    renderTab()

    fireEvent.click(await screen.findByText('sync.wallets.add'))
    fireEvent.change(screen.getByLabelText('sync.wallets.address'), {
      target: { value: '0x123' },
    })
    fireEvent.click(screen.getByText('sync.wallets.track'))

    expect(await screen.findByRole('alert')).toHaveTextContent("Invalid EVM address '0x123'")
  })

  it('clears a stale add error when the form is reopened', async () => {
    apiPost.mockRejectedValue(badRequest('Invalid EVM address'))
    renderTab()

    fireEvent.click(await screen.findByText('sync.wallets.add'))
    fireEvent.change(screen.getByLabelText('sync.wallets.address'), {
      target: { value: '0x123' },
    })
    fireEvent.click(screen.getByText('sync.wallets.track'))
    expect(await screen.findByRole('alert')).toBeInTheDocument()

    fireEvent.click(screen.getByText('common.cancel'))
    fireEvent.click(screen.getByText('sync.wallets.add'))

    // A fresh, empty form must not carry the previous failure.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows why a sync failed', async () => {
    apiPost.mockRejectedValue(badRequest('Could not sync your EVM wallet'))
    renderTab()

    await screen.findByText('Ledger')
    fireEvent.click(screen.getAllByRole('button', { name: '' })[0])

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not sync your EVM wallet')
  })

  it('scopes the sync error to the wallet that failed', async () => {
    // One shared mutation drives every row, so an unscoped isError would light up both.
    apiPost.mockRejectedValue(badRequest('sync exploded'))
    renderTab()

    await screen.findByText('Ledger')
    const syncButtons = screen.getAllByRole('button', { name: '' })
    fireEvent.click(syncButtons[0]) // wallet 1 only

    await screen.findByRole('alert')
    expect(screen.getAllByRole('alert')).toHaveLength(1)

    // The failing row is wallet 1's card, not wallet 2's.
    const alert = screen.getByRole('alert')
    expect(alert.closest('[data-slot="card"]')).toHaveTextContent('Ledger')
    expect(alert.closest('[data-slot="card"]')).not.toHaveTextContent('Cold')
  })

  it('blocks every sync button while one is in flight', async () => {
    // One shared mutation drives all rows, and the error block keys on its `variables`.
    // If a second sync can start before the first settles, `variables` is overwritten and
    // wallet A's failure surfaces under wallet B. Disabling all of them is what prevents it.
    let settle: (v: unknown) => void = () => {}
    apiPost.mockImplementation(() => new Promise(resolve => { settle = resolve }))
    renderTab()

    await screen.findByText('Ledger')
    const syncButtons = () => screen.getAllByRole('button', { name: '' }).filter((_, i) => i % 2 === 0)

    fireEvent.click(syncButtons()[0])

    await waitFor(() => expect(syncButtons()[0]).toBeDisabled())
    // The other wallet's button must be disabled too, not just the one clicked.
    expect(syncButtons()[1]).toBeDisabled()

    settle({ data: {} })
    await waitFor(() => expect(syncButtons()[0]).toBeEnabled())
  })

  it('shows why a removal failed, and keeps the dialog open', async () => {
    apiDelete.mockRejectedValue(badRequest('Wallet is still referenced'))
    renderTab()

    await screen.findByText('Ledger')
    const buttons = screen.getAllByRole('button', { name: '' })
    fireEvent.click(buttons[1]) // trash on wallet 1

    fireEvent.click(await screen.findByText('common.delete'))

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Wallet is still referenced'),
    )
    // Still open, so the message is visible where the action was taken.
    expect(screen.getByText('sync.wallets.removeConfirm')).toBeInTheDocument()
  })

  it('clears a stale remove error when the dialog is reopened', async () => {
    apiDelete.mockRejectedValue(badRequest('Wallet is still referenced'))
    renderTab()

    await screen.findByText('Ledger')
    fireEvent.click(screen.getAllByRole('button', { name: '' })[1])
    fireEvent.click(await screen.findByText('common.delete'))
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())

    fireEvent.click(screen.getByText('common.cancel'))
    await waitFor(() =>
      expect(screen.queryByText('sync.wallets.removeConfirm')).not.toBeInTheDocument(),
    )

    fireEvent.click(screen.getAllByRole('button', { name: '' })[1])
    await screen.findByText('sync.wallets.removeConfirm')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
