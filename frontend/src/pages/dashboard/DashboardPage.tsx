import { useState, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { useDashboard, useNetWorthIntraday, usePnl } from '@/features/dashboard/hooks'
import { useHistory } from '@/features/history/hooks'
import { useBankSyncStatus } from '@/features/sync/hooks'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { PageHeader } from '@/components/shared/PageHeader'
import { NetWorthChart } from '@/components/shared/NetWorthChart'
import { DistributionPie } from '@/components/shared/DistributionPie'
import { LoadingSkeleton } from '@/components/shared/LoadingSkeleton'
import { ErrorState } from '@/components/shared/ErrorState'
import { HoldingsCard } from '@/components/shared/HoldingsCard'
import { LiabilitiesCard } from '@/components/shared/LiabilitiesCard'
import { RealEstateSummaryCard } from '@/components/property/RealEstateSummaryCard'
import { AddAccountModal } from '@/components/shared/AddAccountModal'
import { SyncAllModal } from '@/components/sync/SyncAllModal'
import { type TimeRange } from '@/components/shared/TimeRangeSelector'
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
  CardAction,
} from '@/components/ui/card'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {
  Item,
  ItemContent,
  ItemDescription,
  ItemFooter,
  ItemGroup,
} from '@/components/ui/item'
import { Button } from '@/components/ui/button'
import { Progress } from '@/components/ui/progress'
import { TrendUp, TrendDown, Plus, ArrowsClockwise, CaretDown, Warning } from '@phosphor-icons/react'
import { GoalDetailModal } from '@/pages/goals/GoalDetailModal'
import { formatPercent } from '@/lib/utils'

type WealthMode = 'net' | 'gross' | 'financial'

// Financial wealth = liquid and investable assets only. Checking is the day-to-day
// cash excluded by design; real estate is tangible property; physical precious
// metals are tangible movables; OTHER has an unknown nature and must not inflate
// the figure by default. A peer loan is a real asset but neither liquid nor
// invested, so it counts in net and gross wealth only. SCPI stays in: a share
// of a real-estate fund is an intangible, exchangeable security.
const EXCLUDED_FINANCIAL = new Set(['CHECKING', 'LOAN', 'REAL_ESTATE', 'PRECIOUS_METALS', 'PEER_LOAN', 'OTHER'])

export function DashboardPage() {
  const { t } = useTranslation()
  const [showSyncModal, setShowSyncModal] = useState(false)
  const [showAddAccountModal, setShowAddAccountModal] = useState(false)
  const [range, setRange] = useState<TimeRange>('1Y')
  const [wealthMode, setWealthMode] = useState<WealthMode>('net')
  const [detailGoalId, setDetailGoalId] = useState<number | null>(null)

  const { data, isLoading, isError, refetch } = useDashboard(range)
  const { data: bankConnections } = useBankSyncStatus()
  const failedBanks = bankConnections?.filter(b => b.status === 'FAILED') ?? []

  // Account IDs filtered by the selected wealth mode — drives both the headline value
  // and the history chart so the curve never includes categories the mode excludes.
  const chartAccountIds = useMemo(() => {
    if (!data) return []
    switch (wealthMode) {
      case 'gross':
        // Assets only (no liabilities), receivables included
        return data.assets.map(d => d.accountId)
      case 'financial':
        // Financial assets: drop everything in EXCLUDED_FINANCIAL (checking,
        // loans, real estate, physical metals, peer loans, OTHER)
        return data.assets
          .filter(d => !EXCLUDED_FINANCIAL.has(d.accountType))
          .map(d => d.accountId)
      default:
        // Net worth: all accounts including liabilities and receivables
        return [...data.assets.map(d => d.accountId), ...data.liabilities.map(l => l.accountId)]
    }
  }, [data, wealthMode])
  // Accounts that actually have holdings/tickers — used for PnL only.
  // Mirrors the chart filter: in "financial" mode we still drop excluded categories
  // (e.g. a real-estate or metals account carrying holdings).
  const investmentAccountIds = useMemo(() => {
    if (!data) return []
    return data.assets
      .filter(d => d.hasHoldings)
      .filter(d => wealthMode !== 'financial' || !EXCLUDED_FINANCIAL.has(d.accountType))
      .map(d => d.accountId)
  }, [data, wealthMode])
  const historyMonths = useMemo(() => {
    if (range === 'ALL') return 1200
    if (range === '3M') return 3
    if (range === '1M' || range === '7D') return 1
    if (range === 'YTD') return new Date().getMonth() + 1
    return 12
  }, [range])
  const { data: history } = useHistory(chartAccountIds, historyMonths)
  const { data: intraday } = useNetWorthIntraday(chartAccountIds, range === '24H')

  // Compute fromDate for PnL range
  const pnlFromDate = useMemo(() => {
    const now = new Date()
    let from: Date
    switch (range) {
      case '24H': from = new Date(now.getTime() - 24 * 60 * 60 * 1000); break
      case '7D': from = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 7); break
      case '1M': from = new Date(now.getFullYear(), now.getMonth() - 1, now.getDate()); break
      case '3M': from = new Date(now.getFullYear(), now.getMonth() - 3, now.getDate()); break
      case 'YTD': from = new Date(now.getFullYear(), 0, 1); break
      case '1Y': from = new Date(now.getFullYear() - 1, now.getMonth(), now.getDate()); break
      default: return undefined // ALL → live PnL only
    }
    return from.toISOString().slice(0, 10)
  }, [range])

  const { data: pnlData } = usePnl(investmentAccountIds, pnlFromDate)

  // Compute wealth value based on selected mode
  const wealthValue = useMemo(() => {
    if (!data) return 0
    const sumAccounts = (items: typeof data.assets, exclude?: Set<string>) =>
      items
        .filter(d => !exclude || !exclude.has(d.accountType))
        .reduce((s, d) => s + d.balanceEur, 0)

    switch (wealthMode) {
      case 'gross':
        return sumAccounts(data.assets)
      case 'financial':
        return sumAccounts(data.assets, EXCLUDED_FINANCIAL)
      default:
        return data.totalNetWorth
    }
  }, [data, wealthMode])

  // Distribution shown beside the hero: in financial mode it mirrors the same
  // filter as the headline value and the history curve, so the donut never
  // shows a category the figure excludes. Percentages are recomputed over the
  // filtered total — the backend shares cover the whole portfolio.
  const displayDistribution = useMemo(() => {
    if (!data) return []
    if (wealthMode !== 'financial') return data.distribution
    const filtered = data.distribution.filter(d => !EXCLUDED_FINANCIAL.has(d.accountType))
    const total = filtered.reduce((s, d) => s + d.balanceEur, 0)
    if (total <= 0) return filtered.map(d => ({ ...d, percentage: 0 }))
    return filtered.map(d => ({ ...d, percentage: (d.balanceEur / total) * 100 }))
  }, [data, wealthMode])

  // ALL uses live PnL; selected ranges use only their range PnL. Unknown is not zero.
  const pnl = range === 'ALL' ? (pnlData?.pnl ?? null) : (pnlData?.rangePnl ?? null)
  const pnlPct = range === 'ALL'
    ? (pnlData?.pnlPercent ?? null)
    : (pnlData?.rangePnlPercent ?? null)
  const pnlPositive = pnl != null && pnl >= 0

  if (isError) {
    return (
      <div className="space-y-6">
        <PageHeader title={t('dashboard.title')} />
        <ErrorState title={t('common.error')} onRetry={() => void refetch()} />
      </div>
    )
  }

  if (isLoading || !data) {
    return <LoadingSkeleton />
  }

  const wealthModeOptions: { value: WealthMode; key: string }[] = [
    { value: 'net', key: 'netWorth' },
    { value: 'gross', key: 'grossWorth' },
    { value: 'financial', key: 'financialWorth' },
  ]

  const hasNoAccounts =
    data.assets.length === 0 &&
    data.liabilities.length === 0 &&
    data.totalNetWorth === 0

  return (
    <div className="space-y-6">
      <PageHeader
        title={t('dashboard.title')}
        actions={
          <Button
            variant="outline"
            size="default"
            className="h-10 min-w-44 px-6 text-sm"
            onClick={() => setShowSyncModal(true)}
          >
            <ArrowsClockwise className="size-4" />
            {t('dashboard.syncAccounts')}
          </Button>
        }
      />

      {/* First-account CTA for an empty space */}
      {hasNoAccounts && (
        <Card className="rounded-xl bg-card">
          <CardContent className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
            <div className="space-y-1">
              <p className="text-sm font-bold text-foreground">
                {t('desktop.emptyDashboard.title')}
              </p>
              <p className="text-sm text-muted-foreground">
                {t('desktop.emptyDashboard.description')}
              </p>
            </div>
            <Button
              type="button"
              onClick={() => setShowAddAccountModal(true)}
              className="shrink-0"
            >
              <Plus className="size-4" aria-hidden="true" />
              {t('desktop.emptyDashboard.cta')}
            </Button>
          </CardContent>
        </Card>
      )}

      {/* Failed bank sync banner */}
      {failedBanks.length > 0 && (
        <div className="flex items-center justify-between gap-3 rounded-lg border border-destructive/20 bg-destructive/10 px-4 py-3">
          <div className="flex items-center gap-3">
            <Warning className="size-4 shrink-0 text-destructive" />
            <p className="text-sm font-medium text-destructive">
              {t('dashboard.bankSyncFailed', {
                count: failedBanks.length,
                institution: failedBanks[0].institutionName,
              })}
            </p>
          </div>
          <Button
            variant="outline"
            size="sm"
            className="shrink-0 border-destructive/30 text-destructive hover:bg-destructive/10 hover:text-destructive"
            onClick={() => setShowSyncModal(true)}
          >
            {t('dashboard.fixConnections')}
          </Button>
        </div>
      )}

      {/* Wealth hero */}
      <Card>
        <CardContent>
          <div className="flex items-center">
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button className="inline-flex items-center gap-1 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground">
                  {t(`dashboard.${wealthModeOptions.find(m => m.value === wealthMode)!.key}`)}
                  <CaretDown className="size-3.5" />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent
                align="start"
                className="w-64 min-w-64 border border-border bg-background p-1.5 ring-0 before:hidden"
              >
                {wealthModeOptions.map(m => (
                  <DropdownMenuItem
                    key={m.value}
                    onClick={() => setWealthMode(m.value)}
                    className={wealthMode === m.value ? 'font-semibold text-foreground' : ''}
                  >
                    {t(`dashboard.${m.key}`)}
                  </DropdownMenuItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
          <CurrencyDisplay value={wealthValue} className="text-4xl font-bold" />

          {(data.totalLiabilities ?? 0) > 0 && wealthMode === 'net' && (
            <div className="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm tabular-nums">
              <span>
                <span className="text-muted-foreground">
                  {t('common.labelColon', { label: t('dashboard.totalAssets') })}{' '}
                </span>
                <CurrencyDisplay value={data.totalNetWorth + (data.totalLiabilities ?? 0)} />
              </span>
              <span className="text-red-500" aria-hidden="true">-</span>
              <span>
                <span className="text-muted-foreground">
                  {t('common.labelColon', { label: t('dashboard.totalLiabilities') })}{' '}
                </span>
                <CurrencyDisplay value={data.totalLiabilities ?? 0} className="text-red-500" />
              </span>
            </div>
          )}

          <div
            className="mt-3 flex items-center gap-2"
            data-testid="portfolio-performance"
          >
            <span className="sr-only">{t('dashboard.portfolioPerformance')}</span>
            {pnl != null ? (
              <>
                {pnlPositive
                  ? <TrendUp className="text-emerald-500" size={18} />
                  : <TrendDown className="text-red-500" size={18} />}
                <span
                  className={`text-sm font-medium ${pnlPositive ? 'text-emerald-500' : 'text-red-500'}`}
                >
                  <CurrencyDisplay value={pnl} />
                  {pnlPct !== null && (
                    <span className="ml-1 font-normal text-muted-foreground">
                      ({formatPercent(pnlPct, { signed: true })})
                    </span>
                  )}
                </span>
              </>
            ) : (
              <span className="text-sm font-medium text-muted-foreground">{t('goals.noData')}</span>
            )}
          </div>
        </CardContent>
      </Card>

      {/* Charts row */}
      <div className="grid gap-4 md:grid-cols-2">
        <Card className="h-[420px]">
          <CardHeader>
            {/* Titled by wealth mode — the curve plots (net/gross/financial) wealth,
                not gain/loss (issue #18). */}
            <CardTitle>{t(`dashboard.evolution.${wealthMode}`)}</CardTitle>
          </CardHeader>
          <CardContent className="min-h-0 flex-1">
            <NetWorthChart data={history ?? []} intraday={intraday ?? []} range={range} onRangeChange={setRange} />
          </CardContent>
        </Card>

        <DistributionPie data={displayDistribution} />
      </div>

      {/* Property wealth: gross, mortgage debt and the equity between them. Renders nothing
          when the member owns no property. */}
      <RealEstateSummaryCard />

      {/* Goals section */}
      <Card>
        <CardHeader>
          <CardTitle>{t('dashboard.goals')}</CardTitle>
          {data.goalSummaries.length > 0 && (
            <CardAction>
              <Button variant="outline" size="sm" asChild>
                <Link to="/goals">
                  <Plus />
                  {t('dashboard.newGoal')}
                </Link>
              </Button>
            </CardAction>
          )}
        </CardHeader>
        <CardContent>
          {data.goalSummaries.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t('dashboard.noGoals')}</p>
          ) : (
            <ItemGroup className="gap-3">
              {/* Savings targets only — the backend already filters recurring plans out of
                  goalSummaries, and this card is built entirely around a completion percentage. */}
              {data.goalSummaries
                .filter((g) => g.percentComplete != null && g.targetAmount != null)
                .sort((a, b) => (b.percentComplete ?? 0) - (a.percentComplete ?? 0))
                .slice(0, 3)
                .map((goal) => (
                <Item
                  key={goal.id}
                  variant="muted"
                  className="flex-col items-stretch rounded-xl px-4 py-3 gap-4 cursor-pointer"
                  onClick={() => setDetailGoalId(goal.id)}
                >
                  <ItemContent className="gap-3">
                    <ItemDescription className="cn-font-heading text-sm font-semibold text-foreground">
                      {goal.name}
                    </ItemDescription>
                    <CurrencyDisplay
                      value={goal.currentTotal}
                      className="text-3xl font-semibold tabular-nums"
                    />
                    <Progress value={goal.percentComplete ?? 0} className="[&_[data-slot=progress-indicator]]:bg-emerald-500" />
                  </ItemContent>
                  <ItemFooter>
                    <span className="text-sm text-muted-foreground">
                      {formatPercent(goal.percentComplete ?? 0, { fractionDigits: 0 })} {t('dashboard.achieved')}
                    </span>
                    <CurrencyDisplay
                      value={goal.targetAmount ?? 0}
                      className="text-sm font-medium tabular-nums"
                    />
                  </ItemFooter>
                </Item>
              ))}
              {data.goalSummaries.length > 3 && (
                <Button variant="ghost" size="sm" className="w-full" asChild>
                  <Link to="/goals">
                    {t('dashboard.otherGoals', { count: data.goalSummaries.length - 3 })}
                  </Link>
                </Button>
              )}
            </ItemGroup>
          )}
        </CardContent>
        {data.goalSummaries.length > 0 && (
          <CardFooter>
            <CardDescription className="text-center">
              {t('dashboard.goalsSummary')}
            </CardDescription>
          </CardFooter>
        )}
      </Card>

      {/* Liabilities overview */}
      {data.liabilities.length > 0 && (
        <LiabilitiesCard
          liabilities={data.liabilities}
          totalMonthlyPayment={data.totalMonthlyPayment ?? null}
        />
      )}

      {/* Holdings overview */}
      <HoldingsCard />

      {/* Sync all modal */}
      <SyncAllModal open={showSyncModal} onOpenChange={setShowSyncModal} />

      {/* Add first account modal (mounted on demand) */}
      {showAddAccountModal && (
        <AddAccountModal open={showAddAccountModal} onOpenChange={setShowAddAccountModal} />
      )}

      {/* Goal detail modal */}
      <GoalDetailModal goalId={detailGoalId} onClose={() => setDetailGoalId(null)} />
    </div>
  )
}
