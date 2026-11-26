import type { Account } from '@/types/api'

/**
 * A PER (kept unconditionally for compatibility with existing PER accounts), or a synced BPCE
 * insurer assurance vie (classified from backend sync provenance rather than the editable provider
 * label): its euro fund is stored in `cashBalance` and read from the insurer.
 */
export function hasSyncedEuroFund(
  account: Pick<Account, 'type' | 'isManual' | 'isInsurerContract'>,
): boolean {
  if (account.type === 'PER') return true
  // A hand-made account never qualifies (the backend rule is the same).
  return account.type === 'ASSURANCE_VIE'
    && account.isManual === false
    && account.isInsurerContract === true
}

export function euroFundLabelKey(account: Pick<Account, 'type'>): string {
  return account.type === 'PER' ? 'accounts.perEuroFundBalance' : 'accounts.lifeEuroFundBalance'
}
