import { useTranslation } from 'react-i18next'
import { useScpiFunds } from '@/features/accounts/hooks'
import type { ScpiFundOutcome, ScpiProvider } from '@/types/api'
import { syncRun } from './scpi-sync-run'

const APPLIED: ReadonlySet<ScpiFundOutcome> = new Set(['CREATED', 'LINKED', 'UPDATED', 'PRICE_INCOMPLETE'])

/** Outcomes worth naming fund by fund: each one wrote nothing, or wrote no balance. */
const NOTABLE: readonly ScpiFundOutcome[] = ['PRICE_INCOMPLETE', 'AMBIGUOUS', 'IGNORED', 'MISSING_AT_PROVIDER']

/**
 * What the last CORUM or Sofidy sync did with each fund (GH issue #232). A sync that
 * found funds but applied none is shown as a warning, never as a plain success.
 */
export function ScpiSyncSummary({ provider, completedAt }: { provider: ScpiProvider; completedAt?: string | null }) {
  const { t } = useTranslation()
  const funds = useScpiFunds()
  if (!funds.data || !completedAt) return null

  const run = syncRun(funds.data, provider, completedAt)
  const count = (outcome: ScpiFundOutcome) => run.filter(fund => fund.lastOutcome === outcome).length
  const listed = run.filter(fund => fund.lastOutcome !== 'MISSING_AT_PROVIDER')
  const applied = run.filter(fund => APPLIED.has(fund.lastOutcome!)).length
  const parts = (['CREATED', 'LINKED', 'UPDATED'] as const)
    .filter(outcome => count(outcome) > 0)
    .map(outcome => t(`scpi.sync.outcome.${outcome}`, { count: count(outcome) }))
  const notable = run.filter(fund => NOTABLE.includes(fund.lastOutcome!))

  return (
    <div className="mt-2 space-y-1 text-sm" data-testid="scpi-sync-summary">
      {applied > 0 ? (
        <p className="text-emerald-600 dark:text-emerald-400">
          {t('scpi.sync.found', { count: listed.length })}
          {parts.length > 0 && ` — ${parts.join(', ')}`}
        </p>
      ) : (
        <p className="text-amber-600 dark:text-amber-400">
          {listed.length === 0 ? t('scpi.sync.noFund') : t('scpi.sync.noneApplied', { count: listed.length })}
        </p>
      )}
      {notable.length > 0 && (
        <ul className="list-disc pl-5 text-muted-foreground">
          {notable.map(fund => (
            <li key={fund.fundCode}>
              {fund.label ?? fund.fundCode} ({fund.fundCode}) : {t(`scpi.sync.detail.${fund.lastOutcome}`)}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
