import { useState, useRef, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { Inbox, Sparkles } from 'lucide-react'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { EmptyState } from '@/components/shared/EmptyState'
import { ErrorState } from '@/components/shared/ErrorState'
import { MerchantAvatar } from '@/components/shared/MerchantAvatar'
import { Skeleton } from '@/components/ui/skeleton'
import { RuleWordPicker } from '@/components/budget/RuleWordPicker'
import {
  useBudgetSettings,
  useCategories,
  useCategorize,
  useCategorizeAiStatus,
  useMerchantLogoUrl,
  useRecategorize,
  useStartCategorizeAi,
  useUncategorized,
} from '@/features/budget/hooks'
import { formatDate, getLocale } from '@/lib/utils'
import type { Category, RuleMatchType, UncategorizedTransaction } from '@/types/api'

type RulePayload = {
  pattern: string
  matchType: RuleMatchType
  applyToIds: number[]
}

function useIsMobile() {
  const [isMobile, setIsMobile] = useState(() =>
    typeof window !== 'undefined' ? window.innerWidth < 768 : false
  )
  useEffect(() => {
    function onResize() {
      setIsMobile(window.innerWidth < 768)
    }
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])
  return isMobile
}

function InboxRow({ tx, categories }: {
  tx: UncategorizedTransaction
  categories: Category[]
}) {
  const { t } = useTranslation()
  const categorize = useCategorize()
  const qc = useQueryClient()
  const logoUrlFor = useMerchantLogoUrl()
  const isMobile = useIsMobile()

  // Preselect the AI suggestion (if any) so accepting it is a single click on "Assign".
  const [categoryId, setCategoryId] = useState<number | ''>(tx.aiSuggestedCategoryId ?? '')
  const [rulePickerOpen, setRulePickerOpen] = useState(false)
  const [rulePayload, setRulePayload] = useState<RulePayload | null>(null)

  const txLabel = tx.merchantLabel || tx.counterparty || tx.description

  const suggested =
    tx.aiSuggestedCategoryId != null
      ? categories.find((c) => c.id === tx.aiSuggestedCategoryId) ?? null
      : null

  function assign() {
    if (categoryId === '') return
    categorize.mutate(
      {
        id: tx.id,
        data: {
          categoryId: Number(categoryId),
          createRule: rulePayload != null,
          rulePattern: rulePayload?.pattern,
          ruleMatchType: rulePayload?.matchType,
          applyToTransactionIds: rulePayload?.applyToIds,
        },
      },
      {
        onSuccess: () => {
          setRulePickerOpen(false)
          setRulePayload(null)
          void qc.invalidateQueries({ queryKey: ['budget', 'rules'] })
        },
      }
    )
  }

  function handlePickerConfirm(payload: RulePayload) {
    setRulePayload(payload)
    setRulePickerOpen(false)
  }

  const pickerContent = (
    <RuleWordPicker
      label={txLabel}
      onConfirm={handlePickerConfirm}
      onClose={() => setRulePickerOpen(false)}
    />
  )

  return (
    <Card>
      <CardContent className="py-4">
        <div className="flex items-start justify-between gap-3">
          <div className="flex min-w-0 items-center gap-3">
            <MerchantAvatar
              label={txLabel}
              logoUrl={logoUrlFor(tx.merchantBrandId)}
            />
            <div className="min-w-0">
              <p className="truncate font-medium">{txLabel}</p>
              <p className="text-xs text-muted-foreground">{formatDate(tx.date, getLocale())}</p>
            </div>
          </div>
          <span className={`shrink-0 font-semibold tabular-nums ${
            tx.amount >= 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-foreground'}`}>
            <CurrencyDisplay value={tx.amount} showSign />
          </span>
        </div>

        {suggested && (
          <div className="mt-2">
            <Badge variant="outline" className="gap-1 border-primary/30 bg-primary/10 px-2 py-0.5 text-[0.8rem] font-medium text-primary">
              <Sparkles className="size-3.5 shrink-0" />
              {t('budget.categorize.aiSuggested', {
                name: suggested.name,
                confidence: tx.aiConfidence ?? 0,
              })}
            </Badge>
          </div>
        )}

        <div className="mt-3 flex flex-col gap-3 sm:flex-row sm:items-center">
          <select
            value={categoryId}
            onChange={(e) => setCategoryId(e.target.value === '' ? '' : Number(e.target.value))}
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus:border-ring sm:max-w-56"
          >
            <option value="">{t('budget.categorize.selectCategory')}</option>
            {categories.filter((c) => !c.archived).map((c) => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>

          <div className="flex flex-wrap items-center gap-2">
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={() => setRulePickerOpen(true)}
            >
              {rulePayload != null
                ? t('budget.categorize.editRule')
                : t('budget.categorize.createRule')}
            </Button>
            {rulePayload != null && (
              <span className="text-xs text-muted-foreground">
                {t('budget.rule.ruleActive', { pattern: rulePayload.pattern })}
                {rulePayload.applyToIds.length > 0 && (
                  <> · {rulePayload.applyToIds.length}</>
                )}
              </span>
            )}
          </div>

          <Button
            size="sm"
            className="sm:ml-auto"
            onClick={assign}
            disabled={categoryId === '' || categorize.isPending}
          >
            {t('budget.categorize.assign')}
          </Button>
        </div>

        {/* Rule picker — Sheet (bottom) on mobile, Dialog on desktop */}
        {isMobile ? (
          <Sheet open={rulePickerOpen} onOpenChange={setRulePickerOpen}>
            <SheetContent side="bottom" className="px-4 pb-6 pt-4 max-h-[90dvh] overflow-y-auto">
              <SheetHeader className="mb-4 p-0">
                <SheetTitle>{t('budget.categorize.createRule')}</SheetTitle>
              </SheetHeader>
              {pickerContent}
            </SheetContent>
          </Sheet>
        ) : (
          <Dialog open={rulePickerOpen} onOpenChange={setRulePickerOpen}>
            <DialogContent className="sm:max-w-lg">
              <DialogHeader>
                <DialogTitle>{t('budget.categorize.createRule')}</DialogTitle>
              </DialogHeader>
              {pickerContent}
            </DialogContent>
          </Dialog>
        )}
      </CardContent>
    </Card>
  )
}

export function CategorizeTab() {
  const { t } = useTranslation()
  const { data: txs, isLoading, isError, refetch } = useUncategorized()
  const { data: categories } = useCategories()
  const { data: settings } = useBudgetSettings()
  const recategorize = useRecategorize()
  const aiEnabled = !!settings?.aiCategorizationEnabled
  const aiStatus = useCategorizeAiStatus(aiEnabled)
  const startAi = useStartCategorizeAi()
  const qc = useQueryClient()

  // Track the previous running value so we can detect the running → done transition.
  const prevRunningRef = useRef<boolean | undefined>(undefined)
  const [doneInfo, setDoneInfo] = useState<{ applied: number; suggested: number } | null>(null)

  useEffect(() => {
    const running = aiStatus.data?.running
    if (prevRunningRef.current === true && running === false) {
      void qc.invalidateQueries({ queryKey: ['budget'] })
      void qc.invalidateQueries({ queryKey: ['dashboard'] })
      if (aiStatus.data) {
        // Reacting to the running → done transition detected above, not deriving render state.
        // eslint-disable-next-line react-hooks/set-state-in-effect
        setDoneInfo({ applied: aiStatus.data.applied, suggested: aiStatus.data.suggested })
      }
    }
    prevRunningRef.current = running
  // qc is stable (QueryClient never changes); aiStatus.data is captured via the running dep.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [aiStatus.data?.running])

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground">{t('budget.categorize.subtitle')}</p>
        <div className="flex flex-wrap items-center gap-2">
          {aiEnabled && (
            <Button
              size="sm"
              onClick={() => { setDoneInfo(null); startAi.mutate() }}
              disabled={aiStatus.data?.running || startAi.isPending}
            >
              <Sparkles className="size-4" />
              {aiStatus.data?.running
                ? t('budget.categorize.aiProgress', {
                    processed: aiStatus.data.processed,
                    total: aiStatus.data.total,
                  })
                : t('budget.categorize.categorizeAi')}
            </Button>
          )}
          {doneInfo && (
            <span className="text-xs text-muted-foreground">
              {t('budget.categorize.aiDone', {
                applied: doneInfo.applied,
                suggested: doneInfo.suggested,
              })}
            </span>
          )}
          <Button size="sm" variant="outline" onClick={() => recategorize.mutate()}
            disabled={recategorize.isPending}>
            <Sparkles className="size-4" /> {t('budget.categorize.recategorize')}
          </Button>
        </div>
      </div>

      {isLoading && (
        <div className="space-y-3">
          <Skeleton className="h-28 w-full rounded-xl" />
          <Skeleton className="h-28 w-full rounded-xl" />
        </div>
      )}

      {!isLoading && isError && (
        <ErrorState message={t('budget.categorize.error')} onRetry={() => void refetch()} />
      )}

      {!isLoading && !isError && (txs?.length ?? 0) === 0 && (
        <EmptyState icon={<Inbox className="size-10" />}
          title={t('budget.categorize.empty')}
          description={t('budget.categorize.emptyHint')} />
      )}

      {!isLoading && !isError && (txs?.length ?? 0) > 0 && (
        <div className="space-y-3">
          {txs!.map((tx) => (
            <InboxRow key={tx.id} tx={tx} categories={categories ?? []} />
          ))}
        </div>
      )}
    </div>
  )
}
