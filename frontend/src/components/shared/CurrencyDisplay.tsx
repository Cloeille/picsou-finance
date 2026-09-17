import { useMoney } from '@/hooks/use-money'

interface CurrencyDisplayProps {
  value: number
  currency?: string
  className?: string
  showSign?: boolean
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

export function CurrencyDisplay({ value, currency, className, showSign = false, publicQuote = false }: CurrencyDisplayProps) {
  const money = useMoney()
  const cur = currency || 'EUR'

  const formatted = publicQuote
    ? money.quote(Math.abs(value), cur)
    : money.amount(Math.abs(value), cur)
  const sign = showSign && value >= 0 ? '+' : value < 0 ? '-' : ''

  return (
    <span className={className}>
      {sign}{formatted}
    </span>
  )
}
