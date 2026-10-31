import { describe, expect, it } from 'vitest'
import de from './locales/de.json'
import en from './locales/en.json'
import es from './locales/es.json'
import fr from './locales/fr.json'

// The expense KPI counts spending only; the flow list under it also holds savings, transfers
// and money left unspent, so the two must never read as the same total.
describe('flow outflows wording', () => {
  it.each(Object.entries({ fr, en, de, es }))('%s gives the income allocation its own title', (_, locale) => {
    const { cashflow, flow } = locale.budget
    expect(flow.outflows).not.toEqual(cashflow.expense)
    expect(flow.outflows).not.toEqual(flow.breakdown)
  })
})
