import '@testing-library/jest-dom'
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { ScpiFund } from '@/types/api'
import { ScpiSyncSummary } from './ScpiSyncSummary'
import { syncRun } from './scpi-sync-run'

const { funds } = vi.hoisted(() => ({ funds: { data: [] as ScpiFund[] } }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: { count?: number }) => (options?.count != null ? `${key}:${options.count}` : key),
  }),
}))
vi.mock('@/features/accounts/hooks', () => ({ useScpiFunds: () => funds }))

const RUN = '2026-10-16T08:00:00Z'
const fund = (fundCode: string, lastOutcome: ScpiFund['lastOutcome'], extra: Partial<ScpiFund> = {}): ScpiFund => ({
  provider: 'CORUM', fundCode, label: `CORUM ${fundCode}`, ignored: false, lastOutcome,
  lastSeenAt: RUN, lastSyncedAt: RUN, accountId: 1, accountName: `CORUM ${fundCode}`, ...extra,
})

describe('ScpiSyncSummary', () => {
  it('counts created, linked and updated funds of the latest run', () => {
    funds.data = [fund('US', 'CREATED'), fund('XL', 'CREATED'), fund('EU', 'UPDATED')]
    render(<ScpiSyncSummary provider="CORUM" completedAt={RUN} />)
    expect(screen.getByText(/scpi.sync.found:3/)).toHaveTextContent(
      'scpi.sync.found:3 — scpi.sync.outcome.CREATED:2, scpi.sync.outcome.UPDATED:1')
  })

  it('never reports a run that applied nothing as a success', () => {
    funds.data = [fund('XL', 'AMBIGUOUS', { accountId: null }), fund('US', 'IGNORED', { accountId: null })]
    render(<ScpiSyncSummary provider="CORUM" completedAt={RUN} />)
    expect(screen.getByText('scpi.sync.noneApplied:2')).toHaveClass('text-amber-600')
    expect(screen.getByText(/scpi.sync.detail.AMBIGUOUS/)).toBeInTheDocument()
    expect(screen.queryByText(/scpi.sync.found/)).toBeNull()
  })

  it('reports an empty space instead of a success', () => {
    funds.data = []
    render(<ScpiSyncSummary provider="SOFIDY" completedAt={RUN} />)
    expect(screen.getByText('scpi.sync.noFund')).toBeInTheDocument()
  })

  it('keeps only the funds of the session\'s own run and provider', () => {
    const older = fund('OLD', 'CREATED', { lastSyncedAt: '2026-10-01T08:00:00Z' })
    const sofidy = fund('DY', 'UPDATED', { provider: 'SOFIDY' })
    const micros = fund('US', 'UPDATED', { lastSyncedAt: '2026-10-16T08:00:00.000000Z' })
    expect(syncRun([older, micros, sofidy], 'CORUM', RUN).map(f => f.fundCode)).toEqual(['US'])
  })

  it('shows nothing before the session has completed a sync', () => {
    funds.data = [fund('US', 'CREATED')]
    const { container } = render(<ScpiSyncSummary provider="CORUM" completedAt={null} />)
    expect(container).toBeEmptyDOMElement()
  })
})
