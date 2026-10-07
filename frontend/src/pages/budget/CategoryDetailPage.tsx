import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ChevronRight } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { ErrorState } from '@/components/shared/ErrorState'
import { TransactionsList } from '@/components/shared/TransactionsList'
import { useCategoryDetail, useCategories, useCategorize, useMerchantLogoUrl } from '@/features/budget/hooks'
import type { CashflowPeriod, ChildSpend } from '@/types/api'
import { FALLBACK_COLOR } from './budget-meta'
import { PeriodToggle } from './budget-utils'
import { PeriodNavigator } from './PeriodNavigator'
import { useBudgetPeriod } from './BudgetPeriodContext'

/**
 * `/budget/spending/:categoryId` — one category's transactions over the period. Keyed by
 * id (not slug) because user-created categories have no slug. When the category is a parent,
 * `total`/`count`/`transactions` span its whole subtree and a per-sub-category rollup is shown
 * above the (subtree-wide) transaction list; each sub-category drills one level deeper.
 */

/** A tappable per-sub-category rollup row shown when drilling a parent category. */
function SubcategoryRow({ child }: { child: ChildSpend }) {
  const { t } = useTranslation()
  return (
    <Link
      to={`/budget/spending/${child.categoryId}`}
      className="flex items-center gap-3 rounded-xl px-3 py-2.5 transition-colors hover:bg-muted/60"
    >
      <span
        className="inline-block size-2.5 shrink-0 rounded-full"
        style={{ backgroundColor: child.color || FALLBACK_COLOR }}
      />
      <div className="flex min-w-0 flex-1 items-center justify-between gap-3">
        <span className="flex min-w-0 items-center gap-2">
          <span className="truncate text-sm font-medium">{child.name}</span>
          <span className="shrink-0 text-xs text-muted-foreground">
            {t('budget.flow.transactionsCount', { count: child.count })}
          </span>
        </span>
        <CurrencyDisplay value={child.total} className="shrink-0 text-sm font-semibold tabular-nums" />
      </div>
      <ChevronRight className="size-4 shrink-0 text-muted-foreground" />
    </Link>
  )
}
export function CategoryDetailPage() {
  const { t } = useTranslation()
  const { categoryId } = useParams()
  const [period, setPeriod] = useState<CashflowPeriod>('CYCLE')
  const { anchor, setAnchor } = useBudgetPeriod()
  const id = Number(categoryId)
  const { data, isLoading, isError, refetch } = useCategoryDetail(id, period, anchor)
  const logoUrlFor = useMerchantLogoUrl()
  const { data: categories } = useCategories()
  const categorizeMutation = useCategorize()
  const qc = useQueryClient()

  function handleCategorize(txId: number, categoryId: number) {
    categorizeMutation.mutate(
      { id: txId, data: { categoryId, createRule: false } },
      { onSuccess: () => qc.invalidateQueries({ queryKey: ['budget'] }) },
    )
  }

  const backLink = (
    <Link
      to="/budget/spending"
      className="inline-flex items-center gap-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
    >
      <ArrowLeft className="size-4" />
      {t('budget.detail.back')}
    </Link>
  )

  if (!Number.isFinite(id)) {
    return (
      <div className="space-y-4">
        {backLink}
        <ErrorState message={t('budget.detail.notFound')} />
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        {backLink}
        <div className="flex flex-wrap items-center gap-2">
          <PeriodNavigator
            period={period}
            from={data?.from}
            to={data?.to}
            onAnchorChange={setAnchor}
          />
          <PeriodToggle value={period} onChange={(p) => { setPeriod(p); setAnchor(undefined) }} />
        </div>
      </div>

      {isError && (
        <ErrorState message={t('budget.detail.error')} onRetry={() => refetch()} />
      )}

      {isLoading && !isError && (
        <>
          <Skeleton className="h-20 w-full rounded-xl" />
          <Skeleton className="h-64 w-full rounded-xl" />
        </>
      )}

      {!isLoading && !isError && data && (
        <>
          <Card>
            <CardContent className="flex items-center justify-between gap-4 pt-6">
              <div className="flex min-w-0 items-center gap-3">
                <span
                  className="inline-block size-3 shrink-0 rounded-full"
                  style={{ backgroundColor: data.color || FALLBACK_COLOR }}
                />
                <div className="min-w-0">
                  <p className="truncate text-lg font-semibold">{data.name}</p>
                  <p className="text-sm text-muted-foreground">
                    {t('budget.flow.transactionsCount', { count: data.count })}
                  </p>
                </div>
              </div>
              <div className="text-right">
                <p className="text-xs text-muted-foreground">{t('budget.detail.total')}</p>
                <CurrencyDisplay
                  value={data.total}
                  className="text-xl font-bold tabular-nums text-foreground"
                />
              </div>
            </CardContent>
          </Card>

          {data.children.length > 0 && (
            <Card>
              <CardHeader>
                <CardTitle className="text-base">{t('budget.detail.subcategories')}</CardTitle>
              </CardHeader>
              <CardContent className="space-y-0.5">
                {data.children.map((child) => (
                  <SubcategoryRow key={child.categoryId} child={child} />
                ))}
              </CardContent>
            </Card>
          )}

          {data.count === 0 ? (
            <p className="py-8 text-center text-sm text-muted-foreground">
              {t('budget.detail.empty')}
            </p>
          ) : (
            <TransactionsList
              transactions={data.transactions}
              logoUrlFor={logoUrlFor}
              categories={categories}
              onCategorize={handleCategorize}
            />
          )}
        </>
      )}
    </div>
  )
}
