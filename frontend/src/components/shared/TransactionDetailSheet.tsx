import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Pencil, Trash2 } from 'lucide-react'
import type { Category, Transaction } from '@/types/api'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { MerchantAvatar } from '@/components/shared/MerchantAvatar'
import { Button } from '@/components/ui/button'
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
import { Separator } from '@/components/ui/separator'
import { useIsMobile } from '@/hooks/use-mobile'
import { transactionDescription } from '@/lib/transactions'

interface TransactionDetailSheetProps {
  transaction: Transaction | null
  open: boolean
  onClose: () => void
  logoUrlFor?: (brandId: number | null | undefined) => string | null
  categories?: Category[]
  onCategorize?: (txId: number, categoryId: number) => void
  onEdit?: (tx: Transaction) => void
  onDelete?: (txId: number) => void
}

export function TransactionDetailSheet({
  transaction: tx,
  open,
  onClose,
  logoUrlFor,
  categories,
  onCategorize,
  onEdit,
  onDelete,
}: TransactionDetailSheetProps) {
  const { t } = useTranslation()
  const isMobile = useIsMobile()
  const [pendingCategoryId, setPendingCategoryId] = useState<number | ''>('')

  // Re-derive the pending category whenever `tx`/`categories` change, without
  // discarding an in-progress edit on every re-render: adjust state during
  // rendering (React's recommended alternative to an effect here) by tracking
  // the (tx, categories) pair we last derived from.
  const [derivedFrom, setDerivedFrom] = useState<{ tx: typeof tx | undefined; categories: typeof categories }>({
    tx: undefined,
    categories: undefined,
  })
  if (tx !== derivedFrom.tx || categories !== derivedFrom.categories) {
    setDerivedFrom({ tx, categories })
    const match = tx && categories ? categories.find(c => c.name === tx.category) : undefined
    setPendingCategoryId(match ? match.id : '')
  }

  if (!tx) return null

  const label = tx.merchantLabel || transactionDescription(tx, t)
  const canCategorize = !tx.isManual && !!onCategorize && !!categories

  function confirmCategory() {
    if (!tx || pendingCategoryId === '') return
    onCategorize!(tx.id, Number(pendingCategoryId))
    onClose()
  }

  const content = (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center gap-3">
        <MerchantAvatar
          label={label}
          logoUrl={logoUrlFor?.(tx.merchantBrandId)}
          size="md"
        />
        <div className="min-w-0 flex-1">
          <p className="font-semibold break-words">{label}</p>
          {tx.merchantLabel && tx.description !== tx.merchantLabel && (
            <p className="text-xs text-muted-foreground break-words">{tx.description}</p>
          )}
        </div>
      </div>

      <Separator />

      {/* Amount + date */}
      <div className="flex items-center justify-between">
        <CurrencyDisplay
          value={tx.amount}
          currency={tx.nativeCurrency}
          className={tx.amount >= 0 ? 'text-xl font-bold text-emerald-500' : 'text-xl font-bold'}
        />
        <span className="text-sm text-muted-foreground">
          {new Date(tx.date).toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })}
        </span>
      </div>

      {/* Account */}
      {tx.accountName && (
        <div className="flex items-center justify-between text-sm">
          <span className="text-muted-foreground">{t('transactions.account')}</span>
          <span className="font-medium">{tx.accountName}</span>
        </div>
      )}

      {/* Category */}
      {canCategorize ? (
        <div className="space-y-2">
          <p className="text-sm text-muted-foreground">{t('accounts.changeCategory')}</p>
          <select
            value={pendingCategoryId}
            onChange={(e) => setPendingCategoryId(e.target.value === '' ? '' : Number(e.target.value))}
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus:border-ring"
          >
            <option value="">{t('budget.categorize.selectCategory')}</option>
            {(categories ?? [])
              .filter(c => !c.archived)
              .map(c => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
          </select>
          <div className="flex justify-end gap-2">
            <Button variant="outline" size="sm" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button size="sm" disabled={pendingCategoryId === ''} onClick={confirmCategory}>
              {t('common.confirm')}
            </Button>
          </div>
        </div>
      ) : tx.category ? (
        <div className="flex items-center justify-between text-sm">
          <span className="text-muted-foreground">{t('common.category')}</span>
          <span className="font-medium">{tx.category}</span>
        </div>
      ) : null}

      {/* Actions for manual transactions */}
      {tx.isManual && (onEdit || onDelete) && (
        <>
          <Separator />
          <div className="flex gap-2">
            {onEdit && (
              <Button variant="outline" size="sm" className="flex-1" onClick={() => { onEdit(tx); onClose() }}>
                <Pencil size={14} className="mr-1.5" />
                {t('common.edit')}
              </Button>
            )}
            {onDelete && (
              <Button variant="outline" size="sm" className="flex-1 text-destructive hover:text-destructive" onClick={() => { onDelete(tx.id); onClose() }}>
                <Trash2 size={14} className="mr-1.5" />
                {t('common.delete')}
              </Button>
            )}
          </div>
        </>
      )}
    </div>
  )

  if (isMobile) {
    return (
      <Sheet open={open} onOpenChange={(o) => { if (!o) onClose() }}>
        <SheetContent side="bottom" className="px-4 pb-6 pt-4 max-h-[90dvh] overflow-y-auto">
          <SheetHeader className="mb-4 p-0">
            <SheetTitle>{t('transactions.detail')}</SheetTitle>
          </SheetHeader>
          {content}
        </SheetContent>
      </Sheet>
    )
  }

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose() }}>
      <DialogContent className="sm:max-w-sm max-h-[90vh] overflow-y-auto overflow-x-hidden">
        <DialogHeader>
          <DialogTitle>{t('transactions.detail')}</DialogTitle>
        </DialogHeader>
        {content}
      </DialogContent>
    </Dialog>
  )
}
