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
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import type { PeerLoanFromTransactionRequest, Transaction } from '@/types/api'

interface CreatePeerLoanDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  transaction: Transaction | null
  onSubmit: (data: PeerLoanFromTransactionRequest) => Promise<void> | void
  loading?: boolean
}

/**
 * Starts a peer loan from an existing outgoing transaction. The transaction is linked
 * as the lending leg, so the money that already left the account is not counted twice.
 */
export function CreatePeerLoanDialog({
  open,
  onOpenChange,
  transaction,
  onSubmit,
  loading,
}: CreatePeerLoanDialogProps) {
  const { t } = useTranslation()
  const [beneficiary, setBeneficiary] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [wasOpen, setWasOpen] = useState(open)

  if (open !== wasOpen) {
    setWasOpen(open)
    if (open) {
      setBeneficiary(transaction?.merchantLabel || transaction?.description || '')
      setError(null)
    }
  }

  if (!transaction) return null

  async function handleSubmit() {
    setError(null)
    const name = beneficiary.trim()
    if (!name) {
      setError(t('peerLoan.fromTransaction.beneficiaryRequired'))
      return
    }
    try {
      await onSubmit({ transactionId: transaction!.id, beneficiary: name })
      onOpenChange(false)
    } catch {
      // The parent surfaces the API error; the dialog stays open for a retry.
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{t('peerLoan.fromTransaction.title')}</DialogTitle>
          <DialogDescription>{t('peerLoan.fromTransaction.description')}</DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <div className="flex items-center justify-between gap-3 rounded-md border p-3 text-sm">
            <span className="truncate text-muted-foreground">
              {transaction.merchantLabel || transaction.description}
            </span>
            <CurrencyDisplay value={Math.abs(transaction.amount)} className="font-semibold" />
          </div>

          <div className="space-y-2">
            <Label htmlFor="peer-beneficiary">{t('peerLoan.fields.beneficiary')}</Label>
            <Input
              id="peer-beneficiary"
              value={beneficiary}
              onChange={(e) => setBeneficiary(e.target.value)}
              maxLength={100}
            />
            <p className="text-xs text-muted-foreground">{t('peerLoan.fromTransaction.beneficiaryHint')}</p>
          </div>

          {error && <p className="text-sm text-destructive" role="alert">{error}</p>}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={loading}>
            {t('common.cancel')}
          </Button>
          <Button onClick={handleSubmit} disabled={loading}>
            {loading ? t('common.loading') : t('peerLoan.fromTransaction.confirm')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
