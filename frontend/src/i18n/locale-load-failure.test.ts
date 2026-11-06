import { expect, it, vi } from 'vitest'
import { DEFAULT_LOCALE } from './locales'

const deBundle = vi.hoisted(() => ({ failing: true, imports: 0 }))

vi.mock('./locales/de.json', async (importOriginal) => {
  deBundle.imports++
  if (deBundle.failing) throw new Error('chunk load failed')
  return importOriginal()
})

vi.stubGlobal('localStorage', {
  getItem: (key: string) => (key === 'picsou-locale' ? DEFAULT_LOCALE.code : null),
  setItem: () => {},
  removeItem: () => {},
})

const { default: i18n, i18nReady } = await import('./index')
const { default: de } = await vi.importActual<{ default: { nav: { dashboard: string } } }>('./locales/de.json')

it('falls back to French when a bundle fails, and loads it on a later language change', async () => {
  await i18nReady

  await i18n.changeLanguage('de')

  expect(deBundle.imports).toBe(2) // the import and its single retry
  expect(i18n.language).toBe('de')
  expect(i18n.resolvedLanguage).toBe('fr')
  expect(document.documentElement.lang).toBe('fr')

  deBundle.failing = false
  await i18n.changeLanguage('de')

  expect(i18n.resolvedLanguage).toBe('de')
  expect(document.documentElement.lang).toBe('de')
  expect(i18n.t('nav.dashboard')).toBe(de.nav.dashboard)
})
