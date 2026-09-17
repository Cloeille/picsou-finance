import { useTranslation } from 'react-i18next'
import { useMoney } from '@/hooks/use-money'
import type { ExchangePositionResponse } from '@/types/api'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { PriceFreshnessDot } from '@/components/shared/PriceFreshnessDot'
import { SortableTableHead } from '@/components/shared/SortableTableHead'
import { useTableSort, type SortColumns } from '@/hooks/use-table-sort'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { cn } from '@/lib/utils'
import {
  Table,
  TableBody,
  TableCell,
  TableHeader,
  TableRow,
} from '@/components/ui/table'

interface PositionsByProductProps {
  positions: ExchangePositionResponse[]
}

/** Same green/red convention as the flat holdings table. */
function pnlColor(value: number | null): string {
  if (value == null) return ''
  return value >= 0 ? 'text-emerald-500' : 'text-red-500'
}

/** Fixed order rather than the API's: spot first, then what is put to work. */
const PRODUCT_ORDER = ['SPOT', 'STAKING', 'LENDING'] as const

type PositionSortKey =
  | 'ticker' | 'principal' | 'interest' | 'quantity'
  | 'avgBuyIn' | 'price' | 'value' | 'pnl' | 'pnlPercent'

/** Module scope keeps the map identity-stable for the sort memo. */
const POSITION_COLUMNS: SortColumns<ExchangePositionResponse, PositionSortKey> = {
  ticker: { kind: 'text', value: p => p.ticker },
  principal: { kind: 'number', value: p => p.principal },
  interest: { kind: 'number', value: p => p.interest },
  quantity: { kind: 'number', value: p => p.quantity },
  avgBuyIn: { kind: 'number', value: p => p.averageBuyIn },
  price: { kind: 'number', value: p => p.currentPriceEur },
  value: { kind: 'number', value: p => p.currentValueEur },
  pnl: { kind: 'number', value: p => p.pnlEur },
  pnlPercent: { kind: 'number', value: p => p.pnlPercent },
}

/**
 * A crypto exchange account's positions, grouped by product.
 *
 * Replaces the flat holdings table for these accounts: the same asset can sit in spot *and* be
 * staked, and merging the two lines hides exactly the split the page exists to show. Yield-bearing
 * products get two extra columns — `interest` is a decomposition of the quantity held, never
 * something added to it.
 */
export function PositionsByProduct({ positions }: PositionsByProductProps) {
  const money = useMoney()
  const { t } = useTranslation()
  // One sort across the three groups: they are the same table split by product, so letting each
  // carry its own criterion would make one screen answer a different question per section.
  // Sorting the flat list before grouping orders every group at once and leaves the fixed
  // SPOT/STAKING/LENDING sequence below alone -- that order is editorial, not data.
  const { rows: sortedPositions, sort, toggle } = useTableSort(positions, POSITION_COLUMNS, {
    key: 'value',
    direction: 'desc',
  })

  if (positions.length === 0) return null

  const groups = PRODUCT_ORDER
    .map(product => ({ product, rows: sortedPositions.filter(p => p.product === product) }))
    .filter(group => group.rows.length > 0)

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{t('accounts.holdings')}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-8">
        {groups.map(({ product, rows }) => {
          // A subtotal is only honest when every line under it is valued. Coercing an unpriced
          // line to 0 published a partial total in the same typography as a complete one: the
          // reader has no way to tell EUR 0 for "worth nothing" from EUR 0 for "we don't know",
          // and a product whose largest asset failed to price reads as a loss it never took.
          // Same rule as the value column below, which shows a dash rather than a zero.
          const subtotal = rows.some(row => row.currentValueEur == null)
            ? null
            : rows.reduce((sum, row) => sum + row.currentValueEur!, 0)
          const showYield = rows.some(row => row.interest != null)

          return (
            <div key={product} className="space-y-2">
              <div className="flex items-baseline justify-between gap-4">
                <h3 className="text-sm font-medium">{t(`positions.products.${product}`)}</h3>
                {subtotal != null
                  ? <CurrencyDisplay value={subtotal} className="text-sm font-medium" />
                  : <span className="text-sm font-medium">—</span>}
              </div>

              <div className="overflow-x-auto">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <SortableTableHead sortKey="ticker" sort={sort} onSort={toggle}>{t('holdings.ticker')}</SortableTableHead>
                      {showYield && <SortableTableHead sortKey="principal" sort={sort} onSort={toggle} align="right">{t('positions.principal')}</SortableTableHead>}
                      {showYield && <SortableTableHead sortKey="interest" sort={sort} onSort={toggle} align="right">{t('positions.interest')}</SortableTableHead>}
                      <SortableTableHead sortKey="quantity" sort={sort} onSort={toggle} align="right">
                        {showYield ? t('positions.total') : t('holdings.quantity')}
                      </SortableTableHead>
                      <SortableTableHead sortKey="avgBuyIn" sort={sort} onSort={toggle} align="right">{t('holdings.avgBuyIn')}</SortableTableHead>
                      <SortableTableHead sortKey="price" sort={sort} onSort={toggle} align="right">{t('holdings.assetPrice')}</SortableTableHead>
                      <SortableTableHead sortKey="value" sort={sort} onSort={toggle} align="right">{t('portfolio.value')}</SortableTableHead>
                      <SortableTableHead sortKey="pnl" sort={sort} onSort={toggle} align="right">{t('holdings.pnl')}</SortableTableHead>
                      <SortableTableHead sortKey="pnlPercent" sort={sort} onSort={toggle} align="right">{t('holdings.pnlPercent')}</SortableTableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {rows.map(row => (
                      <TableRow key={`${row.product}-${row.ticker}`}>
                        <TableCell className="font-mono font-medium">{row.ticker}</TableCell>
                        {showYield && (
                          <TableCell className="text-right tabular-nums">
                            {row.principal != null ? money.quantity(row.principal) : '—'}
                          </TableCell>
                        )}
                        {showYield && (
                          <TableCell className="text-right tabular-nums text-emerald-500">
                            {row.interest != null ? money.quantity(row.interest) : '—'}
                          </TableCell>
                        )}
                        <TableCell className="text-right tabular-nums">{money.quantity(row.quantity)}</TableCell>
                        <TableCell className="text-right">
                          {row.averageBuyIn != null
                            ? <CurrencyDisplay value={row.averageBuyIn} publicQuote className="text-sm" />
                            : '—'}
                        </TableCell>
                        <TableCell className="text-right">
                          {row.currentPriceEur != null
                            ? (
                              <div className="inline-flex items-center gap-1.5">
                                <PriceFreshnessDot
                                  priceUpdatedAt={null}
                                  staleAsOf={row.priceStale ? row.priceAsOf : null}
                                />
                                <CurrencyDisplay value={row.currentPriceEur} className="text-sm" />
                              </div>
                            )
                            : '—'}
                        </TableCell>
                        <TableCell className="text-right font-medium">
                          {row.currentValueEur != null
                            ? <CurrencyDisplay value={row.currentValueEur} className="text-sm" />
                            : '—'}
                        </TableCell>
                        <TableCell className={cn('text-right', pnlColor(row.pnlEur))}>
                          {row.pnlEur != null
                            ? <CurrencyDisplay value={row.pnlEur} showSign className="text-sm" />
                            : '—'}
                        </TableCell>
                        <TableCell className={cn('text-right', pnlColor(row.pnlPercent))}>
                          {row.pnlPercent != null
                            ? `${row.pnlPercent >= 0 ? '+' : ''}${row.pnlPercent.toFixed(1)}%`
                            : '—'}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
            </div>
          )
        })}
      </CardContent>
    </Card>
  )
}
