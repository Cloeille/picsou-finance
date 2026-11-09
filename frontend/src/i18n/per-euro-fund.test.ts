import { describe, expect, it } from 'vitest'
import de from './locales/de.json'
import en from './locales/en.json'
import es from './locales/es.json'
import fr from './locales/fr.json'

// A PER euro-fund sleeve is not available cash in a securities account.
describe('PER euro-fund pocket wording', () => {
  it.each(Object.entries({ fr, en, de, es }))('%s distinguishes the euro fund from spendable cash', (_, locale) => {
    const accounts = locale.accounts as Record<string, unknown>
    expect(accounts.perEuroFundBalance).toBeTypeOf('string')
    expect(accounts.perEuroFundBalance).not.toEqual(accounts.cashBalance)
  })
})
