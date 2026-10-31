import { useTranslation } from 'react-i18next'
import { useMoney } from '@/hooks/use-money'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { SortableTableHead } from '@/components/shared/SortableTableHead'
import { useTableSort, type SortColumns } from '@/hooks/use-table-sort'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { cn, localeFromLanguage } from '@/lib/utils'
import { TrendingDown, TrendingUp } from 'lucide-react'
import {
  Table,
  TableBody,
  TableCell,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { useRealizedPnl } from '@/features/accounts/hooks'
import type { RealizedLot } from '@/types/api'

type RealizedSortKey =
  | 'ticker' | 'name' | 'date' | 'quantity' | 'avgCost' | 'proceeds' | 'realized'

/** Module scope keeps the map identity-stable for the sort memo. */
const REALIZED_COLUMNS: SortColumns<RealizedLot, RealizedSortKey> = {
  ticker: { kind: 'text', value: l => l.ticker },
  name: { kind: 'text', value: l => l.name ?? l.ticker },
  // ISO-8601 dates sort correctly as text; parsing them to compare would buy nothing.
  date: { kind: 'text', value: l => l.date },
  quantity: { kind: 'number', value: l => l.quantity },
  avgCost: { kind: 'number', value: l => l.avgCost },
  proceeds: { kind: 'number', value: l => l.proceeds },
  realized: { kind: 'number', value: l => l.realized },
}

/** A stable reference for the loading pass, so the sort memo does not see a new array each render. */
const EMPTY_LOTS: RealizedLot[] = []

interface RealizedPnlSectionProps {
  accountId: number
  /** Only investment accounts have realized P&L; skip the query otherwise. */
  enabled?: boolean
}

/**
 * Realized gains/losses on closed (fully or partially sold) positions. Surfaces the P&L that
 * vanishes from the holdings view once a position is sold. Renders nothing until there is at
 * least one sell.
 */
export function RealizedPnlSection({ accountId, enabled = true }: RealizedPnlSectionProps) {
  const money = useMoney()
  const { t, i18n } = useTranslation()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)
  const { data } = useRealizedPnl(accountId, enabled)
  // These are closed lots, so there is no "value" column: the realized gain is the figure the
  // reader ranks them by. Most recent first would be the other defensible default, and the date
  // column is one click away.
  const { rows, sort, toggle } = useTableSort(data?.lots ?? EMPTY_LOTS, REALIZED_COLUMNS, {
    key: 'realized',
    direction: 'desc',
  })

  // Defensive: demo mode returns {} for unhandled endpoints, so guard `lots` too.
  if (!data || !data.lots || data.lots.length === 0) return null

  const { currency, realizedTotal } = data
  const positive = realizedTotal >= 0

  const formatDate = (iso: string) => {
    const d = new Date(iso)
    return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString(locale)
  }

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0">
        <CardTitle className="text-base">{t('realized.title')}</CardTitle>
        <div className={cn('flex items-center gap-1.5 font-medium', positive ? 'text-emerald-500' : 'text-red-500')}>
          {positive ? <TrendingUp className="size-4" /> : <TrendingDown className="size-4" />}
          <CurrencyDisplay value={realizedTotal} currency={currency} showSign />
        </div>
      </CardHeader>
      <CardContent>
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <SortableTableHead sortKey="ticker" sort={sort} onSort={toggle}>{t('holdings.ticker')}</SortableTableHead>
                <SortableTableHead sortKey="name" sort={sort} onSort={toggle}>{t('holdings.name')}</SortableTableHead>
                <SortableTableHead sortKey="date" sort={sort} onSort={toggle}>{t('accounts.transactionDate')}</SortableTableHead>
                <SortableTableHead sortKey="quantity" sort={sort} onSort={toggle} align="right">{t('realized.qtySold')}</SortableTableHead>
                <SortableTableHead sortKey="avgCost" sort={sort} onSort={toggle} align="right">{t('realized.avgCost')}</SortableTableHead>
                <SortableTableHead sortKey="proceeds" sort={sort} onSort={toggle} align="right">{t('realized.proceeds')}</SortableTableHead>
                <SortableTableHead sortKey="realized" sort={sort} onSort={toggle} align="right">{t('realized.realizedGains')}</SortableTableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((lot, i) => (
                <TableRow key={`${lot.ticker}-${lot.date}-${i}`}>
                  <TableCell className="font-mono font-medium">{lot.ticker}</TableCell>
                  <TableCell>{lot.name ?? lot.ticker}</TableCell>
                  <TableCell>{formatDate(lot.date)}</TableCell>
                  <TableCell className="text-right">{money.quantity(lot.quantity)}</TableCell>
                  <TableCell className="text-right">
                    <CurrencyDisplay value={lot.avgCost} currency={currency} publicQuote className="text-sm" />
                  </TableCell>
                  <TableCell className="text-right">
                    <CurrencyDisplay value={lot.proceeds} currency={currency} className="text-sm" />
                  </TableCell>
                  <TableCell className={cn('text-right', lot.realized >= 0 ? 'text-emerald-500' : 'text-red-500')}>
                    <CurrencyDisplay value={lot.realized} currency={currency} showSign className="text-sm" />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </CardContent>
    </Card>
  )
}
