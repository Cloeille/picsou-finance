import i18next from 'i18next'
import { describe, expect, it } from 'vitest'
import de from './locales/de.json'
import en from './locales/en.json'
import es from './locales/es.json'
import fr from './locales/fr.json'

const resources = { fr, en, de, es }

async function translatorFor(lng: keyof typeof resources) {
  const instance = i18next.createInstance()
  await instance.init({ lng, resources: { [lng]: { translation: resources[lng] } } })
  return instance.t
}

describe('translations', () => {
  it.each([
    ['fr', '1 autre objectif', '2 autres objectifs'],
    ['en', '1 other goal', '2 other goals'],
    ['de', '1 weiteres Ziel', '2 weitere Ziele'],
    ['es', '1 objetivo más', '2 objetivos más'],
  ] as const)('%s pluralizes the remaining goals count', async (lng, one, other) => {
    const t = await translatorFor(lng)
    expect(t('dashboard.otherGoals', { count: 1 })).toBe(one)
    expect(t('dashboard.otherGoals', { count: 2 })).toBe(other)
  })

  it('puts a no-break space before the colon of a French label only', async () => {
    const label = 'Objectif mensuel'
    expect((await translatorFor('fr'))('common.labelColon', { label })).toBe('Objectif mensuel\u00a0:')
    expect((await translatorFor('en'))('common.labelColon', { label })).toBe('Objectif mensuel:')
  })

  it.each([
    ['de', 'Automatisch bestätigt · 95 % Sicherheit', 'KI: Lebensmittel · 80 %', 'Jahreszins (%)', 'Quellensteuer (%)'],
    ['es', 'Confirmado automáticamente · 95 % de confianza', 'IA: Lebensmittel · 80 %', 'Tipo anual (%)', 'Retención fiscal (%)'],
  ] as const)('%s no longer shows the English subscription, AI and savings labels', async (lng, auto, ai, rate, tax) => {
    const t = await translatorFor(lng)
    expect(t('budget.recurring.autoConfirmedConfident', { percent: '95 %' })).toBe(auto)
    // The same badge without a confidence figure.
    expect(auto.startsWith(t('budget.recurring.activity.autoConfirmed'))).toBe(true)
    expect(t('budget.categorize.aiSuggested', { name: 'Lebensmittel', confidence: '80 %' })).toBe(ai)
    expect(t('savings.annualRate')).toBe(rate)
    expect(t('savings.taxRate')).toBe(tax)
  })

  it('addresses the French member as "vous" and names crypto platforms one way', async () => {
    const t = await translatorFor('fr')
    expect(t('budget.settings.aiHint')).toMatch(/^Laissez un modèle /)
    expect(t('setup.integrations.cards.crypto.title')).toBe(t('sync.exchanges.title'))
  })
})
