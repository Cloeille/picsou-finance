import { describe, expect, it } from 'vitest'
import de from './locales/de.json'
import en from './locales/en.json'
import es from './locales/es.json'
import fr from './locales/fr.json'

// Enable Banking imports the savings accounts a bank shares (ISO 20022 `SVGS`, such as a passbook)
// next to the current accounts, so the PSD2 scope note on the Banks tab and on step 1
// of the Enable Banking wizard may not say it syncs current accounts only, nor send savings
// accounts to other integrations. Investment products (PEA, life insurance) stay out of scope.
const only = {
  fr: /uniquement|seulement/i,
  en: /\bonly\b/i,
  de: /\bnur\b/i,
  es: /\bsolo\b/i,
}
const savings = { fr: /livrets/, en: /savings accounts/, de: /Sparkonten/, es: /cuentas de ahorro/ }
const notCovered = { fr: /PEA et assurances-vie/, en: /PEA,? (and )?life insurance/, de: /PEA,? (und )?Lebensversicherung/, es: /PEA,? (y )?seguros de vida/ }

describe('Enable Banking PSD2 scope note', () => {
  it.each(Object.entries({ fr, en, de, es }))(
    '%s names savings accounts as synced, and only investment products as left out',
    (lang, locale) => {
      const key = lang as keyof typeof only
      const notes = [locale.sync.banks.psd2ScopeNote, locale.setup.enablebanking.explain.psd2ScopeNote]
      for (const note of notes) {
        expect(note).not.toMatch(only[key])
        expect(note).toMatch(savings[key])
        expect(note).toMatch(notCovered[key])
        // Savings appear before the "for PEA…" sentence, never in the list sent elsewhere.
        expect(note.search(savings[key])).toBeLessThan(note.search(notCovered[key]))
      }
    },
  )
})
