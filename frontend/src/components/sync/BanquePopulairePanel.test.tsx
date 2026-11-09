import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

const { apiGet } = vi.hoisted(() => ({ apiGet: vi.fn() }))

vi.mock('@/lib/api-client', () => ({
  api: { get: apiGet, post: vi.fn(), delete: vi.fn() },
}))

// Key-echo translator: the copy itself is checked by i18n/banque-populaire-scope.test.ts.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

const { BanquePopulairePanel } = await import('./BanquePopulairePanel')

const NO_SESSION = {
  isActive: false, syncStatus: 'IDLE', lastSyncError: null,
  lastSyncStartedAt: null, lastSyncCompletedAt: null, remembered: false, unsupported: [],
}

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
  return render(<BanquePopulairePanel />, { wrapper: Wrapper })
}

describe('BanquePopulairePanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiGet.mockResolvedValue({ data: NO_SESSION })
  })

  /** The scope line read before connecting is the Banque Populaire one, whose copy names Compte sur Livret and PEL. */
  it('shows the Banque Populaire scope before connecting', async () => {
    renderPanel()

    expect(await screen.findByText('sync.banquePopulaire.scope')).toBeInTheDocument()
    expect(screen.getByText('sync.banquePopulaire.noSession')).toBeInTheDocument()
  })
})
