import { useState, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { useMoney } from '@/hooks/use-money'
import { usePortfolio, type PortfolioLine } from '@/features/accounts/hooks'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import {
  Item,
  ItemContent,
  ItemDescription,
  ItemMedia,
  ItemGroup,
  ItemTitle,
} from '@/components/ui/item'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { Search, TrendingUp, TrendingDown } from 'lucide-react'
import { cn } from '@/lib/utils'
import { accountTypeLabelKey } from '@/lib/constants'
import { ErrorState } from '@/components/shared/ErrorState'
import { formatApiError } from '@/lib/errors'
import { HoldingDetailModal } from '@/components/shared/HoldingDetailModal'
import type { Account } from '@/types/api'

type FilterType = 'all' | Account['type'] | 'cash'

const FILTER_TABS: { value: FilterType; labelKey: string; match: (type: Account['type']) => boolean }[] = [
  { value: 'all', labelKey: 'dashboard.allHoldings', match: () => true },
  { value: 'PEA', labelKey: 'accountTypes.pea', match: (t) => t === 'PEA' },
  { value: 'COMPTE_TITRES', labelKey: 'accountTypes.compteTitres', match: (t) => t === 'COMPTE_TITRES' },
  { value: 'CRYPTO', labelKey: 'accountTypes.crypto', match: (t) => t === 'CRYPTO' },
  { value: 'cash', labelKey: 'portfolio.cash', match: (t) => ['CHECKING', 'SAVINGS', 'LEP', 'LIVRET_A', 'LDDS', 'LIVRET_JEUNE', 'PEL', 'CEL', 'OTHER']
      .includes(t) },
]

function HoldingsItem({ line, onClick }: { line: PortfolioLine; onClick: () => void }) {
  const money = useMoney()
  const { t } = useTranslation()

  return (
    <Item variant="muted" className="cursor-pointer hover:bg-muted/50 transition-colors" onClick={onClick}>
      <ItemMedia>
        <div className="flex size-12 items-center justify-center rounded-lg border text-sm font-semibold">
          {line.ticker ? line.ticker.slice(0, 4) : line.name.slice(0, 3).toUpperCase()}
        </div>
      </ItemMedia>
      <ItemContent>
        <ItemTitle>{line.name}</ItemTitle>
        <ItemDescription className="text-sm">
          {line.quantity > 0
            ? `${money.quantity(line.quantity.toLocaleString())} ${t('dashboard.shares')} · ${line.accountName}`
            : line.accountName}
        </ItemDescription>
      </ItemContent>
      <div className="flex shrink-0 items-center gap-6">
        <Badge variant="outline">
          {t(accountTypeLabelKey(line.accountType))}
        </Badge>
        <div className="flex flex-col items-end gap-0.5">
          <span className="text-sm text-muted-foreground">
            {t('portfolio.value')}
          </span>
          <CurrencyDisplay value={line.valueEur} className="font-medium tabular-nums" />
          {line.pnlPercent != null && (
            <span
              className={cn(
                'inline-flex items-center gap-1 text-xs font-medium tabular-nums',
                line.pnlPercent >= 0 ? 'text-emerald-500' : 'text-red-500',
              )}
            >
              {line.pnlPercent >= 0
                ? <TrendingUp className="size-3" />
                : <TrendingDown className="size-3" />}
              {line.pnlPercent >= 0 ? '+' : ''}{line.pnlPercent.toFixed(1)}%
            </span>
          )}
        </div>
      </div>
    </Item>
  )
}

export function HoldingsCard() {
  const { t } = useTranslation()
  const { data: lines, isLoading, isError, error, refetch } = usePortfolio()
  const [search, setSearch] = useState('')
  const [filter, setFilter] = useState<FilterType>('all')
  const [selectedLine, setSelectedLine] = useState<PortfolioLine | null>(null)

  const filtered = useMemo(() => {
    let result = lines ?? []
    const tab = FILTER_TABS.find(f => f.value === filter)
    if (tab) {
      result = result.filter(l => tab.match(l.accountType))
    }
    if (search) {
      const q = search.toLowerCase()
      result = result.filter(l =>
        l.name.toLowerCase().includes(q) ||
        (l.ticker ?? '').toLowerCase().includes(q) ||
        l.accountName.toLowerCase().includes(q),
      )
    }
    return result.sort((a, b) => b.valueEur - a.valueEur)
  }, [lines, filter, search])

  if (isLoading) {
    return (
      <Card>
        <CardHeader>
          <div className="flex flex-wrap items-center gap-3">
            <Skeleton className="h-5 w-36" />
            <Skeleton className="h-7 w-60 max-w-sm" />
          </div>
          <div className="flex gap-1">
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} className="h-7 w-20" />
            ))}
          </div>
        </CardHeader>
        <CardContent>
          <div className="space-y-2">
            {Array.from({ length: 4 }).map((_, i) => (
              <Skeleton key={i} className="h-16 w-full rounded-xl" />
            ))}
          </div>
        </CardContent>
      </Card>
    )
  }

  // Rendered rather than hidden: an empty dashboard card reads as "nothing to show",
  // which is exactly the wrong conclusion when the holdings could not be loaded.
  if (isError) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>{t('dashboard.holdings')}</CardTitle>
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

  if (!lines || lines.length === 0) return null

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('dashboard.holdings')}</CardTitle>
        <CardDescription>{t('dashboard.holdingsDescription')}</CardDescription>
        <div className="flex flex-wrap items-center gap-3">
          <div className="relative max-w-sm flex-1">
            <Search className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              placeholder={t('dashboard.searchHoldings')}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="pl-9"
            />
          </div>
          <div className="flex flex-wrap gap-2">
            {FILTER_TABS.map(tab => (
              <button
                key={tab.value}
                onClick={() => setFilter(tab.value)}
                className={cn(
                  'inline-flex h-10 min-w-32 items-center justify-center whitespace-nowrap rounded-md border px-6 text-sm font-medium transition-[background-color,color,border-color]',
                  filter === tab.value
                    ? 'bg-primary text-primary-foreground'
                    : 'bg-background hover:bg-muted',
                )}
              >
                {t(tab.labelKey)}
              </button>
            ))}
          </div>
        </div>
      </CardHeader>
      <CardContent>
        <ItemGroup>
          {filtered.map(line => (
            <HoldingsItem key={line.id} line={line} onClick={() => setSelectedLine(line)} />
          ))}
        </ItemGroup>
        {filtered.length === 0 && lines.length > 0 && (
          <p className="py-4 text-center text-sm text-muted-foreground">{t('common.noResults')}</p>
        )}
      </CardContent>

      <HoldingDetailModal line={selectedLine} onClose={() => setSelectedLine(null)} />
    </Card>
  )
}
