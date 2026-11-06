import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Checkbox } from '@/components/ui/checkbox'
import { NumericInput } from '@/components/shared/NumericInput'
import { DateInput } from '@/components/shared/DateInput'
import { SELECT_CONTROL_CLASS } from '@/lib/constants'
import { parseAmount } from '@/lib/utils'
import { extractErrorMessage } from '@/lib/errors'
import { useMoney } from '@/hooks/use-money'
import { usePeerLoanSimulation } from '@/features/peer-loans/hooks'
import type {
  Account,
  PeerLoanDetail,
  PeerLoanRatePeriod,
  PeerLoanSimulationResponse,
  PeerLoanTermsRequest,
} from '@/types/api'

interface PeerLoanTermsDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  initial?: PeerLoanDetail | null
  /** Cash accounts offered as the optional "linked account" (bank movement reconciliation). */
  cashAccounts: Account[]
  onSubmit: (data: PeerLoanTermsRequest) => Promise<void> | void
  loading?: boolean
}

function text(value: number | null | undefined): string {
  return value == null ? '' : String(value)
}

/**
 * The loan's conditions plus the forecast calculator. Both directions of the annuity
 * are available: a duration fills the monthly amount, a monthly amount fills the
 * duration. The result is a forecast only — it never creates a repayment.
 */
export function PeerLoanTermsDialog({
  open,
  onOpenChange,
  initial,
  cashAccounts,
  onSubmit,
  loading,
}: PeerLoanTermsDialogProps) {
  const { t } = useTranslation()
  const money = useMoney()
  const simulate = usePeerLoanSimulation()

  const [principal, setPrincipal] = useState('')
  const [lentAt, setLentAt] = useState('')
  const [expectedBack, setExpectedBack] = useState('')
  const [ratePct, setRatePct] = useState('')
  const [ratePeriod, setRatePeriod] = useState<PeerLoanRatePeriod>('ANNUAL')
  const [months, setMonths] = useState('')
  const [monthly, setMonthly] = useState('')
  const [linkedAccountId, setLinkedAccountId] = useState('')
  const [note, setNote] = useState('')
  const [alreadyBooked, setAlreadyBooked] = useState(false)
  const [simulation, setSimulation] = useState<PeerLoanSimulationResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  // Both plan fields usually hold a value (the calculator fills the other one), so the
  // field the member typed in last is what decides which direction the next run takes.
  const [lastEdited, setLastEdited] = useState<'months' | 'monthly' | null>(null)
  const [wasOpen, setWasOpen] = useState(open)

  // Seed the fields each time the dialog opens, during render (the pattern ConfirmDialog
  // already uses). An effect would set state synchronously and cascade a second render.
  if (open !== wasOpen) {
    setWasOpen(open)
    if (open) {
      setPrincipal(text(initial?.principal))
      setLentAt(initial?.lentAt ?? new Date().toISOString().slice(0, 10))
      setExpectedBack(initial?.expectedBack ?? '')
      const period = initial?.ratePeriod ?? 'ANNUAL'
      setRatePeriod(period)
      setRatePct(initial?.annualRate != null
        ? String(period === 'MONTHLY' ? initial.annualRate * 100 / 12 : initial.annualRate * 100)
        : '')
      setMonths(text(initial?.plannedMonths))
      setMonthly(text(initial?.plannedMonthlyAmount))
      setLinkedAccountId(text(initial?.linkedAccountId))
      setNote(initial?.note ?? '')
      setAlreadyBooked(false)
      setSimulation(null)
      setLastEdited(null)
      setError(null)
    }
  }

  const numberOrNull = (value: string): number | null => {
    const trimmed = value.trim()
    if (trimmed === '') return null
    const parsed = parseAmount(trimmed)
    return Number.isFinite(parsed) ? parsed : null
  }

  const selectedCashAccount = cashAccounts.find(
    account => account.id === Number(linkedAccountId),
  )

  /** The normalized annual fraction, whatever period the field is displayed in. */
  const annualRateFromInput = (): number => {
    const pct = numberOrNull(ratePct) ?? 0
    // Round here too so the request reads cleanly, but the backend rounds again: a client
    // can always send a double, and the column stores six decimals.
    return Number((((ratePeriod === 'MONTHLY' ? pct * 12 : pct) / 100)).toFixed(6))
  }

  /** The backend only rejects a negative rate; short-term loans may carry high ones. */
  function validateRate(annualRate: number): boolean {    if (annualRate < 0) {
      setError(t('peerLoan.validation.rateNegative'))
      return false
    }
    return true
  }

  /** Converts the visible value when the member switches period, so 6 %/yr reads 0.5 %/mo. */
  function handlePeriodChange(next: PeerLoanRatePeriod) {
    const value = numberOrNull(ratePct)
    if (value != null) {
      const converted = next === 'MONTHLY' ? value / 12 : value * 12
      setRatePct(String(Number(converted.toFixed(6))))
    }
    setRatePeriod(next)
  }

  async function handleSimulate() {
    setError(null)
    const principalValue = numberOrNull(principal)
    if (principalValue == null || principalValue <= 0) {
      setError(t('peerLoan.validation.principal'))
      return
    }
    const annualRate = annualRateFromInput()
    if (!validateRate(annualRate)) {
      return
    }
    const monthCount = numberOrNull(months)
    const monthlyValue = numberOrNull(monthly)
    const hasMonths = monthCount != null && monthCount > 0
    const hasMonthly = monthlyValue != null && monthlyValue > 0
    // When the payment is the last field the member touched, it wins even though the
    // duration still carries the value filled by the previous run.
    const usePayment = lastEdited === 'monthly'
      ? hasMonthly
      : !hasMonths && hasMonthly
    try {
      if (usePayment && monthlyValue != null) {
        const result = await simulate.mutateAsync({
          principal: principalValue, annualRate, monthlyPayment: monthlyValue,
        })
        setMonths(String(result.months))
        setSimulation(result)
      } else if (hasMonths && monthCount != null) {
        const result = await simulate.mutateAsync({ principal: principalValue, annualRate, months: monthCount })
        setMonthly(String(result.monthlyPayment))
        setSimulation(result)
      } else {
        setError(t('peerLoan.validation.plan'))
      }
    } catch (err) {
      setError(extractErrorMessage(err, t('peerLoan.validation.simulation')))
    }
  }

  async function handleSubmit() {
    setError(null)
    const principalValue = numberOrNull(principal)
    if (principalValue == null || principalValue <= 0) {
      setError(t('peerLoan.validation.principal'))
      return
    }
    if (!lentAt) {
      setError(t('peerLoan.validation.lentAt'))
      return
    }
    const linkedValue = numberOrNull(linkedAccountId)
    if (linkedValue == null) {
      setError(t('peerLoan.validation.linkedAccount'))
      return
    }
    const monthCount = numberOrNull(months)
    const monthlyValue = numberOrNull(monthly)
    if ((monthCount == null) !== (monthlyValue == null)) {
      setError(t('peerLoan.validation.plan'))
      return
    }
    const annualRate = annualRateFromInput()
    if (!validateRate(annualRate)) {
      return
    }
    try {
      await onSubmit({
        principal: principalValue,
        lentAt,
        expectedBack: expectedBack || null,
        annualRate,
        ratePeriod,
        plannedMonthlyAmount: monthlyValue,
        plannedMonths: monthCount,
        linkedAccountId: linkedValue,
        note: note.trim() || null,
        withCashLeg: !alreadyBooked,
      })
      onOpenChange(false)
    } catch {
      // The parent shows the API error; the dialog stays open for a retry.
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{initial?.configured ? t('peerLoan.editTerms') : t('peerLoan.configure')}</DialogTitle>
          <DialogDescription>{t('peerLoan.termsDescription')}</DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <p className="text-sm text-muted-foreground">{t('peerLoan.borrowerHint')}</p>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div className="space-y-2">
              <Label htmlFor="peer-principal">{t('peerLoan.fields.principal')}</Label>
              <NumericInput id="peer-principal" value={principal} onChange={(e) => setPrincipal(e.target.value)} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="peer-rate">{t('peerLoan.fields.ratePct')}</Label>
              <div className="flex items-center gap-2">
                <NumericInput
                  id="peer-rate"
                  className="min-w-0 flex-1"
                  value={ratePct}
                  onChange={(e) => setRatePct(e.target.value)}
                />
                {/* SELECT_CONTROL_CLASS carries w-full, so the wrapper — not the
                    select — is what holds the 7rem width. */}
                <div className="w-28 shrink-0">
                  <select
                    aria-label={t('peerLoan.fields.ratePeriod')}
                    className={SELECT_CONTROL_CLASS}
                    value={ratePeriod}
                    onChange={(e) => handlePeriodChange(e.target.value as PeerLoanRatePeriod)}
                  >
                    <option value="ANNUAL">{t('peerLoan.ratePeriod.ANNUAL')}</option>
                    <option value="MONTHLY">{t('peerLoan.ratePeriod.MONTHLY')}</option>
                  </select>
                </div>
              </div>
            </div>
            <div className="space-y-2">
              <Label htmlFor="peer-lent-at">{t('peerLoan.fields.lentAt')}</Label>
              <DateInput id="peer-lent-at" value={lentAt} onChange={setLentAt} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="peer-expected-back">{t('peerLoan.fields.expectedBack')}</Label>
              <DateInput id="peer-expected-back" value={expectedBack} onChange={setExpectedBack} />
            </div>
          </div>

          <div className="rounded-lg border p-3 space-y-3">
            <p className="text-sm font-medium">{t('peerLoan.forecast.planTitle')}</p>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div className="space-y-2">
                <Label htmlFor="peer-months">{t('peerLoan.fields.plannedMonths')}</Label>
                <NumericInput id="peer-months" value={months} onChange={(e) => {
                  setMonths(e.target.value)
                  setLastEdited('months')
                }} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="peer-monthly">{t('peerLoan.fields.plannedMonthly')}</Label>
                <NumericInput id="peer-monthly" value={monthly} onChange={(e) => {
                  setMonthly(e.target.value)
                  setLastEdited('monthly')
                }} />
              </div>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Button type="button" size="sm" variant="outline" onClick={handleSimulate} disabled={simulate.isPending}>
                {simulate.isPending ? t('common.loading') : t('peerLoan.actions.simulate')}
              </Button>
              {simulation && (
                <span className="text-xs text-muted-foreground">
                  {t('peerLoan.forecast.simulationResult', {
                    months: simulation.months,
                    monthlyPayment: money.amount(simulation.monthlyPayment),
                    interest: money.amount(simulation.totalInterest),
                  })}
                </span>
              )}
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor="peer-linked">{t('peerLoan.fields.linkedAccount')}</Label>
            <select
              id="peer-linked"
              className={SELECT_CONTROL_CLASS}
              value={linkedAccountId}
              onChange={(e) => setLinkedAccountId(e.target.value)}
            >
              <option value="">{t('peerLoan.fields.selectLinkedAccount')}</option>
              {cashAccounts.map(account => (
                <option key={account.id} value={account.id}>{account.name}</option>
              ))}
            </select>
            <p className="text-xs text-muted-foreground">{t('peerLoan.linkedAccountHint')}</p>
          </div>

          {/* A first configuration on a manual account can double-count the outflow:
              the typed balance may already include a loan recorded after the fact. The
              option is visible before an account is picked, so it is discoverable. */}
          {!initial?.configured && (
            <div className="space-y-2 rounded-lg border border-border/60 p-3">
              <label className="flex cursor-pointer items-center gap-2 text-sm">
                <Checkbox
                  checked={alreadyBooked}
                  disabled={selectedCashAccount?.isManual === false}
                  onCheckedChange={(checked) => setAlreadyBooked(checked === true)}
                />
                {t('peerLoan.fields.outflowAlreadyBooked')}
              </label>
              {selectedCashAccount?.isManual === false ? (
                <p className="text-xs text-muted-foreground">{t('peerLoan.termsHint.syncedAccount')}</p>
              ) : alreadyBooked ? (
                <p className="text-xs text-muted-foreground">{t('peerLoan.termsHint.noCashLeg')}</p>
              ) : null}
            </div>
          )}

          <div className="space-y-2">
            <Label htmlFor="peer-note">{t('peerLoan.fields.note')}</Label>
            <Input id="peer-note" value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} />
          </div>

          {error && <p className="text-sm text-destructive" role="alert">{error}</p>}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={loading}>
            {t('common.cancel')}
          </Button>
          <Button onClick={handleSubmit} disabled={loading}>
            {t('common.save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
