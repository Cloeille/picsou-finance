import { describe, expect, it } from 'vitest'
import { ACCOUNT_TYPES, accountTypeLabelKey } from './constants'
import type { AccountType } from '@/types/api'
import fr from '@/i18n/locales/fr.json'
import en from '@/i18n/locales/en.json'
import de from '@/i18n/locales/de.json'
import es from '@/i18n/locales/es.json'

/**
 * Every value of the `AccountType` union, restated so the compiler checks each literal and the
 * assertion below catches a type added to the union without an entry in ACCOUNT_TYPES — which is
 * what makes ACCOUNT_TYPES the single list every label lookup can rely on.
 */
const EVERY_TYPE: AccountType[] = [
  'CHECKING', 'SAVINGS', 'LEP', 'LIVRET_A', 'LDDS', 'LIVRET_JEUNE', 'PEL', 'CEL',
  'PEA', 'COMPTE_TITRES', 'CRYPTO', 'ASSURANCE_VIE', 'REAL_ESTATE', 'SCPI',
  'EMPLOYEE_SAVINGS', 'LOAN', 'OTHER',
]

const LOCALES = { fr, en, de, es } as Record<string, { accountTypes: Record<string, string> }>

describe('account type labels', () => {
  it('covers every account type exactly once', () => {
    expect(ACCOUNT_TYPES.map((t) => t.value).sort()).toEqual([...EVERY_TYPE].sort())
  })

  it.each(Object.keys(LOCALES))('resolves every label in %s', (locale) => {
    // The bug this pins: a component deriving its own key (`accountTypes.${type.toLowerCase()}`
    // → "accountTypes.livret_a") rendered the raw key next to the account name. Every call site
    // now goes through accountTypeLabelKey, so the keys it returns must all exist.
    const missing = EVERY_TYPE.filter(
      (type) => !(accountTypeLabelKey(type).replace('accountTypes.', '') in LOCALES[locale].accountTypes),
    )
    expect(missing).toEqual([])
  })

  it('falls back to Other for a value outside the list', () => {
    expect(accountTypeLabelKey('NOT_A_TYPE' as AccountType)).toBe('accountTypes.other')
  })
})
