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
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { parseAmount } from '@/lib/utils'
import type { PeerLoanRepayment, PeerLoanRepaymentRequest } from '@/types/api'

interface PeerLoanRepaymentDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  initial?: PeerLoanRepayment | null
  /** Shown as a hint: the interest this loan has accrued but not yet received. */
  accruedInterest: number
  /** Shown as a hint: the most one repayment can take, so the user sees the ceiling. */
  remainingDue: number
  onSubmit: (data: PeerLoanRepaymentRequest) => Promise<void> | void
  loading?: boolean
}

/**
 * One repayment, one amount. The calculator applies the accrued interest first and the
 * remainder to the capital; the ledger row shows that split without asking for it.
 */
export function PeerLoanRepaymentDialog({
  open,
  onOpenChange,
  initial,
  accruedInterest,
  remainingDue,
  onSubmit,
  loading,
}: PeerLoanRepaymentDialogProps) {
  const { t } = useTranslation()

  const [paidOn, setPaidOn] = useState('')
  const [amount, setAmount] = useState('')
  const [note, setNote] = useState('')
  const [withCashLeg, setWithCashLeg] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [wasOpen, setWasOpen] = useState(open)

  if (open !== wasOpen) {
    setWasOpen(open)
    if (open) {
      setPaidOn(initial?.paidOn ?? new Date().toISOString().slice(0, 10))
      setAmount(initial ? String(initial.amount) : '')
      setNote(initial?.note ?? '')
      setWithCashLeg(initial?.hasCashLeg ?? true)
      setError(null)
    }
  }

  async function handleSubmit() {
    setError(null)
    const amountValue = parseAmount(amount)
    if (!Number.isFinite(amountValue) || amountValue <= 0) {
      setError(t('peerLoan.validation.amount'))
      return
    }
    if (!paidOn) {
      setError(t('peerLoan.validation.paidOn'))
      return
    }
    try {
      await onSubmit({
        paidOn,
        amount: amountValue,
        note: note.trim() || null,
        withCashLeg,
      })
      onOpenChange(false)
    } catch {
      // The parent shows the API error; the dialog stays open for a retry.
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>
            {initial ? t('peerLoan.editRepayment') : t('peerLoan.addRepayment')}
          </DialogTitle>
          <DialogDescription>{t('peerLoan.repaymentHint.single')}</DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div className="space-y-2">
              <Label htmlFor="peer-paid-on">{t('peerLoan.fields.paidOn')}</Label>
              <DateInput id="peer-paid-on" value={paidOn} onChange={setPaidOn} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="peer-amount">{t('peerLoan.fields.amount')}</Label>
              <NumericInput id="peer-amount" value={amount} onChange={(e) => setAmount(e.target.value)} />
            </div>
          </div>

          {accruedInterest > 0 && (
            <p className="text-xs text-muted-foreground">
              {t('peerLoan.forecast.accruedHint')} <CurrencyDisplay value={accruedInterest} />
            </p>
          )}
          <p className="text-xs text-muted-foreground">
            {t('peerLoan.forecast.remainingHint')} <CurrencyDisplay value={remainingDue} />
          </p>

          <div className="space-y-2">
            <Label htmlFor="peer-repayment-note">{t('peerLoan.fields.note')}</Label>
            <Input
              id="peer-repayment-note"
              value={note}
              onChange={(e) => setNote(e.target.value)}
              maxLength={255}
            />
          </div>

          <div className="space-y-2 rounded-lg border border-border/60 p-3">
            <label className="flex cursor-pointer items-center gap-2 text-sm">
              <Checkbox
                checked={withCashLeg}
                onCheckedChange={(checked) => setWithCashLeg(checked === true)}
              />
              {t('peerLoan.withCashLeg')}
            </label>
            {!withCashLeg && (
              <p className="text-xs text-muted-foreground">
                {t('peerLoan.repaymentHint.noCashLeg')}
              </p>
            )}
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
