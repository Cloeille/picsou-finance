import { describe, expect, it } from 'vitest'
import de from './locales/de.json'
import en from './locales/en.json'
import es from './locales/es.json'
import fr from './locales/fr.json'

// An envelope's status line is the figure the budget tab is read for, so it must not fall back
// to English wording in another language.
describe('envelope status copy', () => {
  it.each(Object.entries({ fr, de, es }))('%s translates the remaining and overspent lines', (_lang, locale) => {
    for (const key of ['remaining', 'overspent'] as const) {
      const text = locale.budget.envelope[key]
      expect(text).not.toBe(en.budget.envelope[key])
      expect(text).toContain('{{amount}}')
      expect(text).toContain('{{percent}}')
    }
  })
})
