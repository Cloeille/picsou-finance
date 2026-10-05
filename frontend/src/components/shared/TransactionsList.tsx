import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import type { Category, Transaction } from '@/types/api'
import { TransactionRow } from '@/components/shared/TransactionRow'
import { TransactionDetailSheet } from '@/components/shared/TransactionDetailSheet'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Separator } from '@/components/ui/separator'
import { localeFromLanguage } from '@/lib/utils'
import { transactionDescription } from '@/lib/transactions'

interface TransactionsListProps {
  transactions: Transaction[]
  onDelete?: (txId: number) => void
  onEdit?: (tx: Transaction) => void
  logoUrlFor?: (brandId: number | null | undefined) => string | null
  categories?: Category[]
  onCategorize?: (txId: number, categoryId: number) => void
  /** Buttons rendered in the card header (add, import). The card stays visible when empty if set. */
  actions?: ReactNode
}

export function TransactionsList({
  transactions,
  onDelete,
  onEdit,
  logoUrlFor,
  categories,
  onCategorize,
  actions,
}: TransactionsListProps) {
  const { t, i18n } = useTranslation()
  const [search, setSearch] = useState('')
  const [selectedTx, setSelectedTx] = useState<Transaction | null>(null)
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)

  const filtered = search
    ? transactions.filter(tr => {
        const normalizedSearch = search.toLocaleLowerCase(locale)
        const displayedDescription = transactionDescription(tr, t).toLocaleLowerCase(locale)
        return (
          displayedDescription.includes(normalizedSearch) ||
          tr.description.toLocaleLowerCase(locale).includes(normalizedSearch) ||
          (tr.merchantLabel ?? '').toLocaleLowerCase(locale).includes(normalizedSearch)
        )
      })
    : transactions
  const showYear = new Set(filtered.map(tr => tr.date.slice(0, 4))).size > 1

  const grouped = filtered.reduce<Record<string, Transaction[]>>((acc, tr) => {
    if (!acc[tr.date]) acc[tr.date] = []
    acc[tr.date].push(tr)
    return acc
  }, {})

  const sortedDates = Object.keys(grouped).sort((a, b) => b.localeCompare(a))

  if (transactions.length === 0 && !actions) return null

  return (
    <>
      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2 space-y-0">
          <CardTitle className="text-base">{t('accounts.transactions')}</CardTitle>
          {actions && <div className="flex items-center gap-2">{actions}</div>}
        </CardHeader>
        <CardContent className="space-y-0">
          {transactions.length > 0 && <Input
            placeholder={t('common.search')}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="mb-4"
          />}
          {sortedDates.map((date, dateIdx) => (
            <div key={date}>
              {dateIdx > 0 && <Separator className="my-3" />}
              <p className="mb-2 text-xs font-medium uppercase tracking-wider text-muted-foreground">
                {formatTransactionDate(date, locale, showYear)}
              </p>
              <div className="space-y-0.5">
                {grouped[date].map((tr, rowIdx) => (
                  <TransactionRow
                    key={tr.id}
                    transaction={tr}
                    logoUrlFor={logoUrlFor}
                    index={rowIdx}
                    onClick={setSelectedTx}
                  />
                ))}
              </div>
            </div>
          ))}
        </CardContent>
      </Card>

      <TransactionDetailSheet
        transaction={selectedTx}
        open={selectedTx != null}
        onClose={() => setSelectedTx(null)}
        logoUrlFor={logoUrlFor}
        categories={categories}
        onCategorize={onCategorize}
        onEdit={onEdit}
        onDelete={onDelete}
      />
    </>
  )
}

function formatTransactionDate(date: string, locale: string, showYear: boolean): string {
  const transactionDate = new Date(`${date}T00:00:00`)
  const label = new Intl.DateTimeFormat(locale, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    ...(showYear ? { year: 'numeric' } : {}),
  }).format(transactionDate)
  return label.charAt(0).toLocaleUpperCase(locale) + label.slice(1)
}
