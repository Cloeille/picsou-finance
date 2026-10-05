import { useState, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { usePortfolio, type PortfolioLine } from '@/features/accounts/hooks'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { PriceFreshnessDot } from '@/components/shared/PriceFreshnessDot'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { EmptyState } from '@/components/shared/EmptyState'
import { ErrorState } from '@/components/shared/ErrorState'
import { formatApiError } from '@/lib/errors'
import { cn } from '@/lib/utils'
import { accountTypeLabelKey } from '@/lib/constants'
import { Search } from 'lucide-react'

type SortBy = 'value' | 'pnl'

const SORT_OPTIONS: { value: SortBy; labelKey: string }[] = [
  { value: 'value', labelKey: 'portfolio.sortByValue' },
  { value: 'pnl', labelKey: 'portfolio.sortByPnl' },
]

function PortfolioItem({ line }: { line: PortfolioLine }) {
  const { t } = useTranslation()
  const isPositive = (line.pnlEur ?? 0) >= 0

  return (
    <div className="flex items-center gap-4 rounded-xl bg-muted/30 px-4 py-3 transition-colors hover:bg-muted/60">
      {/* Icon / Ticker badge */}
      <div
        className="flex size-12 shrink-0 items-center justify-center rounded-lg border text-sm font-semibold"
        style={{ borderColor: line.accountColor }}
      >
        {line.ticker ? line.ticker.slice(0, 4) : line.name.slice(0, 3).toUpperCase()}
      </div>

      {/* Info */}
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium">{line.name}</p>
        {line.ticker && (
          <p className="text-sm text-muted-foreground">
            {line.accountName}
          </p>
        )}
      </div>

      {/* Right side */}
      <div className="flex shrink-0 items-center gap-5">
        <Badge variant="outline">
          {t(accountTypeLabelKey(line.accountType))}
        </Badge>

        {/* PnL */}
        {line.pnlEur != null && (
          <div className="flex flex-col items-end gap-0.5">
            <span className="text-sm text-muted-foreground">{t('portfolio.pnl')}</span>
            <span className={cn('text-sm font-medium tabular-nums', isPositive ? 'text-emerald-500' : 'text-red-500')}>
              {isPositive ? '+' : ''}{line.pnlPercent?.toFixed(1)}%
            </span>
          </div>
        )}

        {/* Value */}
        <div className="flex flex-col items-end gap-0.5">
          <span className="text-sm text-muted-foreground">
            {t('portfolio.value')}
          </span>
          <div className="inline-flex items-center gap-1.5">
            <PriceFreshnessDot priceUpdatedAt={line.priceUpdatedAt} />
            <CurrencyDisplay value={line.valueEur} className="font-medium tabular-nums" />
          </div>
        </div>
      </div>
    </div>
  )
}

export function PortfolioView() {
  const { t } = useTranslation()
  const { data: lines, isLoading, isError, error, refetch } = usePortfolio()
  const [search, setSearch] = useState('')
  const [sortBy, setSortBy] = useState<SortBy>('value')

  const sorted = useMemo(() => {
    let result = lines ?? []

    if (search) {
      const q = search.toLowerCase()
      result = result.filter(l =>
        l.name.toLowerCase().includes(q) ||
        (l.ticker ?? '').toLowerCase().includes(q) ||
        l.accountName.toLowerCase().includes(q)
      )
    }

    if (sortBy === 'value') {
      result = [...result].sort((a, b) => b.valueEur - a.valueEur)
    } else {
      result = [...result].sort((a, b) => (b.pnlEur ?? 0) - (a.pnlEur ?? 0))
    }

    return result
  }, [lines, sortBy, search])

  const totalValue = useMemo(
    () => (sorted ?? []).reduce((sum, l) => sum + l.valueEur, 0),
    [sorted],
  )

  if (isLoading) {
    return (
      <Card>
        <CardHeader>
          <Skeleton className="h-5 w-40" />
        </CardHeader>
        <CardContent>
          <div className="space-y-3">
            <Skeleton className="h-9 w-full max-w-md" />
            <div className="flex gap-1">
              <Skeleton className="h-9 w-32" />
              <Skeleton className="h-9 w-28" />
            </div>
            {Array.from({ length: 6 }).map((_, i) => (
              <Skeleton key={i} className="h-16 w-full rounded-xl" />
            ))}
          </div>
        </CardContent>
      </Card>
    )
  }

  // Before the empty check: a failed load has no lines either, and "no holdings" is the
  // one message that must never stand in for "we could not read your holdings".
  if (isError) {
    return (
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t('portfolio.title')}</CardTitle>
        </CardHeader>
        <CardContent>
          <ErrorState
            title={t('error.crashTitle')}
            message={formatApiError(error, t)}
            onRetry={() => void refetch()}
          />
        </CardContent>
      </Card>
    )
  }

  if (!lines || lines.length === 0) {
    return (
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t('portfolio.title')}</CardTitle>
        </CardHeader>
        <CardContent>
          <EmptyState title={t('portfolio.noHoldings')} />
        </CardContent>
      </Card>
    )
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <CardTitle className="text-base">{t('portfolio.title')}</CardTitle>
          <div className="flex items-center gap-2 text-sm">
            <span className="text-muted-foreground">{t('portfolio.totalValue')}</span>
            <CurrencyDisplay value={totalValue} className="font-semibold" />
          </div>
        </div>
        {/* Search + sort */}
        <div className="flex flex-wrap items-center gap-3">
          <div className="relative max-w-sm flex-1">
            <Search
              className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
            />
            <Input
              placeholder={t('portfolio.searchPlaceholder')}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="pl-9"
            />
          </div>
          <div className="flex flex-wrap gap-2">
            {SORT_OPTIONS.map(opt => (
              <button
                key={opt.value}
                onClick={() => setSortBy(opt.value)}
                className={cn(
                  'inline-flex h-10 min-w-32 items-center justify-center whitespace-nowrap rounded-md border px-6 text-sm font-medium transition-[background-color,color,border-color]',
                  sortBy === opt.value
                    ? 'bg-primary text-primary-foreground'
                    : 'bg-background hover:bg-muted',
                )}
              >
                {t(opt.labelKey)}
              </button>
            ))}
          </div>
        </div>
      </CardHeader>
      <CardContent>
        {sorted.length === 0 ? (
          <EmptyState title={t('common.noResults')} />
        ) : (
          <div className="space-y-2">
            {sorted.map(line => (
              <PortfolioItem key={line.id} line={line} />
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  )
}
