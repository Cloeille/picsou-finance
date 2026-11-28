import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useTranslation } from 'react-i18next'
import { CircleNotch } from '@phosphor-icons/react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { AlertBanner } from '@/components/shared/AlertBanner'
import { ImportArchiveEntry } from './components/ImportArchiveEntry'
import { useDesktopQuickStart } from '@/features/setup/hooks'
import { formatApiError } from '@/lib/errors'
import { useAppStore } from '@/stores/app-store'

interface DesktopWelcomeStepProps {
  legacyPostgresDetected?: boolean
}

/**
 * 1-step local onboarding for Desktop Mode: frameless layout matching
 * SetupStepIntro and SetupStepAdmin directly on the clean canvas.
 */
export function DesktopWelcomeStep({ legacyPostgresDetected }: DesktopWelcomeStepProps = {}) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const desktopMode = useAppStore((s) => s.desktopMode)
  const storeLegacyPostgresDetected = useAppStore((s) => s.legacyPostgresDetected)
  const showLegacyPostgresNotice = desktopMode && (legacyPostgresDetected ?? storeLegacyPostgresDetected)
  const [displayName, setDisplayName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const quickStart = useDesktopQuickStart()

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      await quickStart.mutateAsync({
        displayName: displayName.trim() || undefined,
      })
      navigate('/', { replace: true })
    } catch (err: unknown) {
      setError(formatApiError(err, t, 'desktop.onboarding.error'))
    }
  }

  return (
    <div className="space-y-8 animate-hello-in">
      <div className="space-y-2 text-center">
        <h1 className="text-3xl font-semibold tracking-tight text-foreground sm:text-4xl">
          {t('desktop.onboarding.title')}
        </h1>
        <p className="mx-auto max-w-md text-sm leading-relaxed text-muted-foreground">
          {t('desktop.onboarding.subtitle')}
        </p>
      </div>

      {showLegacyPostgresNotice && (
        <div
          role="status"
          className="mx-auto w-full max-w-md space-y-1.5 rounded-xl bg-muted/50 p-4 text-left ring-1 ring-foreground/10"
        >
          <p className="text-sm font-semibold text-foreground">
            {t('desktop.onboarding.legacyPostgresTitle')}
          </p>
          <p className="text-sm leading-relaxed text-muted-foreground">
            {t('desktop.onboarding.legacyPostgresBody')}
          </p>
        </div>
      )}

      <form onSubmit={handleSubmit} className="mx-auto w-full max-w-md space-y-5">
        <div className="space-y-2">
          <Label htmlFor="desktop-display-name" className="text-sm font-semibold">
            {t('desktop.onboarding.nameLabel')}
          </Label>
          <Input
            id="desktop-display-name"
            type="text"
            maxLength={80}
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            placeholder={t('desktop.onboarding.namePlaceholder')}
            autoFocus
            disabled={quickStart.isPending}
            className="h-10 rounded-lg px-3.5 text-sm"
          />
        </div>

        {error && (
          <AlertBanner variant="prominent" role="alert">
            {error}
          </AlertBanner>
        )}

        <Button
          type="submit"
          disabled={quickStart.isPending}
          className="h-10 w-full rounded-lg text-sm font-medium"
        >
          {quickStart.isPending && (
            <CircleNotch className="size-4 animate-spin" aria-hidden="true" />
          )}
          {quickStart.isPending
            ? t('desktop.onboarding.starting')
            : t('desktop.onboarding.cta')}
        </Button>
      </form>

      <div className="flex justify-center">
        <ImportArchiveEntry />
      </div>
    </div>
  )
}
