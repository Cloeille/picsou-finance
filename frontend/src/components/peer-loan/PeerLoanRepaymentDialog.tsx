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
import { NumericInput } from '@/components/shared/NumericInput'
import { DateInput } from '@/components/shared/DateInput'
import { SELECT_CONTROL_CLASS } from '@/lib/constants'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { parseAmount } from '@/lib/utils'
import type { PeerLoanRepayment, PeerLoanRepaymentKind, PeerLoanRepaymentRequest } from '@/types/api'

interface PeerLoanRepaymentDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  initial?: PeerLoanRepayment | null
  /** Shown as a hint: the interest this loan has accrued but not yet received. */
  accruedInterest: number
  onSubmit: (data: PeerLoanRepaymentRequest) => Promise<void> | void
  loading?: boolean
}

const KINDS: PeerLoanRepaymentKind[] = ['PRINCIPAL', 'INTEREST', 'WRITE_OFF']

/**
 * One ledger row, one fact, one budget category. A capital repayment is a transfer;
 * interest is real income; a write-off carries no cash at all. Splitting them by hand
 * is what keeps the cashflow honest — the app never splits a payment implicitly.
 */
export function PeerLoanRepaymentDialog({
  open,
  onOpenChange,
  initial,
  accruedInterest,
  onSubmit,
  loading,
}: PeerLoanRepaymentDialogProps) {
  const { t } = useTranslation()

  const [paidOn, setPaidOn] = useState('')
  const [amount, setAmount] = useState('')
  const [kind, setKind] = useState<PeerLoanRepaymentKind>('PRINCIPAL')
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [wasOpen, setWasOpen] = useState(open)

  if (open !== wasOpen) {
    setWasOpen(open)
    if (open) {
      setPaidOn(initial?.paidOn ?? new Date().toISOString().slice(0, 10))
      setAmount(initial ? String(initial.amount) : '')
      setKind(initial?.kind ?? 'PRINCIPAL')
      setNote(initial?.note ?? '')
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
        kind,
        note: note.trim() || null,
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
          <DialogDescription>
            {kind === 'PRINCIPAL' && t('peerLoan.repaymentHint.principal')}
            {kind === 'INTEREST' && t('peerLoan.repaymentHint.interest')}
            {kind === 'WRITE_OFF' && t('peerLoan.repaymentHint.writeOff')}
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="peer-kind">{t('peerLoan.fields.kind')}</Label>
            <select
              id="peer-kind"
              className={SELECT_CONTROL_CLASS}
              value={kind}
              onChange={(e) => setKind(e.target.value as PeerLoanRepaymentKind)}
            >
              {KINDS.map(value => (
                <option key={value} value={value}>{t(`peerLoan.kinds.${value}`)}</option>
              ))}
            </select>
          </div>

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

          {kind === 'INTEREST' && accruedInterest > 0 && (
            <p className="text-xs text-muted-foreground">
              {t('peerLoan.forecast.accruedHint')} <CurrencyDisplay value={accruedInterest} />
            </p>
          )}

          <div className="space-y-2">
            <Label htmlFor="peer-repayment-note">{t('peerLoan.fields.note')}</Label>
            <Input
              id="peer-repayment-note"
              value={note}
              onChange={(e) => setNote(e.target.value)}
              maxLength={255}
            />
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
