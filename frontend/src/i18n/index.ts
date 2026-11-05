import i18n, { type BackendModule, type ResourceKey } from 'i18next'
import { initReactI18next } from 'react-i18next'
import LanguageDetector from 'i18next-browser-languagedetector'
import { DEFAULT_LOCALE, SUPPORTED_LOCALES, resolveLocale } from './locales'
import fr from './locales/fr.json'

// Only the default (and fallback) language ships in the entry chunk. The others are separate
// chunks fetched on demand. i18next emits `languageChanged` only after the bundle loaded, so a
// switch never renders raw keys.
const lazyTranslations: Record<string, () => Promise<{ default: ResourceKey }>> = {
  en: () => import('./locales/en.json'),
  de: () => import('./locales/de.json'),
  es: () => import('./locales/es.json'),
}

const lazyLocaleBackend: BackendModule = {
  type: 'backend',
  init: () => {},
  read: (language, _namespace, callback) => {
    const load = lazyTranslations[language]
    if (!load) {
      callback(new Error(`No translation bundle for "${language}"`), false)
      return
    }
    load().then(
      (bundle) => callback(null, bundle.default),
      (error: Error) => callback(error, false),
    )
  },
}

// Mirror the language actually rendered into <html lang>: accessibility, and lib/utils.ts
// getLocale() reads it to pick the Intl locale for dates/numbers. `language` is the requested
// one, `resolvedLanguage` the one whose strings loaded. When a bundle failed the UI shows
// French, so dates and numbers must format as French too.
function syncDocumentLanguage() {
  if (typeof document === 'undefined') return
  document.documentElement.lang = resolveLocale(i18n.resolvedLanguage ?? i18n.language).code
}

/** Resolves once the detected language's bundle is loaded. main.tsx renders after it. */
export const i18nReady = i18n
  .use(LanguageDetector)
  .use(lazyLocaleBackend)
  .use(initReactI18next)
  .init({
    resources: { [DEFAULT_LOCALE.code]: { translation: fr } },
    partialBundledLanguages: true,
    fallbackLng: DEFAULT_LOCALE.code,
    supportedLngs: SUPPORTED_LOCALES.map((l) => l.code),
    ns: ['translation'],
    defaultNS: 'translation',
    interpolation: {
      escapeValue: false,
    },
    detection: {
      order: ['localStorage', 'navigator'],
      caches: ['localStorage'],
      lookupLocalStorage: 'picsou-locale',
    },
  })
  .then(syncDocumentLanguage)

i18n.on('languageChanged', syncDocumentLanguage)

export default i18n
