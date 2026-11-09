import { describe, expect, it } from 'vitest'
import de from './locales/de.json'
import en from './locales/en.json'
import es from './locales/es.json'
import fr from './locales/fr.json'

// The Banque Populaire connector imports the current account, Livret A, Livret Jeune and LDDS
// only: loans and PER are listed as unsupported and cards are not imported, so the copy read
// before connecting must name the imported products and promise none of the others.
const loanWord = { fr: /prêt/i, en: /loan/i, de: /kredit/i, es: /préstamo/i }
const cardWord = { fr: /carte/i, en: /card/i, de: /karte/i, es: /tarjeta/i }

describe('Banque Populaire scope copy', () => {
  it.each(Object.entries({ fr, en, de, es }))('%s names Livret A, Livret Jeune and LDDS, not PER, loans or cards', (lang, locale) => {
    const texts = [locale.addAccount.desc.banquePopulaire, locale.sync.banquePopulaire.scope]
    for (const text of texts) {
      expect(text).toContain('Banque Populaire')
      expect(text).toContain('Livret A')
      expect(text).toContain('Livret Jeune')
      expect(text).toContain('LDDS')
      expect(text).not.toMatch(/\bPER\b/)
      expect(text).not.toMatch(loanWord[lang as keyof typeof loanWord])
      expect(text).not.toMatch(cardWord[lang as keyof typeof cardWord])
    }
  })
})
