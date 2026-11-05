import { beforeEach, describe, expect, it } from 'vitest'
import i18n, { i18nReady } from './index'
import de from './locales/de.json'

describe('lazy locale bundles', () => {
  beforeEach(async () => {
    await i18nReady
    await i18n.changeLanguage('fr')
  })

  it('ships only French up front', () => {
    expect(i18n.hasResourceBundle('fr', 'translation')).toBe(true)
    expect(i18n.hasResourceBundle('es', 'translation')).toBe(false)
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
