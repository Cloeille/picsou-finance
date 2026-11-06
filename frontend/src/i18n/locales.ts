/**
 * Single source of truth for the languages the UI supports.
 *
 * Adding a language = add a `locales/<code>.json` translation file, add its
 * dynamic import to `lazyTranslations` in `i18n/index.ts`, and append an entry here. Every language selector
 * (setup wizard, settings page, sidebar menu) and the `Intl` date/number
 * formatting in `lib/utils.ts` derive from this list.
 */
export interface AppLocale {
  /** i18next language code — also the translation file name. */
  code: string
  /** Short label shown in compact selectors (toggle pills). */
  label: string
  /** Native language name, for menus and accessibility. */
  nativeName: string
  /** BCP 47 locale fed to `Intl.*` for date/number/currency formatting. */
  intlLocale: string
}

export const SUPPORTED_LOCALES: AppLocale[] = [
  { code: 'fr', label: 'FR', nativeName: 'Français', intlLocale: 'fr-FR' },
  { code: 'en', label: 'EN', nativeName: 'English', intlLocale: 'en-US' },
  { code: 'de', label: 'DE', nativeName: 'Deutsch', intlLocale: 'de-DE' },
  { code: 'es', label: 'ES', nativeName: 'Español', intlLocale: 'es-ES' },
]

export const DEFAULT_LOCALE = SUPPORTED_LOCALES[0]

/**
 * Match a raw language tag (i18next code, `navigator.language`, `<html lang>`…)
 * to a supported locale by prefix, falling back to the app default. Handles
 * regional variants like "fr-CA" or "de-AT".
 */
export function resolveLocale(lang: string | null | undefined): AppLocale {
  if (!lang) return DEFAULT_LOCALE
  return (
    SUPPORTED_LOCALES.find((l) => lang === l.code || lang.startsWith(`${l.code}-`)) ??
    DEFAULT_LOCALE
  )
}
