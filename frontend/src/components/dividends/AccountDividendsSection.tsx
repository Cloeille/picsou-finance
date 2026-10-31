import { useRef, type KeyboardEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { useUpcomingDividends } from '@/features/dividends/hooks'
import { HoldingLogo } from '@/components/shared/HoldingLogo'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDate, localeFromLanguage } from '@/lib/utils'
import type { DividendCadence, HoldingResponse, UpcomingDividend } from '@/types/api'

const CADENCE_LABEL_KEY: Record<DividendCadence, string> = {
  MONTHLY: 'accounts.dividends.cadence.MONTHLY',
  QUARTERLY: 'accounts.dividends.cadence.QUARTERLY',
  SEMI_ANNUAL: 'accounts.dividends.cadence.SEMI_ANNUAL',
  ANNUAL: 'accounts.dividends.cadence.ANNUAL',
}

/**
 * One snap interval: the card is `w-64` (16rem) and the row uses `gap-3` (0.75rem).
 * Measuring the card would be 0 in a layout-less test, and the width is fixed in CSS,
 * so the buttons and the arrow keys share this step.
 */
const CARD_SCROLL_PX = 256 + 12

interface AccountDividendsSectionProps {
  accountId: number
  /**
   * Holdings already loaded on the account page. Used only to reuse {@link HoldingLogo};
   * a missing list, or a ticker with no mark, falls back to the empty disc.
   */
  holdings?: HoldingResponse[]
}

function logoFor(holdings: HoldingResponse[] | undefined, ticker: string | null): HoldingResponse | undefined {
  if (!ticker || !holdings) return undefined
  const key = ticker.toUpperCase()
  return holdings.find((holding) => holding.ticker.toUpperCase() === key)
}

/**
 * Upcoming dividends for one compte-titres or PEA, as a horizontal carousel of cards.
 *
 * The page decides which account types mount this. The horizon is a year, not the
 * budget card's 60 days: a securities account is where the reader comes to see the
 * next detachment, not a household total. A failed forecast stays inside the card.
 */
export function AccountDividendsSection({ accountId, holdings }: AccountDividendsSectionProps) {
  const { t, i18n } = useTranslation()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)
  const scrollerRef = useRef<HTMLDivElement>(null)
  const query = useUpcomingDividends({ horizonDays: 366, accountId })
  const rows = query.data ?? []
  const total = rows.reduce((sum, row) => sum + row.amountEur, 0)

  function scroll(direction: -1 | 1) {
    scrollerRef.current?.scrollBy({ left: direction * CARD_SCROLL_PX, behavior: 'smooth' })
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') return
    event.preventDefault()
    scroll(event.key === 'ArrowRight' ? 1 : -1)
  }

  const showCarousel = !query.isLoading && !query.isError && rows.length > 0

  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between gap-3 space-y-0">
        <div>
          <CardTitle id="account-dividends-heading" className="text-base">
            {t('accounts.dividends.title')}
          </CardTitle>
          {showCarousel && (
            <p className="mt-1 text-sm text-muted-foreground">
              <CurrencyDisplay value={total} className="font-medium text-foreground" />
              {' '}
              {t('accounts.dividends.yearTotal')}
            </p>
          )}
        </div>
        {showCarousel && (
          <div className="hidden gap-1 md:flex">
            <Button
              type="button"
              variant="outline"
              size="icon-sm"
              aria-label={t('accounts.dividends.prev')}
              aria-controls="account-dividends-carousel"
              onClick={() => scroll(-1)}
            >
              <ChevronLeft />
            </Button>
            <Button
              type="button"
              variant="outline"
              size="icon-sm"
              aria-label={t('accounts.dividends.next')}
              aria-controls="account-dividends-carousel"
              onClick={() => scroll(1)}
            >
              <ChevronRight />
            </Button>
          </div>
        )}
      </CardHeader>
      <CardContent>
        {query.isLoading ? (
          <Skeleton className="h-36 w-64" />
        ) : query.isError ? (
          <p className="text-sm text-muted-foreground">{t('accounts.dividends.error')}</p>
        ) : rows.length === 0 ? (
          <>
            <p className="text-sm">{t('accounts.dividends.empty')}</p>
            <p className="mt-1 text-xs text-muted-foreground">{t('accounts.dividends.refreshWeekly')}</p>
          </>
        ) : (
          <div
            ref={scrollerRef}
            id="account-dividends-carousel"
            role="region"
            aria-label={t('accounts.dividends.carousel')}
            tabIndex={0}
            onKeyDown={onKeyDown}
            className="flex w-full min-w-0 gap-3 overflow-x-auto snap-x snap-mandatory scroll-px-1 pb-1 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            {rows.map((row) => (
              <DividendCard
                key={row.id}
                row={row}
                locale={locale}
                holding={logoFor(holdings, row.ticker)}
              />
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function DividendCard({
  row,
  locale,
  holding,
}: {
  row: UpcomingDividend
  locale: string
  holding: HoldingResponse | undefined
}) {
  const { t } = useTranslation()
  const paymentKnown = row.paymentDate != null

  return (
    <article className="flex w-64 shrink-0 snap-start flex-col rounded-xl border bg-background p-4">
      <div className="flex items-start gap-2">
        {row.ticker && (
          <HoldingLogo
            logoUrl={holding?.logoUrl}
            logoUrlDark={holding?.logoUrlDark}
            ticker={row.ticker}
            className="size-8"
          />
        )}
        <div className="min-w-0">
          <p className="truncate font-medium">{row.name}</p>
          {row.ticker && <p className="truncate text-xs text-muted-foreground">{row.ticker}</p>}
        </div>
      </div>
      <div className="mt-2 flex flex-wrap gap-1">
        {row.basis === 'ANNOUNCED' && (
          <Badge variant="secondary">{t('accounts.dividends.announced')}</Badge>
        )}
        {row.source === 'HOLDING' && row.basis !== 'ANNOUNCED' && (
          <Badge variant="outline">{t('accounts.dividends.estimatedBasis')}</Badge>
        )}
        {!row.countsInBudget && (
          <Badge variant="outline">{t('budget.overview.staysInEnvelope')}</Badge>
        )}
        {row.cadence != null && (
          <Badge variant="outline">{t(CADENCE_LABEL_KEY[row.cadence])}</Badge>
        )}
      </div>
      <p className="mt-3 text-2xl font-bold tabular-nums">
        <CurrencyDisplay value={row.amountEur} />
      </p>
      <p className="mt-3 text-sm">
        <span className="text-muted-foreground">{t('accounts.dividends.exDate')}</span>
        {' '}
        <span className="tabular-nums">{row.exDate ? formatDate(row.exDate, locale) : '—'}</span>
      </p>
      <p className="mt-1 text-sm">
        <span className="text-muted-foreground">{t('accounts.dividends.paymentDate')}</span>
        {' '}
        {paymentKnown ? (
          <span className="tabular-nums">{formatDate(row.paymentDate, locale)}</span>
        ) : (
          <span>{t('accounts.dividends.paymentUnknown')}</span>
        )}
        {paymentKnown && row.paymentDateEstimated && (
          <span className="ml-1 text-xs text-muted-foreground">{t('accounts.dividends.estimated')}</span>
        )}
      </p>
      <p className="mt-3 text-xs text-muted-foreground">
        <CurrencyDisplay value={row.amountPerShareEur} />
        {' '}
        {t('accounts.dividends.perShare')}
      </p>
    </article>
  )
}
