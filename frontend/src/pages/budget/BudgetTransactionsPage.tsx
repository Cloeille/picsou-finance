import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { Receipt } from 'lucide-react'
import { EmptyState } from '@/components/shared/EmptyState'
import { TransactionsList } from '@/components/shared/TransactionsList'
import { useMerchantLogoUrl } from '@/features/budget/hooks'
import { useTransactions } from '@/features/budget/hooks'
import { useCategories, useCategorize } from '@/features/budget/hooks'
import { useAccounts } from '@/features/accounts/hooks'
import { Skeleton } from '@/components/ui/skeleton'
import { ErrorState } from '@/components/shared/ErrorState'
import { DateInput } from '@/components/shared/DateInput'
import { NativeSelect } from '@/components/shared/NativeSelect'

function startOfMonth(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`
}

function endOfMonth(d: Date) {
  const last = new Date(d.getFullYear(), d.getMonth() + 1, 0)
  return `${last.getFullYear()}-${String(last.getMonth() + 1).padStart(2, '0')}-${String(last.getDate()).padStart(2, '0')}`
}

const now = new Date()

export function BudgetTransactionsPage() {
  const { t } = useTranslation()
  const qc = useQueryClient()

  const [from, setFrom] = useState(startOfMonth(now))
  const [to, setTo] = useState(endOfMonth(now))
  const [accountId, setAccountId] = useState<number | undefined>()
  const [categoryId, setCategoryId] = useState<number | undefined>()

  const { data: accounts } = useAccounts()
  const { data: categories } = useCategories()
  const { data: transactions, isLoading, isError, refetch } = useTransactions({ from, to, accountId, categoryId })
  const categorizeMutation = useCategorize()
  const logoUrlFor = useMerchantLogoUrl()

  function handleCategorize(txId: number, catId: number) {
    categorizeMutation.mutate(
      { id: txId, data: { categoryId: catId, createRule: false } },
      {
        onSuccess: () => {
          qc.invalidateQueries({ queryKey: ['budget'] })
          refetch()
        },
      },
    )
  }

  return (
    <div className="space-y-4">
      {/* Filters */}
      <div className="flex flex-wrap items-center gap-2">
        <div className="flex items-center gap-1.5">
          <label htmlFor="tx-filter-from" className="text-xs text-muted-foreground shrink-0">{t('transactions.filterFrom')}</label>
          <DateInput id="tx-filter-from" value={from} onChange={setFrom} className="w-36" />
        </div>
        <div className="flex items-center gap-1.5">
          <label htmlFor="tx-filter-to" className="text-xs text-muted-foreground shrink-0">{t('transactions.filterTo')}</label>
          <DateInput id="tx-filter-to" value={to} onChange={setTo} className="w-36" />
        </div>
        <NativeSelect
          aria-label={t('transactions.account')}
          value={accountId ?? ''}
          onChange={(e) => setAccountId(e.target.value ? Number(e.target.value) : undefined)}
          className="w-auto"
        >
          <option value="">{t('transactions.allAccounts')}</option>
          {(accounts ?? []).map(a => (
            <option key={a.id} value={a.id}>{a.name}</option>
          ))}
        </NativeSelect>
        <NativeSelect
          aria-label={t('common.category')}
          value={categoryId ?? ''}
          onChange={(e) => setCategoryId(e.target.value ? Number(e.target.value) : undefined)}
          className="w-auto"
        >
          <option value="">{t('transactions.allCategories')}</option>
          {(categories ?? []).filter(c => !c.archived).map(c => (
            <option key={c.id} value={c.id}>{c.name}</option>
          ))}
        </NativeSelect>
      </div>

      {isError && <ErrorState message={t('transactions.error')} onRetry={() => refetch()} />}

      {isLoading && !isError && (
        <Skeleton className="h-64 w-full rounded-xl" />
      )}

      {!isLoading && !isError && transactions && transactions.length === 0 && (
        <EmptyState icon={<Receipt className="size-10" />} title={t('transactions.empty')} />
      )}

      {!isLoading && !isError && transactions && transactions.length > 0 && (
        <TransactionsList
          transactions={transactions}
          logoUrlFor={logoUrlFor}
          categories={categories}
          onCategorize={handleCategorize}
        />
      )}
    </div>
  )
}
