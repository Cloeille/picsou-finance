import '@testing-library/jest-dom'
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import type { Account } from '@/types/api'

const RUN = '2026-11-05T08:00:00Z'
const { allAccounts } = vi.hoisted(() => ({
  allAccounts: { data: [] as Account[], isPending: false },
}))

vi.mock('@/features/accounts/hooks', () => ({
  useAllAccounts: () => allAccounts,
  // The visible-only list would miss a hidden contract: the panel must not read it.
  useAccounts: () => ({ data: [], isPending: false }),
}))
vi.mock('@/features/sync/hooks', () => ({
  useProviderStatus: () => ({ data: { lastSyncCompletedAt: RUN, lifeAmbiguousCount: 0 } }),
}))
vi.mock('@/components/sync/SidecarSessionPanel', () => ({
  SidecarSessionPanel: ({ successSummary }: { successSummary: ReactNode }) => <div>{successSummary}</div>,
}))
vi.mock('@/components/scpi/ScpiSyncSummary', () => ({
  ScpiSyncSummary: ({ hideWhenEmpty }: { hideWhenEmpty?: boolean }) => (
    <span data-testid="scpi-summary">{hideWhenEmpty ? 'hidden-when-empty' : 'warns-when-empty'}</span>
  ),
}))
vi.mock('@/components/sync/CorumLifeSyncSummary', () => ({ CorumLifeSyncSummary: () => null }))

const { CorumPanel } = await import('./CorumPanel')

const life = (extra: Partial<Account> = {}): Account => ({
  id: 1, name: 'CORUM Life', type: 'ASSURANCE_VIE', provider: 'CORUM', currency: 'EUR',
  currentBalance: 1000, currentBalanceEur: 1000, lastSyncedAt: RUN, isManual: false,
  color: '#8b5cf6', ticker: null, logoUrl: null, logoKey: null, createdAt: RUN, hidden: false, ...extra,
})

describe('CorumPanel', () => {
  it('counts a hidden Life contract the run updated, so no empty-SCPI warning shows', () => {
    allAccounts.data = [life({ hidden: true })]
    render(<CorumPanel />)
    expect(screen.getByTestId('scpi-summary')).toHaveTextContent('hidden-when-empty')
  })

  it('still warns about an empty SCPI list when no Life contract was written', () => {
    allAccounts.data = []
    render(<CorumPanel />)
    expect(screen.getByTestId('scpi-summary')).toHaveTextContent('warns-when-empty')
  })
})
