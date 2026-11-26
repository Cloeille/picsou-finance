import { useMoney } from '@/hooks/use-money'
import { signedAmountClass } from '@/lib/money'
import { cn } from '@/lib/utils'

interface CurrencyDisplayProps {
  value: number
  currency?: string
  className?: string
  showSign?: boolean
  /**
   * Colour the figure by its sign via {@link signedAmountClass}.
   * - `true`: positive in Growth Green, negative in Alert Red, zero neutral.
   * - `'inflow'`: positive in Growth Green, negative and zero neutral (for ledger rows).
   *
   * Reserve for signed variations and flows — never balances, totals, targets, or limits.
   */
  colorBySign?: boolean | 'inflow'
  /**
   * A public market quote rather than one of the member's own figures.
   *
   * Stays legible in privacy mode: a share price is published, discloses nothing about how much
   * of it anyone holds, and blanking the whole "Price" column would leave the holdings tables
   * unreadable for no gain. The quantity beside it is masked, which is what makes the position
   * unreconstructible.
   */
  publicQuote?: boolean
}

export function CurrencyDisplay({
  value,
  currency,
  className,
  showSign = false,
  colorBySign = false,
  publicQuote = false,
}: CurrencyDisplayProps) {
  const money = useMoney()
  const cur = currency || 'EUR'

  const formatted = publicQuote
    ? money.quote(Math.abs(value), cur)
    : money.amount(Math.abs(value), cur)
  const sign = showSign && value > 0 ? '+' : value < 0 ? '-' : ''
  const signColor = colorBySign
    ? signedAmountClass(value, colorBySign === 'inflow' ? { negative: 'neutral' } : undefined)
    : ''

  return (
    <span className={cn(className, signColor)}>
      {sign}{formatted}
    </span>
  )
}
