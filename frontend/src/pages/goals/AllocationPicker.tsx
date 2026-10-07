import { useTranslation } from 'react-i18next'
import { useHoldingsWithLivePrices } from '@/features/accounts/hooks'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { NumericInput } from '@/components/shared/NumericInput'
import { Label } from '@/components/ui/label'
import { cn } from '@/lib/utils'
import { allocatedTotal, type AllocationDraft } from './plan-math'

interface AllocationPickerProps {
  /** The funded account, or null while none is picked. */
  accountId: number | null
  monthlyAmount: number
  allocations: AllocationDraft[]
  onChange: (next: AllocationDraft[]) => void
}

/**
 * Splits a recurring plan's monthly amount across positions the funded account already holds.
 *
 * The list is the account's own holdings rather than a free-text field: the backend refuses a
 * ticker the account does not hold, so offering one would be offering a request that cannot
 * succeed. It also means the member never types a ticker, which is where the mistakes are.
 *
 * A partial split is legal — the remainder reads as unallocated — but an over-allocation is not,
 * and the line below turns red before the Save button refuses. The 422 behind it stays the
 * authority; this is just the earlier, kinder version of the same answer.
 */
export function AllocationPicker({
  accountId,
  monthlyAmount,
  allocations,
  onChange,
}: AllocationPickerProps) {
  const { t } = useTranslation()
  // `enabled: !!id` inside the hook covers the no-account-yet case; 0 is never a real id.
  const { data: holdings, isLoading } = useHoldingsWithLivePrices(accountId ?? 0)

  const allocated = allocatedTotal(allocations)
  const remainder = monthlyAmount - allocated
  const over = remainder < -0.005

  const toggle = (ticker: string) => {
    const existing = allocations.find(a => a.ticker === ticker)
    onChange(
      existing
        ? allocations.filter(a => a.ticker !== ticker)
        : [...allocations, { ticker, amount: '' }],
    )
  }

  const setAmount = (ticker: string, amount: string) =>
    onChange(allocations.map(a => (a.ticker === ticker ? { ...a, amount } : a)))

  return (
    <div className="flex flex-col gap-2">
      <div>
        <Label>{t('goals.allocation.title')}</Label>
        <p className="text-sm text-muted-foreground">{t('goals.allocation.hint')}</p>
      </div>

      {accountId == null ? (
        <p className="text-sm text-muted-foreground">{t('goals.allocation.pickAccountFirst')}</p>
      ) : isLoading ? (
        <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
      ) : !holdings || holdings.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t('goals.allocation.noHoldings')}</p>
      ) : (
        <>
          <div className="flex max-h-48 flex-col gap-2 overflow-y-auto">
            {holdings.map(h => {
              const line = allocations.find(a => a.ticker === h.ticker)
              return (
                <div key={h.ticker} className="flex items-center gap-2.5">
                  <label className="flex min-w-0 flex-1 cursor-pointer select-none items-center gap-2.5">
                    <input
                      type="checkbox"
                      checked={line != null}
                      onChange={() => toggle(h.ticker)}
                      className="rounded accent-primary"
                    />
                    <span className="font-mono text-sm">{h.ticker}</span>
                    <span className="truncate text-sm text-muted-foreground">{h.name ?? h.ticker}</span>
                  </label>
                  {line && (
                    <NumericInput
                      aria-label={t('goals.allocation.amountFor', { ticker: h.ticker })}
                      className="w-28 shrink-0"
                      value={line.amount}
                      onChange={e => setAmount(h.ticker, e.target.value)}
                      placeholder="100"
                    />
                  )}
                </div>
              )
            })}
          </div>

          {allocations.length > 0 && (
            <p className={cn('text-sm', over ? 'text-destructive' : 'text-muted-foreground')}>
              {t('goals.allocation.allocated')} <CurrencyDisplay value={allocated} />
              {' — '}
              {over ? t('goals.allocation.over') : t('goals.allocation.remaining')}{' '}
              <CurrencyDisplay value={Math.abs(remainder)} />
            </p>
          )}
        </>
      )}
    </div>
  )
}
