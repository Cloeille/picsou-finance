import { expect, it, vi } from 'vitest'
import { DEFAULT_LOCALE } from './locales'

const deBundle = vi.hoisted(() => ({ failing: true }))

vi.mock('./locales/de.json', async (importOriginal) => {
  if (deBundle.failing) throw new Error('chunk load failed')
  return importOriginal()
})

vi.stubGlobal('localStorage', {
  getItem: (key: string) => (key === 'picsou-locale' ? DEFAULT_LOCALE.code : null),
  setItem: () => {},
  removeItem: () => {},
})

const { default: i18n, i18nReady } = await import('./index')

it('keeps rendering French, and formatting as French, when a bundle fails to load', async () => {
  await i18nReady

  await i18n.changeLanguage('de')

  expect(i18n.language).toBe('de')
  expect(i18n.resolvedLanguage).toBe('fr')
  expect(document.documentElement.lang).toBe('fr')
})
