import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Lightbulb } from 'lucide-react'
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
import { parseAmount } from '@/lib/utils'
import { cn } from '@/lib/utils'
import type { AllocationTargets, AllocationTargetsRequest, WealthTier } from '@/types/api'

/** The four tiers that carry a percentage target; the safety net is an absolute amount. */
const TARGET_TIERS = ['REAL_ESTATE', 'EQUITY', 'CRYPTO', 'ALTERNATIVE'] as const
type TargetTier = (typeof TARGET_TIERS)[number]

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
  onSubmit,
  onClose,
}: {
  targets: AllocationTargets
  saving: boolean
  onSubmit: (body: AllocationTargetsRequest) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const estimate = useEssentialExpenseEstimate(true)

  const [expenses, setExpenses] = useState(() =>
    // Strict equality here put the string "undefined" in the field for anyone who had
    // never stated their expenses — the API omits the null rather than sending it.
    targets.monthlyEssentialExpenses == null ? '' : String(targets.monthlyEssentialExpenses),
  )
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
            id="essential-expenses"
            value={expenses}
            placeholder={t('analysis.targets.expensesPlaceholder')}
            onChange={(e) => setExpenses(e.target.value)}
          />
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
                onClick={() => setExpenses(String(suggestion))}
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
              {t('analysis.targets.sum', { value: sum.toFixed(1) })}
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
          <p className="text-sm text-muted-foreground">{t('analysis.targets.allocationHint')}</p>
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

export function AllocationTargetsModal({
  open,
  onOpenChange,
  targets,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  targets: AllocationTargets | undefined
}) {
  const { t } = useTranslation()
  // Same reason as HoldingClassificationModal: saving invalidates ['analysis'], the refetch
  // changes the remount key, the form unmounts, and TanStack Query then drops the callbacks
  // passed to mutate(). Owning the mutation here keeps a listener alive to close the dialog.
  const save = useSaveAllocationTargets()

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{t('analysis.targets.title')}</DialogTitle>
          <DialogDescription>{t('analysis.targets.description')}</DialogDescription>
        </DialogHeader>
        {open && targets && (
          <TargetsForm
            key={JSON.stringify(targets)}
            targets={targets}
            saving={save.isPending}
            onSubmit={(body) => save.mutate(body, { onSuccess: () => onOpenChange(false) })}
            onClose={() => onOpenChange(false)}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}
