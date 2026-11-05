import { describe, expect, it, vi } from 'vitest'
import { DEFAULT_LOCALE, SUPPORTED_LOCALES } from './locales'

// Language detection reads localStorage first and navigator.language (en-US under jsdom) next.
// Pin it to French before the module loads, so which bundles are present at boot is not left to
// the test environment.
vi.stubGlobal('localStorage', {
  getItem: (key: string) => (key === 'picsou-locale' ? DEFAULT_LOCALE.code : null),
  setItem: () => {},
  removeItem: () => {},
})

const { default: i18n, i18nReady } = await import('./index')
const { default: de } = await import('./locales/de.json')

await i18nReady
const loadedAtBoot = SUPPORTED_LOCALES.filter((l) => i18n.hasResourceBundle(l.code, 'translation')).map(
  (l) => l.code,
)

describe('lazy locale bundles', () => {
  it('ships only French up front', () => {
    expect(loadedAtBoot).toEqual([DEFAULT_LOCALE.code])
  })

  it('switches only once the bundle is loaded, so no raw key renders', async () => {
    const seen: string[] = []
    const onChange = () => seen.push(i18n.t('nav.dashboard'))
    i18n.on('languageChanged', onChange)
    await i18n.changeLanguage('de')
    i18n.off('languageChanged', onChange)

    expect(seen).toEqual([de.nav.dashboard])
    expect(i18n.resolvedLanguage).toBe('de')
    expect(document.documentElement.lang).toBe('de')
  })
})
