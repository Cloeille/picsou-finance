import { useTranslation } from 'react-i18next'
import { cn } from '@/lib/utils'

interface LanguageToggleProps {
  className?: string
}

/**
 * Compact FR / EN toggle that persists the choice via i18next-browser-languagedetector
 * (localStorage key: `picsou-locale`, set by i18n.changeLanguage).
 *
 * Mirrors the ToggleGroup style used in SettingsPage > Appearance so both
 * locations feel consistent.
 */
export function LanguageToggle({ className }: LanguageToggleProps) {
  const { i18n, t } = useTranslation()
  const isEn = i18n.language.startsWith('en')

  return (
    <div
      role="group"
      aria-label={t('nav.switchLanguage')}
      className={cn('inline-flex items-center rounded-lg bg-muted p-1', className)}
    >
      <button
        type="button"
        onClick={() => i18n.changeLanguage('fr')}
        aria-pressed={!isEn}
        className={cn(
          'rounded-md px-2.5 py-1 text-xs font-medium transition-colors',
          !isEn
            ? 'bg-primary text-primary-foreground shadow-sm'
            : 'text-muted-foreground hover:text-foreground',
        )}
      >
        FR
      </button>
      <button
        type="button"
        onClick={() => i18n.changeLanguage('en')}
        aria-pressed={isEn}
        className={cn(
          'rounded-md px-2.5 py-1 text-xs font-medium transition-colors',
          isEn
            ? 'bg-primary text-primary-foreground shadow-sm'
            : 'text-muted-foreground hover:text-foreground',
        )}
      >
        EN
      </button>
    </div>
  )
}
