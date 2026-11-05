import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { formatLocalDate, localeFromLanguage } from '@/lib/utils'
import type { Account, RecurringCadence } from '@/types/api'

/** French tax law: an assurance-vie's gains are taxed more lightly from its eighth anniversary. */
const TAX_ADVANTAGE_YEARS = 8

const CADENCE_SUFFIX_KEY: Record<RecurringCadence, string> = {
  WEEKLY: 'accounts.lifeContract.perWeek',
  BIWEEKLY: 'accounts.lifeContract.perTwoWeeks',
  MONTHLY: 'accounts.lifeContract.perMonth',
  QUARTERLY: 'accounts.lifeContract.perQuarter',
  YEARLY: 'accounts.lifeContract.perYear',
}

/**
 * The same calendar day `years` later, as `yyyy-MM-dd`. Worked on the string parts so no
 * timezone can shift the day; a 29 February whose anniversary year is not a leap year lands on
 * the 28th.
 */
function anniversary(date: string, years: number): string | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date)
  if (!m) return null
  const year = Number(m[1]) + years
  const leap = (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0
  const day = m[2] === '02' && m[3] === '29' && !leap ? '28' : m[3]
  return `${year}-${m[2]}-${day}`
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="font-medium tabular-nums text-foreground">{children}</dd>
    </div>
  )
}

/**
 * The contract-level figures an insurer reports for an assurance-vie. The gain is not repeated
 * here: the page's portfolio performance already shows it against the same net invested amount
 * (see `docs/features/corum-life.md`). Rows the insurer does not report are left out rather than
 * shown as a dash.
 */
export function LifeContractSection({ account }: { account: Account }) {
  const { t, i18n } = useTranslation()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)
  const contract = account.contract
  if (!contract) return null

  const openedAt = account.openedAt ?? null
  const taxDate = openedAt ? anniversary(openedAt, TAX_ADVANTAGE_YEARS) : null
  const net = contract.netInvestedEur
  const schedule = contract.scheduledPaymentEur != null && contract.scheduledPaymentCadence != null
    ? { amount: contract.scheduledPaymentEur, cadence: contract.scheduledPaymentCadence }
    : null

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{t('accounts.lifeContract.title')}</CardTitle>
      </CardHeader>
      <CardContent>
        <dl className="grid grid-cols-2 gap-4 text-sm">
          {openedAt && (
            <Row label={t('accounts.lifeContract.openedAt')}>{formatLocalDate(openedAt, locale)}</Row>
          )}
          {taxDate && (
            <Row label={t('accounts.lifeContract.taxAdvantageFrom')}>{formatLocalDate(taxDate, locale)}</Row>
          )}
          {contract.paidInEur != null && (
            <Row label={t('accounts.lifeContract.paidIn')}><CurrencyDisplay value={contract.paidInEur} /></Row>
          )}
          {net != null && (
            <Row label={t('accounts.lifeContract.netInvested')}><CurrencyDisplay value={net} /></Row>
          )}
          {schedule && (
            <Row label={t('accounts.lifeContract.scheduledPayment')}>
              <CurrencyDisplay value={schedule.amount} />
              {t(CADENCE_SUFFIX_KEY[schedule.cadence])}
            </Row>
          )}
        </dl>
      </CardContent>
    </Card>
  )
}
