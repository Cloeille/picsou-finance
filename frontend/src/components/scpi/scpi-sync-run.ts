import type { ScpiFund, ScpiProvider } from '@/types/api'

/**
 * The funds written by one sync run. The backend stamps every fund of a run with the
 * same instant it records as the session's `lastSyncCompletedAt`, so the run is matched
 * exactly rather than guessed from the newest row.
 */
export function syncRun(funds: ScpiFund[], provider: ScpiProvider, completedAt: string): ScpiFund[] {
  const at = Date.parse(completedAt)
  return funds.filter(fund => fund.provider === provider && fund.lastOutcome
    && fund.lastSyncedAt != null && Date.parse(fund.lastSyncedAt) === at)
}
