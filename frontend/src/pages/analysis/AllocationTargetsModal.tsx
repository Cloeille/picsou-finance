import { useMemo, useRef, useState } from 'react'
import type { Ref } from 'react'
import { useTranslation } from 'react-i18next'
import { Lightbulb } from '@phosphor-icons/react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { NumericInput } from '@/components/shared/NumericInput'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { useEssentialExpenseEstimate, useSaveAllocationTargets } from '@/features/analysis/hooks'
import { useRecurring } from '@/features/budget/hooks'
import { useMoney } from '@/hooks/use-money'
import { recurringSummary } from '@/pages/budget/recurring-summary'
import { formatPercent, parseAmount } from '@/lib/utils'
import { cn } from '@/lib/utils'
import type { AllocationTargets, AllocationTargetsRequest, WealthTier } from '@/types/api'

/** The four tiers that carry a percentage target; the safety net is an absolute amount. */
const TARGET_TIERS = ['REAL_ESTATE', 'EQUITY', 'CRYPTO', 'ALTERNATIVE'] as const
type TargetTier = (typeof TARGET_TIERS)[number]
const EMPTY_CALENDAR: never[] = []
const UNUSED_TODAY = new Date(0)

/** parseAmount returns NaN for an empty or half-typed field; every arithmetic here wants 0. */
function num(raw: string): number {
  const parsed = parseAmount(raw)
  return Number.isFinite(parsed) ? parsed : 0
}

/**
 * The form body, mounted only while the dialog is open and keyed on the loaded targets.
 *
 * Every field seeds from props through a lazy `useState` initializer rather than an
 * `useEffect(…, [open])`, which is what the React Compiler's `set-state-in-effect` rule requires
 * and what the rest of the codebase's modals already do.
 */
function TargetsForm({
  targets,
  saving,
  expensesInputRef,
  onSubmit,
  onClose,
}: {
  targets: AllocationTargets
  saving: boolean
  expensesInputRef?: Ref<HTMLInputElement>
  onSubmit: (body: AllocationTargetsRequest) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const money = useMoney()
  const estimate = useEssentialExpenseEstimate(true)
  const recurring = useRecurring()

  const hasSavedExpenses = targets.monthlyEssentialExpenses != null
  const recurringExpenses = useMemo(() => {
    if (!recurring.data) return null
    const rounded = Math.round(
      recurringSummary(recurring.data, EMPTY_CALENDAR, UNUSED_TODAY).monthlyExpenses,
    )
    return rounded > 0 ? rounded : null
  }, [recurring.data])

  // null until the user edits the field or applies a suggestion: lets an empty field pick up
  // recurring expenses as soon as the query resolves, without a setState-in-effect and without
  // overwriting anything the user already typed.
  const [expensesDraft, setExpensesDraft] = useState<string | null>(null)
  const prefilledFromRecurring =
    !hasSavedExpenses && expensesDraft === null && recurringExpenses !== null
  const expenses =
    expensesDraft !== null
      ? expensesDraft
      : hasSavedExpenses
        ? String(targets.monthlyEssentialExpenses)
        : recurringExpenses !== null
          ? String(recurringExpenses)
          : ''
  const [months, setMonths] = useState(() => String(targets.safetyNetMonths))
  const [pcts, setPcts] = useState<Record<TargetTier, string>>(() => ({
    REAL_ESTATE: String(targets.realEstatePct),
    EQUITY: String(targets.equityPct),
    CRYPTO: String(targets.cryptoPct),
    ALTERNATIVE: String(targets.alternativePct),
  }))

  const sum = useMemo(
    () => TARGET_TIERS.reduce((acc, tier) => acc + num(pcts[tier]), 0),
    [pcts],
  )
  const balanced = Math.abs(sum - 100) < 0.005

  // An empty field means "I do not know", which the backend stores as null and the score reads
  // as "unrated". A half-typed one is 0, not NaN — the Save button is what guards the rest.
  const parsedExpenses = expenses.trim() === '' ? null : num(expenses)
  const parsedMonths = num(months)
  const monthsValid = parsedMonths >= 1 && parsedMonths <= 24

  const suggestion = estimate.data?.estimate ?? null
  const showRecurringSuggestion =
    hasSavedExpenses && recurringExpenses !== null && parsedExpenses !== recurringExpenses

  function submit() {
    onSubmit({
      monthlyEssentialExpenses: parsedExpenses,
      safetyNetMonths: parsedMonths,
      realEstatePct: num(pcts.REAL_ESTATE),
      equityPct: num(pcts.EQUITY),
      cryptoPct: num(pcts.CRYPTO),
      alternativePct: num(pcts.ALTERNATIVE),
    })
  }

  return (
    <>
      <div className="space-y-6">
        <div className="space-y-2">
          <Label htmlFor="essential-expenses">{t('analysis.targets.expenses')}</Label>
          <NumericInput
            ref={expensesInputRef}
            id="essential-expenses"
            value={expenses}
            placeholder={t('analysis.targets.expensesPlaceholder')}
            onChange={(e) => setExpensesDraft(e.target.value)}
          />
          {prefilledFromRecurring && (
            <p className="text-sm text-muted-foreground">
              {t('analysis.targets.recurringPrefillHint')}
            </p>
          )}
          {showRecurringSuggestion && (
            <p className="flex flex-wrap items-center gap-1.5 text-sm text-muted-foreground">
              <span>
                {t('analysis.targets.recurringSuggestion')}{' '}
                <span className="font-medium text-foreground tabular-nums">
                  {money.amount(recurringExpenses, 'EUR', { maximumFractionDigits: 0 })}
                </span>
              </span>
              <span aria-hidden="true">·</span>
              <button
                type="button"
                className="cursor-pointer rounded-xs font-medium text-primary underline-offset-4 hover:underline"
                onClick={() => setExpensesDraft(String(recurringExpenses))}
              >
                {t('analysis.targets.useSuggestion')}
              </button>
            </p>
          )}
          {suggestion !== null && (
            <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
              <Lightbulb className="size-4 shrink-0" aria-hidden="true" />
              <span>
                {t('analysis.targets.suggestion')}{' '}
                <CurrencyDisplay value={suggestion} className="text-foreground" />{' '}
                {t('analysis.targets.suggestionBasis', {
                  count: estimate.data?.monthsObserved ?? 0,
                })}
              </span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setExpensesDraft(String(suggestion))}
              >
                {t('analysis.targets.useSuggestion')}
              </Button>
            </div>
          )}
          <p className="text-sm text-muted-foreground">{t('analysis.targets.expensesHint')}</p>
        </div>

        <div className="space-y-2">
          <Label htmlFor="safety-net-months">{t('analysis.targets.months')}</Label>
          <NumericInput
            id="safety-net-months"
            value={months}
            onChange={(e) => setMonths(e.target.value)}
          />
        </div>

        <div className="space-y-3">
          <div className="flex items-baseline justify-between gap-3">
            <Label>{t('analysis.targets.allocation')}</Label>
            <span
              className={cn(
                'text-sm',
                balanced ? 'text-muted-foreground' : 'text-destructive',
              )}
            >
              {t('analysis.targets.sum', {
                value: formatPercent(sum),
                max: formatPercent(100, { fractionDigits: 0 }),
              })}
            </span>
          </div>
          {TARGET_TIERS.map((tier) => (
            <div key={tier} className="flex items-center gap-3">
              <Label htmlFor={`pct-${tier}`} className="w-40 shrink-0 font-normal">
                {t(`analysis.tiers.${tier satisfies WealthTier}`)}
              </Label>
              <NumericInput
                id={`pct-${tier}`}
                value={pcts[tier]}
                onChange={(e) => setPcts({ ...pcts, [tier]: e.target.value })}
              />
            </div>
          ))}
          {!balanced && (
            <p role="alert" className="text-sm text-destructive">{t('analysis.targets.allocationHint')}</p>
          )}
        </div>
      </div>

      <DialogFooter>
        <Button variant="outline" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button onClick={submit} disabled={!balanced || !monthsValid || saving}>
          {t('common.save')}
        </Button>
      </DialogFooter>
    </>
  )
}

export type AllocationTargetsFocusField = 'expenses'

export function AllocationTargetsModal({
  open,
  onOpenChange,
  targets,
  initialFocus,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  targets: AllocationTargets | undefined
  initialFocus?: AllocationTargetsFocusField
}) {
  const { t } = useTranslation()
  // Same reason as HoldingClassificationModal: saving invalidates ['analysis'], the refetch
  // changes the remount key, the form unmounts, and TanStack Query then drops the callbacks
  // passed to mutate(). Owning the mutation here keeps a listener alive to close the dialog.
  const save = useSaveAllocationTargets()
  const expensesInputRef = useRef<HTMLInputElement>(null)

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className="sm:max-w-lg"
        onOpenAutoFocus={(event) => {
          if (initialFocus === 'expenses' && expensesInputRef.current) {
            event.preventDefault()
            expensesInputRef.current.focus()
          }
        }}
      >
        <DialogHeader>
          <DialogTitle>{t('analysis.targets.title')}</DialogTitle>
          <DialogDescription>{t('analysis.targets.description')}</DialogDescription>
        </DialogHeader>
        {open && targets && (
          <TargetsForm
            key={JSON.stringify(targets)}
            targets={targets}
            saving={save.isPending}
            expensesInputRef={expensesInputRef}
            onSubmit={(body) => save.mutate(body, { onSuccess: () => onOpenChange(false) })}
            onClose={() => onOpenChange(false)}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}
