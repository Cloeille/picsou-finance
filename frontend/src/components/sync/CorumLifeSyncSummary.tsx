import { useTranslation } from 'react-i18next'
import { lifeRun } from '@/components/scpi/scpi-sync-run'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { useAllAccounts } from '@/features/accounts/hooks'

/**
 * The life-insurance (assurance-vie) accounts the last CORUM sync wrote, with their balance,
 * and the contracts it wrote nowhere (`ambiguous`), which are a warning, never a success.
 */
export function CorumLifeSyncSummary({ completedAt, ambiguous = 0 }: {
  completedAt?: string | null
  ambiguous?: number
}) {
  const { t } = useTranslation()
  // Hidden accounts included: hiding a contract does not stop the sync from writing it.
  const accounts = useAllAccounts()
  if (!completedAt) return null

  const run = accounts.data ? lifeRun(accounts.data, completedAt) : []
  if (run.length === 0 && ambiguous === 0) return null

  return (
    <div className="mt-2 space-y-1 text-sm" data-testid="corum-life-sync-summary">
      {run.length > 0 && (
        <>
          <p className="text-emerald-600 dark:text-emerald-400">{t('sync.corum.life.synced', { count: run.length })}</p>
          <ul className="space-y-0.5">
            {run.map(account => (
              <li key={account.id} className="flex justify-between gap-3">
                <span className="text-muted-foreground">{account.name}</span>
                <CurrencyDisplay value={account.currentBalanceEur} currency="EUR" className="font-medium text-foreground" />
              </li>
            ))}
          </ul>
        </>
      )}
      {ambiguous > 0 && (
        <p className="text-amber-600 dark:text-amber-400">{t('sync.corum.life.ambiguous', { count: ambiguous })}</p>
      )}
    </div>
  )
}
