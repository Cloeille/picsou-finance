import { describe, expect, it } from 'vitest'
import de from './locales/de.json'
import en from './locales/en.json'
import es from './locales/es.json'
import fr from './locales/fr.json'

// The La Banque Postale connector imports the current account and the savings
// books (Livret A, LDDS, LEP, Compte sur livret) only: the short copy a user
// reads before connecting names Livret A and LEP, while the sync scope names
// the full list. Neither promises PEL, PER, loans or cards.
const loanWord = { fr: /prêt/i, en: /loan/i, de: /kredit/i, es: /préstamo/i }
const cardWord = { fr: /carte/i, en: /card/i, de: /karte/i, es: /tarjeta/i }
// Pinned word-for-word: the "nothing else" in the test names below is only as
// strong as this list (a copy change must update it here too).
const shortCopy: Record<string, string> = {
  fr: 'Compte courant, Livret A et LEP à La Banque Postale',
  en: 'Current account, Livret A and LEP at La Banque Postale',
  de: 'Girokonto, Livret A und LEP bei La Banque Postale',
  es: 'Cuenta corriente, Livret A y LEP en La Banque Postale',
}

describe('La Banque Postale scope copy', () => {
  it.each(Object.entries({ fr, en, de, es }))(
    '%s short copy names Livret A and LEP, nothing else',
    (lang, locale) => {
      // Exact wording: contains/not-contains alone let "LEP et livrets" pass.
      expect(locale.addAccount.desc.lbp).toBe(shortCopy[lang])
      const texts = [locale.addAccount.desc.lbp, locale.setup.integrations.cards.lbp.description]
      for (const text of texts) {
        expect(text).toContain('Livret A')
        expect(text).toContain('LEP')
        expect(text).not.toContain('LDDS')
        expect(text).not.toMatch(/\bPEL\b/)
        expect(text).not.toMatch(/\bPER\b/)
        expect(text).not.toMatch(loanWord[lang as keyof typeof loanWord])
        expect(text).not.toMatch(cardWord[lang as keyof typeof cardWord])
      }
    },
  )

  it.each(Object.entries({ fr, en, de, es }))(
    '%s sync scope names Livret A, LDDS, LEP and Compte sur livret, not PEL, PER, loans or cards',
    (lang, locale) => {
      const text = locale.sync.lbp.scope
      expect(text).toContain('Livret A')
      expect(text).toContain('LDDS')
      expect(text).toContain('LEP')
      expect(text).toContain('Compte sur livret')
      expect(text).not.toMatch(/\bPEL\b/)
      expect(text).not.toMatch(/\bPER\b/)
      expect(text).not.toMatch(loanWord[lang as keyof typeof loanWord])
      expect(text).not.toMatch(cardWord[lang as keyof typeof cardWord])
    },
  )
})
