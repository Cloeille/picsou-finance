import { Outlet, useLocation } from 'react-router'
import { useTranslation } from 'react-i18next'
import { useEffect, useMemo } from 'react'
import { Progress } from '@/components/ui/progress'
import { cn } from '@/lib/utils'
import { SUPPORTED_LOCALES, resolveLocale } from '@/i18n/locales'

/**
 * Shell for every setup route. Intentionally minimal — Apple's setup
 * assistants let the content breathe: one top progress strip, a compact
 * language switcher, one "Step N of M" pill, and negative space.
 *
 * The step counter comes from URL path, not a nav state, so a browser
 * refresh keeps the right index visible.
 */

const MAIN_STEPS: { path: string; i18nKey: string }[] = [
  { path: '/setup', i18nKey: 'setup.steps.hello' },
  { path: '/setup/admin', i18nKey: 'setup.steps.admin' },
  { path: '/setup/security', i18nKey: 'setup.steps.security' },
  { path: '/setup/integrations', i18nKey: 'setup.steps.integrations' },
  { path: '/setup/done', i18nKey: 'setup.steps.done' },
]

export function SetupLayout() {
  const { t, i18n } = useTranslation()
  const location = useLocation()

  const { index, total, label } = useMemo(() => {
    // Pick the longest-prefix match so sub-routes like /setup/integrations/enablebanking
    // resolve to the Integrations step, not Hello.
    const sorted = [...MAIN_STEPS].sort((a, b) => b.path.length - a.path.length)
    const match = sorted.find(s => location.pathname.startsWith(s.path))
      ?? MAIN_STEPS[0]
    const idx = MAIN_STEPS.findIndex(s => s.path === match.path)
    return { index: idx + 1, total: MAIN_STEPS.length, label: t(match.i18nKey) }
  }, [location.pathname, t])

  useEffect(() => {
    // Preload the Homemade Apple font only on setup routes — dashboard
    // users pay zero font-cost for the wizard's typography.
    const id = 'picsou-setup-font-preload'
    if (document.getElementById(id)) return
    const link = document.createElement('link')
    link.id = id
    link.rel = 'preload'
    link.as = 'font'
    link.type = 'font/woff2'
    link.crossOrigin = 'anonymous'
    link.href = '/fonts/HomemadeApple-Regular.woff2'
    document.head.appendChild(link)
  }, [])

  const progressPct = Math.round((index / total) * 100)
  const activeLanguage = resolveLocale(i18n.language).code
  const switchLang = (lng: string) => {
    i18n.changeLanguage(lng)
  }

  return (
    <div className="relative min-h-dvh bg-background setup-gradient">
      <Progress
        value={progressPct}
        aria-label={t('setup.progress.bar', { current: index, total })}
        className="fixed inset-x-0 top-0 z-50 h-1 rounded-none"
      />

      <header className="fixed left-4 right-4 top-4 z-40 flex items-center justify-between gap-3 sm:left-6 sm:right-6 sm:top-6">
        <div
          role="radiogroup"
          aria-label={t('setup.intro.language')}
          className="inline-flex min-h-12 shrink-0 items-center rounded-2xl border border-border/60 bg-background/80 p-1 backdrop-blur-md"
        >
          {SUPPORTED_LOCALES.map((locale) => (
            <button
              key={locale.code}
              role="radio"
              type="button"
              aria-checked={activeLanguage === locale.code}
              onClick={() => switchLang(locale.code)}
              className={cn(
                'inline-flex h-10 min-w-12 items-center justify-center rounded-xl px-4 text-sm font-medium transition-[background-color,color] sm:min-w-14 sm:px-5',
                activeLanguage === locale.code
                  ? 'bg-primary text-primary-foreground'
                  : 'text-muted-foreground hover:text-foreground'
              )}
            >
              {locale.label}
            </button>
          ))}
        </div>

        <span
          className={cn(
            'inline-flex h-10 min-w-0 items-center rounded-full border border-border/60',
            'bg-background/80 px-4 text-xs font-medium text-muted-foreground',
            'backdrop-blur-md'
          )}
          aria-live="polite"
        >
          <span className="truncate">
            {t('setup.progress.label', { current: index, total })} — {label}
          </span>
        </span>
      </header>

      <main
        id="setup-main"
        className="mx-auto flex min-h-dvh w-full max-w-xl flex-col justify-center px-5 py-20 sm:max-w-2xl sm:px-8 sm:py-24"
      >
        <a
          href="#setup-main"
          className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-20 focus:z-[60] focus:inline-flex focus:h-10 focus:items-center focus:rounded-full focus:bg-primary focus:px-6 focus:text-sm focus:font-medium focus:text-primary-foreground"
        >
          {t('setup.a11y.skipToContent')}
        </a>

        <div
          key={location.pathname}
          className="w-full animate-setup-slide-in"
        >
          <Outlet />
        </div>
      </main>
    </div>
  )
}
