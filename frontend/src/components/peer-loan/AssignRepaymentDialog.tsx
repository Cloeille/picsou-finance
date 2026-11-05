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
import { Label } from '@/components/ui/label'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { SELECT_CONTROL_CLASS } from '@/lib/constants'
import { useMoney } from '@/hooks/use-money'
import type { PeerLoanOpen, PeerLoanRepaymentFromTransactionRequest, Transaction } from '@/types/api'

type Kind = 'PRINCIPAL' | 'INTEREST'

interface AssignRepaymentDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  transaction: Transaction | null
  loans: PeerLoanOpen[]
  onSubmit: (accountId: number, data: PeerLoanRepaymentFromTransactionRequest) => Promise<void> | void
  loading?: boolean
}

/**
 * Assigns an incoming transaction to an open loan. The transaction becomes the
 * repayment's cash leg, so no second row is written on a synced account.
 */
export function AssignRepaymentDialog({
  open,
  onOpenChange,
  transaction,
  loans,
  onSubmit,
  loading,
}: AssignRepaymentDialogProps) {
  const { t } = useTranslation()
  const money = useMoney()
  const [loanId, setLoanId] = useState('')
  const [kind, setKind] = useState<Kind>('PRINCIPAL')
  const [error, setError] = useState<string | null>(null)
  const [wasOpen, setWasOpen] = useState(open)

  if (open !== wasOpen) {
    setWasOpen(open)
    if (open) {
      setLoanId('')
      setKind('PRINCIPAL')
      setError(null)
    }
  }

  if (!transaction) return null

  async function handleSubmit() {
    setError(null)
    if (!loanId) {
      setError(t('peerLoan.assignRepayment.loanRequired'))
      return
    }
    try {
      await onSubmit(Number(loanId), { transactionId: transaction!.id, kind })
      onOpenChange(false)
    } catch {
      // The parent surfaces the API error; the dialog stays open for a retry.
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{t('peerLoan.assignRepayment.title')}</DialogTitle>
          <DialogDescription>{t('peerLoan.assignRepayment.description')}</DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <div className="flex items-center justify-between gap-3 rounded-md border p-3 text-sm">
            <span className="truncate text-muted-foreground">
              {transaction.merchantLabel || transaction.description}
            </span>
            <CurrencyDisplay value={transaction.amount} className="font-semibold text-emerald-500" />
          </div>

          <div className="space-y-2">
            <Label htmlFor="assign-loan">{t('peerLoan.assignRepayment.loan')}</Label>
            <select
              id="assign-loan"
              className={SELECT_CONTROL_CLASS}
              value={loanId}
              onChange={(e) => setLoanId(e.target.value)}
              disabled={loans.length === 0}
            >
              <option value="">{t('peerLoan.assignRepayment.selectLoan')}</option>
              {loans.map(loan => (
                <option key={loan.accountId} value={loan.accountId}>
                  {loan.name} — {money.amount(loan.remaining)}
                </option>
              ))}
            </select>
            {loans.length === 0 && (
              <p className="text-xs text-muted-foreground">{t('peerLoan.assignRepayment.noLoans')}</p>
            )}
          </div>

          <div className="space-y-2">
            <Label htmlFor="assign-kind">{t('peerLoan.fields.kind')}</Label>
            <select
              id="assign-kind"
              className={SELECT_CONTROL_CLASS}
              value={kind}
              onChange={(e) => setKind(e.target.value as Kind)}
            >
              <option value="PRINCIPAL">{t('peerLoan.kinds.PRINCIPAL')}</option>
              <option value="INTEREST">{t('peerLoan.kinds.INTEREST')}</option>
            </select>
            <p className="text-xs text-muted-foreground">{t('peerLoan.assignRepayment.kindHint')}</p>
          </div>

          {error && <p className="text-sm text-destructive" role="alert">{error}</p>}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={loading}>
            {t('common.cancel')}
          </Button>
          <Button onClick={handleSubmit} disabled={loading || loans.length === 0}>
            {loading ? t('common.loading') : t('peerLoan.assignRepayment.confirm')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
