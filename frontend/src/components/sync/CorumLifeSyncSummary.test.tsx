import '@testing-library/jest-dom'
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { Account } from '@/types/api'
import { CorumLifeSyncSummary } from './CorumLifeSyncSummary'

const { accounts } = vi.hoisted(() => ({ accounts: { data: [] as Account[] } }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: { count?: number }) => (options?.count != null ? `${key}:${options.count}` : key),
  }),
}))
vi.mock('@/features/accounts/hooks', () => ({ useAccounts: () => accounts }))
vi.mock('@/components/shared/CurrencyDisplay', () => ({
  CurrencyDisplay: ({ value, className }: { value: number; className?: string }) => (
    <span className={className}>{`EUR ${value}`}</span>
  ),
}))

const RUN = '2026-11-05T08:00:00Z'
const account = (id: number, extra: Partial<Account> = {}): Account => ({
  id, name: `CORUM Life ${id}`, type: 'ASSURANCE_VIE', provider: 'CORUM', currency: 'EUR',
  currentBalance: 1000 * id, currentBalanceEur: 1000 * id, lastSyncedAt: RUN, isManual: false,
  color: '#8b5cf6', ticker: null, logoUrl: null, logoKey: null, createdAt: RUN, hidden: false, ...extra,
})

describe('CorumLifeSyncSummary', () => {
  it('lists the life-insurance accounts of the latest run with their balance in Ledger Ink', () => {
    accounts.data = [account(1), account(2)]
    render(<CorumLifeSyncSummary completedAt={RUN} />)
    expect(screen.getByText('sync.corum.life.synced:2')).toBeInTheDocument()
    expect(screen.getByText('CORUM Life 1')).toBeInTheDocument()
    expect(screen.getByText('EUR 2000')).toHaveClass('text-foreground')
  })

  it('ignores manual, other-provider, other-type and older accounts', () => {
    accounts.data = [
      account(1, { isManual: true }),
      account(2, { provider: 'Sofidy' }),
      account(3, { type: 'SCPI' }),
      account(4, { lastSyncedAt: '2026-11-01T08:00:00Z' }),
    ]
    const { container } = render(<CorumLifeSyncSummary completedAt={RUN} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('warns about the contracts the run wrote nowhere', () => {
    accounts.data = [account(1)]
    render(<CorumLifeSyncSummary completedAt={RUN} ambiguous={2} />)
    expect(screen.getByText('sync.corum.life.synced:1')).toBeInTheDocument()
    expect(screen.getByText('sync.corum.life.ambiguous:2')).toHaveClass('text-amber-600')
  })

  it('warns even when the run wrote no life-insurance account', () => {
    accounts.data = []
    render(<CorumLifeSyncSummary completedAt={RUN} ambiguous={1} />)
    expect(screen.getByText('sync.corum.life.ambiguous:1')).toBeInTheDocument()
    expect(screen.queryByText(/sync.corum.life.synced/)).not.toBeInTheDocument()
  })

  it('shows nothing before the session has completed a sync', () => {
    accounts.data = [account(1)]
    const { container } = render(<CorumLifeSyncSummary completedAt={null} />)
    expect(container).toBeEmptyDOMElement()
  })
})
