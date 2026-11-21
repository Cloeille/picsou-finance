import { useState, useRef, useEffect, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import {
  Check,
  CaretDown,
  Tray,
  ArrowsClockwise,
  MagnifyingGlass,
  GearSix,
  SlidersHorizontal,
  Sparkle,
  X,
} from '@phosphor-icons/react'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
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
import { useAdminSettings } from '@/features/admin/hooks'
import { useAuthStore } from '@/stores/auth-store'
import { selectIsAdvancedMode, useAppStore } from '@/stores/app-store'
import { cn, formatDate, getLocale } from '@/lib/utils'
import type { Category, RuleMatchType, UncategorizedTransaction } from '@/types/api'

type RulePayload = {
  pattern: string
  matchType: RuleMatchType
  applyToIds: number[]
}

const EMPTY_CATEGORY = '__EMPTY__'

/**
 * Turn raw bank/broker descriptions (which often carry UUIDs, full IBANs, or English Trade Republic
 * boilerplate) into a clean human-readable title while preserving the original string as detail.
 */
function formatTransactionDisplay(raw: string, lang: string): { title: string; detail: string | null } {
  const trimmed = raw.trim()
  if (!trimmed) return { title: raw, detail: null }

  // Strip embedded UUIDs (e.g. "01a194ae-aa5e-7a34-8fcb-d5d7b6c1a451") and parenthesized IBANs.
  let cleaned = trimmed
    .replace(/\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi, '')
    .replace(/\(\s*[a-z]{2}\d{2}[a-z0-9]{10,30}\s*\)/gi, '')
    .replace(/\s{2,}/g, ' ')
    .trim()

  if (lang.toLowerCase().startsWith('fr')) {
    const incoming = cleaned.match(/^Incoming\s+Transfer\s+From\s+(.+)$/i)
    const outgoing = cleaned.match(/^Outgoing\s+Transfer\s+(?:To|For)\s+(.+)$/i)
    const dividendIsin = cleaned.match(/^Cash\s+Dividend\s+For\s+Isin\s+([A-Z0-9]+)$/i)
    if (incoming) {
      cleaned = `Virement entrant de ${incoming[1].trim()}`
    } else if (outgoing) {
      cleaned = `Virement sortant vers ${outgoing[1].trim()}`
    } else if (/^Interest\s+Payment(\s+For\s+Payout\s+Collection)?$/i.test(cleaned)) {
      cleaned = "Versement d'intérêts"
    } else if (dividendIsin) {
      cleaned = `Dividende (${dividendIsin[1].toUpperCase()})`
    } else if (/^Dividend$/i.test(cleaned)) {
      cleaned = 'Dividende'
    } else if (/^Savings\s+Plan\s+Execution/i.test(cleaned)) {
      cleaned = cleaned.replace(/^Savings\s+Plan\s+Execution/i, "Plan d'épargne").trim()
    } else if (/^Card\s+Payment/i.test(cleaned)) {
      cleaned = cleaned.replace(/^Card\s+Payment/i, 'Paiement par carte').trim()
    } else if (/^Direct\s+Debit/i.test(cleaned)) {
      cleaned = cleaned.replace(/^Direct\s+Debit/i, 'Prélèvement').trim()
    }
  }

  const isPureUuid = cleaned === '' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(trimmed)
  const finalTitle = cleaned || (isPureUuid
    ? (lang.toLowerCase().startsWith('fr') ? 'Opération sans libellé' : 'Untitled transaction')
    : trimmed)
  const ibanMatch = trimmed.match(/\(([a-z]{2}\d{2}[a-z0-9]{10,30})\)/i)
  const detail = ibanMatch
    ? `IBAN …${ibanMatch[1].slice(-4).toUpperCase()}`
    : isPureUuid
      ? null
      : finalTitle !== trimmed
        ? trimmed
        : null

  return { title: finalTitle, detail }
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
  const { t, i18n } = useTranslation()
  const categorize = useCategorize()
  const qc = useQueryClient()
  const logoUrlFor = useMerchantLogoUrl()
  const isMobile = useIsMobile()

  const [categoryId, setCategoryId] = useState<string>('')
  const [rulePickerOpen, setRulePickerOpen] = useState(false)
  const [rulePayload, setRulePayload] = useState<RulePayload | null>(null)

  const rawLabel = tx.merchantLabel || tx.counterparty || tx.description
  const { title, detail } = formatTransactionDisplay(rawLabel, i18n?.resolvedLanguage ?? i18n?.language ?? 'fr')

  const activeCategories = useMemo(
    () => categories.filter((c) => !c.archived),
    [categories],
  )
  const incomeCategories = useMemo(
    () => activeCategories.filter((c) => c.kind === 'INCOME'),
    [activeCategories],
  )
  const expenseCategories = useMemo(
    () => activeCategories.filter((c) => c.kind !== 'INCOME'),
    [activeCategories],
  )

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
      label={rawLabel}
      onConfirm={handlePickerConfirm}
      onClose={() => setRulePickerOpen(false)}
    />
  )

  const renderCategoryItem = (c: Category) => (
    <SelectItem key={c.id} value={String(c.id)}>
      <span className="inline-flex items-center gap-2">
        {c.color && (
          <span
            className="size-2 shrink-0 rounded-full"
            style={{ backgroundColor: c.color }}
            aria-hidden="true"
          />
        )}
        <span>{c.name}</span>
      </span>
    </SelectItem>
  )

  return (
    <div className="flex flex-col gap-3 px-4 py-3.5 transition-colors hover:bg-muted/25 lg:flex-row lg:items-center lg:justify-between lg:gap-4">
      {/* Left: avatar + readable title + date/type/detail */}
      <div className="flex min-w-0 flex-1 items-start gap-3">
        <MerchantAvatar
          label={title}
          logoUrl={logoUrlFor(tx.merchantBrandId)}
        />
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-medium text-foreground" title={rawLabel}>
            {title}
          </p>
          <div className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-muted-foreground">
            <span className="tabular-nums">{formatDate(tx.date, getLocale())}</span>
            <span>{tx.amount >= 0 ? t('budget.categorize.incoming') : t('budget.categorize.outgoing')}</span>
            {detail && (
              <span className="max-w-xs truncate opacity-80" title={rawLabel}>
                {detail}
              </span>
            )}
            {rulePayload != null && (
              <Badge variant="outline" className="h-5 px-1.5 text-xs">
                {t('budget.rule.ruleActive', { pattern: rulePayload.pattern })}
                {rulePayload.applyToIds.length > 0 && ` (+${rulePayload.applyToIds.length})`}
              </Badge>
            )}
          </div>
        </div>
        {/* Mobile amount sits on top right */}
        <span
          className={cn(
            'shrink-0 text-sm font-semibold tabular-nums lg:hidden',
            tx.amount >= 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-foreground',
          )}
        >
          <CurrencyDisplay value={tx.amount} showSign />
        </span>
      </div>

      {/* Right: desktop amount + custom category dropdown + rule + validate */}
      <div className="flex flex-wrap items-center gap-2 lg:shrink-0">
        <span
          className={cn(
            'mr-2 hidden min-w-24 text-right text-sm font-semibold tabular-nums lg:inline-block',
            tx.amount >= 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-foreground',
          )}
        >
          <CurrencyDisplay value={tx.amount} showSign />
        </span>

        <div className="min-w-48 flex-1 sm:w-52 sm:flex-none">
          <Select
            value={categoryId === '' ? EMPTY_CATEGORY : categoryId}
            onValueChange={(next) => setCategoryId(next === EMPTY_CATEGORY ? '' : next)}
          >
            <SelectTrigger
              aria-label={t('budget.categorize.selectCategory')}
              data-value={categoryId}
              className="h-9"
            >
              <SelectValue placeholder={t('budget.categorize.selectCategory')} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={EMPTY_CATEGORY}>
                <span className="text-muted-foreground">{t('budget.categorize.selectCategory')}</span>
              </SelectItem>
              {incomeCategories.length > 0 && expenseCategories.length > 0 ? (
                <>
                  <SelectGroup>
                    <SelectLabel>{t('budget.categorize.incomeGroup')}</SelectLabel>
                    {incomeCategories.map(renderCategoryItem)}
                  </SelectGroup>
                  <SelectGroup>
                    <SelectLabel>{t('budget.categorize.expenseGroup')}</SelectLabel>
                    {expenseCategories.map(renderCategoryItem)}
                  </SelectGroup>
                </>
              ) : (
                activeCategories.map(renderCategoryItem)
              )}
            </SelectContent>
          </Select>
        </div>

        <Button
          type="button"
          size="sm"
          variant={rulePayload != null ? 'secondary' : 'outline'}
          onClick={() => setRulePickerOpen(true)}
          title={rulePayload != null ? t('budget.categorize.editRule') : t('budget.categorize.createRule')}
        >
          <SlidersHorizontal className="size-3.5" aria-hidden="true" />
          <span className="hidden sm:inline">
            {rulePayload != null ? t('budget.categorize.editRule') : t('budget.categorize.createRule')}
          </span>
        </Button>

        <Button
          type="button"
          size="sm"
          onClick={assign}
          disabled={categoryId === '' || categorize.isPending}
        >
          <Check className="size-3.5" aria-hidden="true" />
          {t('budget.categorize.assign')}
        </Button>
      </div>

      {/* Rule picker */}
      {isMobile ? (
        <Sheet open={rulePickerOpen} onOpenChange={setRulePickerOpen}>
          <SheetContent side="bottom" className="max-h-[90dvh] overflow-x-hidden overflow-y-auto px-4 pt-4 pb-6">
            <SheetHeader className="mb-4 p-0">
              <SheetTitle>{t('budget.categorize.createRule')}</SheetTitle>
            </SheetHeader>
            {pickerContent}
          </SheetContent>
        </Sheet>
      ) : (
        <Dialog open={rulePickerOpen} onOpenChange={setRulePickerOpen}>
          <DialogContent className="sm:max-w-xl" aria-describedby={undefined}>
            <DialogHeader>
              <DialogTitle>{t('budget.categorize.createRule')}</DialogTitle>
            </DialogHeader>
            {pickerContent}
          </DialogContent>
        </Dialog>
      )}
    </div>
  )
}

export function CategorizeTab() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const isAdmin = useAuthStore((s) => s.user?.role === 'ADMIN')
  const isAdvancedMode = useAppStore(selectIsAdvancedMode)
  const { data: txs, isLoading, isError, refetch } = useUncategorized()
  const { data: categories } = useCategories()
  const { data: settings } = useBudgetSettings()
  const { data: adminSettings } = useAdminSettings(isAdmin)
  const recategorize = useRecategorize()
  const aiEnabled = !!settings?.aiCategorizationEnabled
  const aiProvider = adminSettings?.ai?.provider
  const aiConfigured = !isAdmin || (aiProvider != null && aiProvider !== 'none')
  const canRunAi = aiEnabled && aiConfigured
  const aiStatus = useCategorizeAiStatus(canRunAi)
  const startAi = useStartCategorizeAi()
  const qc = useQueryClient()

  const [query, setQuery] = useState('')
  const prevRunningRef = useRef<boolean | undefined>(undefined)

  useEffect(() => {
    const running = aiStatus.data?.running
    if (prevRunningRef.current === true && running === false) {
      void qc.invalidateQueries({ queryKey: ['budget'] })
      void qc.invalidateQueries({ queryKey: ['dashboard'] })
    }
    prevRunningRef.current = running
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [aiStatus.data?.running])

  const filteredTxs = useMemo(() => {
    if (!txs) return []
    const q = query.trim().toLowerCase()
    if (!q) return txs
    const lang = i18n?.resolvedLanguage ?? i18n?.language ?? 'fr'
    return txs.filter((tx) => {
      const raw = tx.merchantLabel || tx.counterparty || tx.description
      const { title } = formatTransactionDisplay(raw, lang)
      return raw.toLowerCase().includes(q) || title.toLowerCase().includes(q)
    })
  }, [txs, query, i18n?.resolvedLanguage, i18n?.language])

  const showSearch = !isLoading && !isError && (txs?.length ?? 0) > 3

  return (
    <div className="space-y-4">
      {/* Header toolbar */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        {showSearch ? (
          <div className="relative w-full max-w-sm">
            <MagnifyingGlass className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={t('budget.categorize.searchPlaceholder')}
              className="pl-10 pr-9"
            />
            {query && (
              <button
                type="button"
                aria-label={t('setup.integrations.clearSearch')}
                onClick={() => setQuery('')}
                className="absolute right-2.5 top-1/2 -translate-y-1/2 rounded p-0.5 text-muted-foreground hover:text-foreground"
              >
                <X className="size-4" />
              </button>
            )}
          </div>
        ) : (
          <div />
        )}

        <div className="flex items-center gap-2">
          {aiStatus.data?.running ? (
            <Button size="sm" variant="outline" disabled>
              <Sparkle className="size-4 animate-pulse" aria-hidden="true" />
              {t('budget.categorize.aiProgress', {
                processed: aiStatus.data.processed,
                total: aiStatus.data.total,
              })}
            </Button>
          ) : (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button type="button" size="sm" variant="outline">
                  <Sparkle className="size-4 text-muted-foreground" aria-hidden="true" />
                  <span>{t('budget.categorize.automate')}</span>
                  <CaretDown className="size-3.5 text-muted-foreground" aria-hidden="true" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="min-w-64">
                {canRunAi && (
                  <DropdownMenuItem
                    disabled={startAi.isPending}
                    onSelect={() => startAi.mutate()}
                  >
                    <Sparkle className="size-4" aria-hidden="true" />
                    <span>{t('budget.categorize.categorizeAi')}</span>
                  </DropdownMenuItem>
                )}

                <DropdownMenuItem
                  disabled={recategorize.isPending}
                  onSelect={() => recategorize.mutate()}
                >
                  <ArrowsClockwise className={cn('size-4', recategorize.isPending && 'animate-spin')} aria-hidden="true" />
                  <span>{t('budget.categorize.recategorize')}</span>
                </DropdownMenuItem>

                {isAdmin && isAdvancedMode && (
                  <>
                    <DropdownMenuSeparator />
                    <DropdownMenuItem onSelect={() => navigate('/admin?tab=ai')}>
                      <GearSix className="size-4" aria-hidden="true" />
                      <span>{canRunAi ? t('budget.categorize.aiSettings') : t('budget.categorize.configureAi')}</span>
                    </DropdownMenuItem>
                  </>
                )}
              </DropdownMenuContent>
            </DropdownMenu>
          )}
        </div>
      </div>

      {aiStatus.data?.error && (
        <p role="alert" className="text-sm text-destructive">
          {aiStatus.data.error}
        </p>
      )}

      {isLoading && (
        <div className="space-y-3">
          <Skeleton className="h-20 w-full rounded-xl" />
          <Skeleton className="h-20 w-full rounded-xl" />
          <Skeleton className="h-20 w-full rounded-xl" />
        </div>
      )}

      {!isLoading && isError && (
        <ErrorState message={t('budget.categorize.error')} onRetry={() => void refetch()} />
      )}

      {!isLoading && !isError && (txs?.length ?? 0) === 0 && (
        <Card>
          <CardContent>
            <EmptyState
              className="py-8"
              icon={<Tray className="size-10" />}
              title={t('budget.categorize.empty')}
              description={t('budget.categorize.emptyHint')}
            />
          </CardContent>
        </Card>
      )}

      {!isLoading && !isError && (txs?.length ?? 0) > 0 && (
        filteredTxs.length > 0 ? (
          <Card className="overflow-hidden py-0">
            <div className="divide-y divide-border/60">
              {filteredTxs.map((tx) => (
                <InboxRow key={tx.id} tx={tx} categories={categories ?? []} />
              ))}
            </div>
          </Card>
        ) : (
          <Card>
            <CardContent>
              <EmptyState
                className="py-8"
                icon={<MagnifyingGlass className="size-8" />}
                title={t('common.noResults')}
              />
            </CardContent>
          </Card>
        )
      )}
    </div>
  )
}
