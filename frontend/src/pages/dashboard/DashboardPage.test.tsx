import '@testing-library/jest-dom'
import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DashboardData } from '@/types/api'

const hooks = vi.hoisted(() => ({
  useDashboard: vi.fn(),
  useNetWorthIntraday: vi.fn(),
  usePnl: vi.fn(),
  useHistory: vi.fn(),
  useBankSyncStatus: vi.fn(),
}))

vi.mock('@/features/dashboard/hooks', () => ({
  useDashboard: hooks.useDashboard,
  useNetWorthIntraday: hooks.useNetWorthIntraday,
  usePnl: hooks.usePnl,
}))
vi.mock('@/features/history/hooks', () => ({ useHistory: hooks.useHistory }))
vi.mock('@/features/sync/hooks', () => ({ useBankSyncStatus: hooks.useBankSyncStatus }))
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key === 'goals.noData' ? '—' : key,
  }),
}))
vi.mock('@/components/shared/CurrencyDisplay', () => ({
  CurrencyDisplay: ({ value }: { value: number }) => <span data-testid="currency">{value}</span>,
}))
vi.mock('@/components/shared/PageHeader', () => ({
  PageHeader: ({ title, actions }: { title: string; actions?: ReactNode }) => <header><h1>{title}</h1>{actions}</header>,
}))
vi.mock('@/components/shared/NetWorthChart', () => ({ NetWorthChart: () => null }))
vi.mock('@/components/shared/DistributionPie', () => ({ DistributionPie: () => null }))
vi.mock('@/components/shared/LoadingSkeleton', () => ({ LoadingSkeleton: () => null }))
vi.mock('@/components/shared/HoldingsCard', () => ({ HoldingsCard: () => null }))
vi.mock('@/components/shared/LiabilitiesCard', () => ({ LiabilitiesCard: () => null }))
vi.mock('@/components/property/RealEstateSummaryCard', () => ({ RealEstateSummaryCard: () => null }))
vi.mock('@/components/sync/SyncAllModal', () => ({ SyncAllModal: () => null }))
vi.mock('@/pages/goals/GoalDetailModal', () => ({ GoalDetailModal: () => null }))
vi.mock('@/components/ui/card', () => {
  const Part = ({ children }: { children?: ReactNode }) => <div>{children}</div>
  return {
    Card: Part,
    CardContent: Part,
    CardDescription: Part,
    CardFooter: Part,
    CardHeader: Part,
    CardTitle: Part,
    CardAction: Part,
  }
})
vi.mock('@/components/ui/dropdown-menu', () => {
  const Part = ({ children }: { children?: ReactNode }) => <div>{children}</div>
  return {
    DropdownMenu: Part,
    DropdownMenuContent: Part,
    DropdownMenuItem: Part,
    DropdownMenuTrigger: Part,
  }
})
vi.mock('@/components/ui/item', () => {
  const Part = ({ children }: { children?: ReactNode }) => <div>{children}</div>
  return { Item: Part, ItemContent: Part, ItemDescription: Part, ItemFooter: Part, ItemGroup: Part }
})
vi.mock('@/components/ui/button', () => ({
  Button: ({ children, onClick }: { children?: ReactNode; onClick?: () => void }) => <button onClick={onClick}>{children}</button>,
}))
vi.mock('@/components/ui/progress', () => ({ Progress: () => null }))

const { DashboardPage } = await import('./DashboardPage')

const DASHBOARD_DATA: DashboardData = {
  totalNetWorth: 1200,
  totalLiabilities: 0,
  netWorthHistory: [],
  distribution: [],
  liabilities: [],
  goalSummaries: [],
}

function renderDashboard() {
  return render(
    <MemoryRouter>
      <DashboardPage />
    </MemoryRouter>,
  )
}

describe('DashboardPage portfolio performance', () => {
  beforeEach(() => {
    hooks.useDashboard.mockReturnValue({ data: DASHBOARD_DATA, isLoading: false })
    hooks.useNetWorthIntraday.mockReturnValue({ data: [] })
    hooks.usePnl.mockReturnValue({
      data: {
        total: 1200,
        invested: null,
        pnl: null,
        pnlPercent: null,
        valueAtFrom: 1200,
        rangePnl: null,
        rangePnlPercent: null,
      },
    })
    hooks.useHistory.mockReturnValue({ data: [] })
    hooks.useBankSyncStatus.mockReturnValue({ data: [] })
  })

  it('shows unavailable performance instead of zero when the selected range has unknown PnL', () => {
    renderDashboard()

    const row = screen.getByText('dashboard.portfolioPerformance').parentElement
    expect(row).toHaveTextContent('—')
    expect(row?.querySelector('[data-testid="currency"]')).toBeNull()
    expect(row?.querySelector('svg')).toBeNull()
  })

  it('does not substitute known live PnL when the selected range PnL is unknown', () => {
    hooks.usePnl.mockReturnValue({
      data: {
        total: 1200,
        invested: 1000,
        pnl: 200,
        pnlPercent: 20,
        valueAtFrom: null,
        rangePnl: null,
        rangePnlPercent: null,
      },
    })

    renderDashboard()

    const row = screen.getByText('dashboard.portfolioPerformance').parentElement
    expect(row).toHaveTextContent('—')
    expect(row?.querySelector('[data-testid="currency"]')).toBeNull()
  })

  it('keeps a known zero range PnL as zero', () => {
    hooks.usePnl.mockReturnValue({
      data: {
        total: 1200,
        invested: 1200,
        pnl: 0,
        pnlPercent: 0,
        valueAtFrom: 1200,
        rangePnl: 0,
        rangePnlPercent: 0,
      },
    })

    renderDashboard()

    const row = screen.getByText('dashboard.portfolioPerformance').parentElement
    expect(row?.querySelector('[data-testid="currency"]')).toHaveTextContent('0')
    expect(row).toHaveTextContent('(0.0%)')
    expect(row?.querySelector('svg')).not.toBeNull()
  })
})
