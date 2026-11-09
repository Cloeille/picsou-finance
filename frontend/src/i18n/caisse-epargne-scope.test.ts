import { describe, expect, it } from 'vitest'
import de from './locales/de.json'
import en from './locales/en.json'
import es from './locales/es.json'
import fr from './locales/fr.json'

// The Caisse d'Epargne connector imports LDDS, PER and personal loans as well as the
// current account, Livret A and cards: the copy a user reads before connecting must say so.
const loanWord = { fr: /prêt/i, en: /loan/i, de: /kredit/i, es: /préstamo/i }

describe("Caisse d'Epargne scope copy", () => {
  it.each(Object.entries({ fr, en, de, es }))('%s names LDDS, PER and loans', (lang, locale) => {
    const texts = [locale.addAccount.desc.caisseEpargne, locale.sync.caisseEpargne.scope]
    for (const text of texts) {
      expect(text).toContain('LDDS')
      expect(text).toMatch(/\bPER\b/)
      expect(text).toMatch(loanWord[lang as keyof typeof loanWord])
    }
  })
})
