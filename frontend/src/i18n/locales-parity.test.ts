import { describe, expect, it } from 'vitest'
import de from './locales/de.json'
import en from './locales/en.json'
import es from './locales/es.json'
import fr from './locales/fr.json'

type TranslationNode = string | TranslationNode[] | { [key: string]: TranslationNode }

function flattenTranslations(tree: TranslationNode, prefix = ''): Map<string, string> {
  const flattened = new Map<string, string>()
  if (typeof tree === 'string') {
    flattened.set(prefix, tree)
    return flattened
  }

  if (Array.isArray(tree)) {
    tree.forEach((value, index) => {
      flattenTranslations(value, `${prefix}.${index}`).forEach((translation, nestedPath) => {
        flattened.set(nestedPath, translation)
      })
    })
    return flattened
  }

  for (const [key, value] of Object.entries(tree)) {
    const path = prefix ? `${prefix}.${key}` : key
    flattenTranslations(value, path).forEach((translation, nestedPath) => {
      flattened.set(nestedPath, translation)
    })
  }
  return flattened
}

function placeholders(translation: string): string[] {
  return [...translation.matchAll(/{{\s*([^},\s]+)[^}]*}}/g)]
    .map((match) => match[1])
    .sort()
}

describe('locale parity', () => {
  const locales = { fr, de, es } satisfies Record<string, TranslationNode>
  const reference = flattenTranslations(en)
  const referenceKeys = [...reference.keys()].sort()

  it.each(Object.entries(locales))('%s has the same keys and placeholders as English', (_, locale) => {
    const translations = flattenTranslations(locale)
    expect([...translations.keys()].sort()).toEqual(referenceKeys)

    for (const [key, englishTranslation] of reference) {
      expect(placeholders(translations.get(key) ?? '')).toEqual(placeholders(englishTranslation))
    }
  })
})
