import { describe, expect, it } from 'vitest'
import de from './locales/de.json'
import en from './locales/en.json'
import es from './locales/es.json'
import fr from './locales/fr.json'

// The Banque Populaire connector imports the current account, Livret A, Livret Jeune, LDDS,
// Compte sur Livret, PEL, term deposits, cooperative shares and PER: loans are listed as
// unsupported and cards are not imported, so the copy read before connecting must name the
// imported products and promise none of the others.
const termDepositWord = { fr: /compte[s]? à terme/i, en: /term deposit/i, de: /festgeld/i, es: /depósito[s]? a plazo/i }
const sharesWord = { fr: /parts sociales/i, en: /cooperative shares/i, de: /genossenschaftsanteile/i, es: /participaciones sociales/i }
const loanWord = { fr: /prêt/i, en: /loan/i, de: /kredit/i, es: /préstamo/i }
const cardWord = { fr: /carte/i, en: /card/i, de: /karte/i, es: /tarjeta/i }

describe('Banque Populaire scope copy', () => {
  it.each(Object.entries({ fr, en, de, es }))('%s names Livret A, Livret Jeune, LDDS, Compte sur Livret, PEL, term deposit, cooperative shares and PER, not loans or cards', (lang, locale) => {
    const texts = [locale.addAccount.desc.banquePopulaire, locale.sync.banquePopulaire.scope]
    for (const text of texts) {
      expect(text).toContain('Banque Populaire')
      expect(text).toContain('Livret A')
      expect(text).toContain('Livret Jeune')
      expect(text).toContain('LDDS')
      expect(text).toContain('Compte sur Livret')
      expect(text).toContain('PEL')
      expect(text).toMatch(termDepositWord[lang as keyof typeof termDepositWord])
      expect(text).toMatch(sharesWord[lang as keyof typeof sharesWord])
      expect(text).toMatch(/\bPER\b/)
      expect(text).not.toMatch(loanWord[lang as keyof typeof loanWord])
      expect(text).not.toMatch(cardWord[lang as keyof typeof cardWord])
    }
  })
})
