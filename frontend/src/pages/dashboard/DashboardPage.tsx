import { useState, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { useDashboard, useNetWorthIntraday, usePnl } from '@/features/dashboard/hooks'
import { useHistory } from '@/features/history/hooks'
import { useBankSyncStatus } from '@/features/sync/hooks'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { PageHeader } from '@/components/shared/PageHeader'
import { NetWorthChart } from '@/components/shared/NetWorthChart'
import { DistributionPie } from '@/components/shared/DistributionPie'
import { LoadingSkeleton } from '@/components/shared/LoadingSkeleton'
import { HoldingsCard } from '@/components/shared/HoldingsCard'
import { LiabilitiesCard } from '@/components/shared/LiabilitiesCard'
import { RealEstateSummaryCard } from '@/components/property/RealEstateSummaryCard'
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
import { TrendingUp, TrendingDown, Plus, RefreshCw, ChevronDown, AlertTriangle } from 'lucide-react'
import { GoalDetailModal } from '@/pages/goals/GoalDetailModal'

type WealthMode = 'net' | 'gross' | 'financial'

const EXCLUDED_FINANCIAL = new Set(['CHECKING', 'LOAN'])

export function DashboardPage() {
  const { t } = useTranslation()
  const [showSyncModal, setShowSyncModal] = useState(false)
  const [range, setRange] = useState<TimeRange>('1Y')
  const [wealthMode, setWealthMode] = useState<WealthMode>('net')
  const [detailGoalId, setDetailGoalId] = useState<number | null>(null)

  const { data, isLoading } = useDashboard(range)
  const { data: bankConnections } = useBankSyncStatus()
  const failedBanks = bankConnections?.filter(b => b.status === 'FAILED') ?? []

  // Account IDs filtered by the selected wealth mode — drives both the headline value
  // and the history chart so the curve never includes categories the mode excludes.
  const chartAccountIds = useMemo(() => {
    if (!data) return []
    switch (wealthMode) {
      case 'gross':
        // Assets only (no liabilities)
        return data.distribution.map(d => d.accountId)
      case 'financial':
        // Financial assets: drop checking and any loan that slipped into distribution
        return data.distribution
          .filter(d => !EXCLUDED_FINANCIAL.has(d.accountType))
          .map(d => d.accountId)
      default:
        // Net worth: all accounts including liabilities
        return [...data.distribution.map(d => d.accountId), ...data.liabilities.map(l => l.accountId)]
    }
  }, [data, wealthMode])
  // Accounts that actually have holdings/tickers — used for PnL only.
  // Mirrors the chart filter: in "financial" mode we still drop excluded categories
  // (in practice CHECKING/LOAN don't carry holdings, so this is a no-op safety net).
  const investmentAccountIds = useMemo(() => {
    if (!data) return []
    return data.distribution
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
    const sumAccounts = (items: typeof data.distribution, exclude?: Set<string>) =>
      items
        .filter(d => !exclude || !exclude.has(d.accountType))
        .reduce((s, d) => s + d.balanceEur, 0)

    switch (wealthMode) {
      case 'gross':
        return sumAccounts(data.distribution)
      case 'financial':
        return sumAccounts(data.distribution, EXCLUDED_FINANCIAL)
      default:
        return data.totalNetWorth
    }
  }, [data, wealthMode])

  // PnL: use range fields when available, fall back to live PnL (ALL range)
  const pnl = pnlData?.rangePnl != null ? pnlData.rangePnl : (pnlData?.pnl ?? 0)
  const pnlPct = pnlData?.rangePnlPercent != null
    ? pnlData.rangePnlPercent.toFixed(1)
    : (pnlData?.pnlPercent != null ? pnlData.pnlPercent.toFixed(1) : null)
  const pnlPositive = pnl >= 0

  if (isLoading || !data) {
    return <LoadingSkeleton />
  }

  const wealthModeOptions: { value: WealthMode; key: string }[] = [
    { value: 'net', key: 'netWorth' },
    { value: 'gross', key: 'grossWorth' },
    { value: 'financial', key: 'financialWorth' },
  ]

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
            <RefreshCw className="mr-2 size-4" />
            {t('dashboard.syncAccounts')}
          </Button>
        }
      />

      {/* Failed bank sync banner */}
      {failedBanks.length > 0 && (
        <div className="flex items-center justify-between gap-3 rounded-lg border border-destructive/20 bg-destructive/10 px-4 py-3">
          <div className="flex items-center gap-3">
            <AlertTriangle className="size-4 shrink-0 text-destructive" />
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
                  <ChevronDown className="size-3.5" />
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
            <div className="mt-2 flex items-center gap-4 text-sm">
              <span className="text-muted-foreground">
                {t('dashboard.totalAssets')}:
              </span>
              <CurrencyDisplay value={data.totalNetWorth + (data.totalLiabilities ?? 0)} />
              <span className="text-red-500">-</span>
              <span className="text-muted-foreground">
                {t('dashboard.totalLiabilities')}:
              </span>
              <CurrencyDisplay value={data.totalLiabilities ?? 0} className="text-red-500" />
            </div>
          )}

          <div className="mt-3 flex items-center gap-2">
            {pnlPositive
              ? <TrendingUp className="text-emerald-500" size={18} />
              : <TrendingDown className="text-red-500" size={18} />}
            <span
              className={`text-sm font-medium ${pnlPositive ? 'text-emerald-500' : 'text-red-500'}`}
            >
              <CurrencyDisplay value={pnl} />
              {pnlPct !== null && (
                <span className="ml-1 font-normal text-muted-foreground">
                  ({pnlPositive ? '+' : ''}{pnlPct}%)
                </span>
              )}
            </span>
            <span className="text-sm text-muted-foreground">{t('dashboard.portfolioPerformance')}</span>
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

        <DistributionPie data={data.distribution} />
      </div>

      {/* Property wealth: gross, mortgage debt and the equity between them. Renders nothing
          when the member owns no property. */}
      <RealEstateSummaryCard />

      {/* Goals section */}
      <Card>
        <CardHeader>
          <CardTitle>{t('dashboard.goals')}</CardTitle>
          <CardDescription>{t('dashboard.goalsDescription')}</CardDescription>
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
                  className="flex-col items-stretch rounded-4xl px-4 py-3 gap-4 cursor-pointer"
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
                    <Progress value={goal.percentComplete ?? 0} className="h-2.5 [&_[data-slot=progress-indicator]]:bg-emerald-500" />
                  </ItemContent>
                  <ItemFooter>
                    <span className="text-sm text-muted-foreground">
                      {Math.round(goal.percentComplete ?? 0)}% {t('dashboard.achieved')}
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

      {/* Goal detail modal */}
      <GoalDetailModal goalId={detailGoalId} onClose={() => setDetailGoalId(null)} />
    </div>
  )
}
