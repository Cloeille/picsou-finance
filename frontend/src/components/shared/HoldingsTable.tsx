import { useTranslation } from 'react-i18next'
import { useMoney } from '@/hooks/use-money'
import type { HoldingResponse } from '@/types/api'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { PriceFreshnessDot } from '@/components/shared/PriceFreshnessDot'
import { SortableTableHead } from '@/components/shared/SortableTableHead'
import { useTableSort, type SortColumns } from '@/hooks/use-table-sort'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { Pencil, Trash2 } from 'lucide-react'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'

type HoldingSortKey =
  | 'ticker' | 'name' | 'quantity' | 'avgBuyIn' | 'price' | 'value' | 'pnl' | 'pnlPercent'

/**
 * Declared at module scope so the map is one stable object: the sort memo depends on it, and a
 * literal rebuilt each render would invalidate the memo on every keystroke elsewhere on the page.
 */
const HOLDING_COLUMNS: SortColumns<HoldingResponse, HoldingSortKey> = {
  ticker: { kind: 'text', value: h => h.ticker },
  // Falls back to the ticker exactly as the cell does, so the column sorts on what is on screen.
  name: { kind: 'text', value: h => h.name ?? h.ticker },
  quantity: { kind: 'number', value: h => h.quantity },
  avgBuyIn: { kind: 'number', value: h => h.averageBuyIn },
  price: { kind: 'number', value: h => h.currentPrice },
  value: { kind: 'number', value: h => h.currentValueEur },
  pnl: { kind: 'number', value: h => h.pnlEur },
  pnlPercent: { kind: 'number', value: h => h.pnlPercent },
}

interface HoldingsTableProps {
  holdings: HoldingResponse[]
  onEdit?: (holding: HoldingResponse) => void
  onDelete?: (holding: HoldingResponse) => void
}

export function HoldingsTable({ holdings, onEdit, onDelete }: HoldingsTableProps) {
  const money = useMoney()
  const { t } = useTranslation()
  // The biggest line first is what the reader is nearly always after; the account detail page
  // remounts this table per account, which is what puts the sort back here.
  const { rows, sort, toggle } = useTableSort(holdings, HOLDING_COLUMNS, {
    key: 'value',
    direction: 'desc',
  })

  if (holdings.length === 0) return null

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{t('accounts.holdings')}</CardTitle>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <SortableTableHead sortKey="ticker" sort={sort} onSort={toggle}>{t('holdings.ticker')}</SortableTableHead>
              <SortableTableHead sortKey="name" sort={sort} onSort={toggle}>{t('holdings.name')}</SortableTableHead>
              <SortableTableHead sortKey="quantity" sort={sort} onSort={toggle} align="right">{t('holdings.quantity')}</SortableTableHead>
              <SortableTableHead sortKey="avgBuyIn" sort={sort} onSort={toggle} align="right">{t('holdings.avgBuyIn')}</SortableTableHead>
              <SortableTableHead sortKey="price" sort={sort} onSort={toggle} align="right">{t('holdings.assetPrice')}</SortableTableHead>
              <SortableTableHead sortKey="value" sort={sort} onSort={toggle} align="right">{t('portfolio.value')}</SortableTableHead>
              <SortableTableHead sortKey="pnl" sort={sort} onSort={toggle} align="right">{t('holdings.pnl')}</SortableTableHead>
              <SortableTableHead sortKey="pnlPercent" sort={sort} onSort={toggle} align="right">{t('holdings.pnlPercent')}</SortableTableHead>
              {(onEdit || onDelete) && <TableHead />}
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((h) => (
              <TableRow key={h.ticker}>
                <TableCell className="font-mono font-medium">{h.ticker}</TableCell>
                <TableCell>{h.name ?? h.ticker}</TableCell>
                <TableCell className="text-right">{money.quantity(h.quantity)}</TableCell>
                <TableCell className="text-right">
                  {h.averageBuyIn != null ? <CurrencyDisplay value={h.averageBuyIn} publicQuote className="text-sm" /> : '\u2014'}
                </TableCell>
                <TableCell className="text-right">
                  <div className="inline-flex items-center gap-1.5">
                    <PriceFreshnessDot
                      priceUpdatedAt={h.priceUpdatedAt}
                      staleAsOf={h.priceStale ? h.priceAsOf : null}
                    />
                    {h.currentPrice != null ? <CurrencyDisplay value={h.currentPrice} currency={h.quoteCurrency ?? undefined} publicQuote className="text-sm" /> : '\u2014'}
                  </div>
                </TableCell>
                <TableCell className="text-right font-medium">
                  {h.currentValueEur != null ? <CurrencyDisplay value={h.currentValueEur} className="text-sm" /> : '\u2014'}
                </TableCell>
                <TableCell className={cn('text-right', h.pnlEur != null && h.pnlEur >= 0 ? 'text-emerald-500' : h.pnlEur != null && h.pnlEur < 0 ? 'text-red-500' : '')}>
                  {h.pnlEur != null ? <CurrencyDisplay value={h.pnlEur} showSign className="text-sm" /> : '\u2014'}
                </TableCell>
                <TableCell className={cn('text-right', h.pnlPercent != null && h.pnlPercent >= 0 ? 'text-emerald-500' : h.pnlPercent != null && h.pnlPercent < 0 ? 'text-red-500' : '')}>
                  {h.pnlPercent != null ? `${h.pnlPercent >= 0 ? '+' : ''}${h.pnlPercent.toFixed(1)}%` : '\u2014'}
                </TableCell>
                {(onEdit || onDelete) && (
                  <TableCell className="text-right">
                    <div className="inline-flex items-center gap-1">
                      {onEdit && (
                        <Button
                          variant="ghost"
                          size="icon"
                          className="text-muted-foreground hover:text-foreground"
                          onClick={() => onEdit(h)}
                        >
                          <Pencil className="size-4" />
                        </Button>
                      )}
                      {onDelete && (
                        <Button
                          variant="ghost"
                          size="icon"
                          className="text-muted-foreground hover:text-destructive"
                          onClick={() => onDelete(h)}
                        >
                          <Trash2 className="size-4" />
                        </Button>
                      )}
                    </div>
                  </TableCell>
                )}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  )
}
