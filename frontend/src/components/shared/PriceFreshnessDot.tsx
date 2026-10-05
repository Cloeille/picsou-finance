import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { formatDate, formatTimeAgo } from '@/lib/utils'

interface PriceFreshnessDotProps {
  priceUpdatedAt: string | null
  /**
   * The day the displayed price is for, set only when it is the last price we managed to
   * record rather than a live quote (backend: `priceStale` + `priceAsOf`).
   *
   * When the price provider is unreachable or rate-limiting us, the value shown is a real
   * number from a real day — just not today's. Marking it here is what lets the tables keep
   * displaying a figure instead of a dash: the alternative, blanking the line, made an
   * untouched account read as an 85% loss because its largest positions had no price.
   */
  staleAsOf?: string | null
}

// Strictly greater than the holdings/portfolio refetchInterval (2 min) plus
// tick + network latency, so the dot doesn't flicker to stale right before
// each scheduled refetch lands.
const LIVE_THRESHOLD_MS = 3 * 60 * 1000
const TICK_MS = 30 * 1000

/**
 * The label is also the element's accessible name: a bare coloured dot conveys nothing to a
 * screen reader, and the tooltip that explains it only exists once the pointer is over it.
 */
function Dot({ className, label }: { className: string; label: string }) {
  return (
    <TooltipProvider>
      <Tooltip>
        <TooltipTrigger asChild>
          <span role="img" aria-label={label} className={`size-1.5 shrink-0 rounded-full ${className}`} />
        </TooltipTrigger>
        <TooltipContent>{label}</TooltipContent>
      </Tooltip>
    </TooltipProvider>
  )
}

export function PriceFreshnessDot({ priceUpdatedAt, staleAsOf = null }: PriceFreshnessDotProps) {
  const { t } = useTranslation()
  // Lazy initializer keeps the impure Date.now() out of render; the interval
  // keeps the clock ticking so a tab left open cannot show "live" forever on
  // a timestamp that stopped moving.
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), TICK_MS)
    return () => clearInterval(id)
  }, [])

  // Checked first, and independently of priceUpdatedAt: those are different facts. The latter
  // says when the position was last synced, this one says the price on screen is not today's.
  // A position synced a minute ago can still be valued from yesterday's price.
  if (staleAsOf) {
    return <Dot className="bg-amber-500" label={t('accounts.priceAsOf', { date: formatDate(staleAsOf) })} />
  }

  if (!priceUpdatedAt) return null

  const age = now - new Date(priceUpdatedAt).getTime()
  if (age < LIVE_THRESHOLD_MS) {
    return <Dot className="bg-emerald-500" label={t('accounts.priceLive')} />
  }

  return <Dot className="bg-amber-500" label={t('accounts.priceStale', { time: formatTimeAgo(priceUpdatedAt) })} />
}
