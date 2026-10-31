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
})
