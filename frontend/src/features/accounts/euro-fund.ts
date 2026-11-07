import type { Account } from '@/types/api'

const CAISSE_EPARGNE = "Caisse d'Epargne"

/** A PER, or an assurance vie imported from the Caisse d'Epargne: its euro fund is read from the insurer. */
export function hasSyncedEuroFund(account: Pick<Account, 'type' | 'provider'>): boolean {
  return account.type === 'PER' || (account.type === 'ASSURANCE_VIE' && account.provider === CAISSE_EPARGNE)
}

export function euroFundLabelKey(account: Pick<Account, 'type'>): string {
  return account.type === 'PER' ? 'accounts.perEuroFundBalance' : 'accounts.lifeEuroFundBalance'
}
