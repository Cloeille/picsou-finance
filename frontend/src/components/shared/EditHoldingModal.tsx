import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { NumericInput } from '@/components/shared/NumericInput'
import { Label } from '@/components/ui/label'
import { parseAmount } from '@/lib/utils'
import { totalFromAvg, avgFromTotal } from '@/features/accounts/cost-basis'
import { Loader2 } from 'lucide-react'
import type { HoldingResponse } from '@/types/api'

interface EditHoldingModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  holding: HoldingResponse | null
  onSubmit: (ticker: string, quantity: number, averageBuyIn?: number) => Promise<void>
  isLoading?: boolean
  /**
   * When true the quantity is owned by an external sync (an on-chain wallet or
   * exchange), so it is shown read-only — the user only corrects the cost basis.
   * Editing it would be overwritten on the next sync.
   */
  quantityReadOnly?: boolean
}

export function EditHoldingModal({ open, onOpenChange, holding, onSubmit, isLoading, quantityReadOnly }: EditHoldingModalProps) {
  const { t } = useTranslation()

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{t('holdings.editTitle', { ticker: holding?.ticker ?? '' })}</DialogTitle>
        </DialogHeader>
        {/* Remount per holding so the form's initial values come straight from
            props — no populate-on-open effect needed. */}
        {open && holding && (
          <HoldingForm
            key={holding.ticker}
            holding={holding}
            onOpenChange={onOpenChange}
            onSubmit={onSubmit}
            isLoading={isLoading}
            quantityReadOnly={quantityReadOnly}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

interface HoldingFormProps {
  holding: HoldingResponse
  onOpenChange: (open: boolean) => void
  onSubmit: (ticker: string, quantity: number, averageBuyIn?: number) => Promise<void>
  isLoading?: boolean
  quantityReadOnly?: boolean
}

/** Trims floating-point noise and trailing zeros from a derived value. */
function fmt(n: number | null): string {
  if (n == null) return ''
  return String(Number(n.toFixed(8)))
}

/** parseAmount but returns null instead of NaN for empty/invalid input. */
function parseFinite(value: string): number | null {
  const n = parseAmount(value)
  return Number.isFinite(n) ? n : null
}

function HoldingForm({ holding, onOpenChange, onSubmit, isLoading, quantityReadOnly }: HoldingFormProps) {
  const { t } = useTranslation()
  const [quantity, setQuantity] = useState(() => String(holding.quantity))
  const [averageBuyIn, setAverageBuyIn] = useState(() => (holding.averageBuyIn != null ? String(holding.averageBuyIn) : ''))
  const [totalInvested, setTotalInvested] = useState(() =>
    fmt(totalFromAvg(holding.averageBuyIn, holding.quantity)),
  )
  const [error, setError] = useState<string | null>(null)

  // The quantity used to keep the two cost-basis fields in sync. When it's
  // read-only it's authoritative (the synced balance); otherwise track the input.
  const effectiveQty = quantityReadOnly ? holding.quantity : parseFinite(quantity)

  function onAvgChange(value: string) {
    setAverageBuyIn(value)
    setTotalInvested(fmt(totalFromAvg(parseFinite(value), effectiveQty)))
  }

  function onTotalChange(value: string) {
    setTotalInvested(value)
    setAverageBuyIn(fmt(avgFromTotal(parseFinite(value), effectiveQty)))
  }

  function onQuantityChange(value: string) {
    setQuantity(value)
    // Keep the average buy-in as the source of truth; refresh the total display.
    setTotalInvested(fmt(totalFromAvg(parseFinite(averageBuyIn), parseFinite(value))))
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      await onSubmit(
        holding.ticker,
        quantityReadOnly ? holding.quantity : parseAmount(quantity),
        averageBuyIn ? parseAmount(averageBuyIn) : undefined,
      )
      onOpenChange(false)
    } catch {
      setError(t('common.error'))
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div className="space-y-1">
        <Label>{t('holdings.quantity')}</Label>
        <NumericInput
          value={quantity}
          onChange={e => onQuantityChange(e.target.value)}
          disabled={quantityReadOnly}
          required={!quantityReadOnly}
        />
        {quantityReadOnly && (
          <p className="text-muted-foreground text-xs">{t('holdings.quantitySyncedHint')}</p>
        )}
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <div className="space-y-1">
          <Label>{t('holdings.avgBuyIn')} <span className="text-muted-foreground text-xs">({t('common.optional')})</span></Label>
          <NumericInput
            value={averageBuyIn}
            onChange={e => onAvgChange(e.target.value)}
            placeholder="—"
          />
        </div>
        <div className="space-y-1">
          <Label>{t('holdings.totalInvested')} <span className="text-muted-foreground text-xs">({t('common.optional')})</span></Label>
          <NumericInput
            value={totalInvested}
            onChange={e => onTotalChange(e.target.value)}
            placeholder="—"
          />
        </div>
      </div>
      <p className="text-muted-foreground text-xs">{t('holdings.costBasisHint')}</p>
      {error && <p className="text-sm text-destructive">{error}</p>}
      <DialogFooter>
        <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>{t('common.cancel')}</Button>
        <Button type="submit" disabled={isLoading}>
          {isLoading && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
          {t('common.save')}
        </Button>
      </DialogFooter>
    </form>
  )
}
