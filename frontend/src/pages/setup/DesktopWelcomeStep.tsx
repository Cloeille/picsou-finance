import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useTranslation } from 'react-i18next'
import { CircleNotch } from '@phosphor-icons/react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { AlertBanner } from '@/components/shared/AlertBanner'
import { useDesktopQuickStart } from '@/features/setup/hooks'
import { formatApiError } from '@/lib/errors'

/**
 * 1-step local onboarding for Desktop Mode ("Le Coffre Lumineux"):
 * calm flat hairline card, optional name input, single primary action.
 */
export function DesktopWelcomeStep() {
  const { t } = useTranslation()
  const navigate = useNavigate()
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
    <div className="mx-auto w-full max-w-md">
      <Card className="rounded-xl border border-border/60 py-7 ring-0 sm:py-8">
        <CardHeader className="items-center gap-2 px-6 text-center sm:px-8">
          <CardTitle className="text-2xl font-bold tracking-tight text-foreground sm:text-3xl">
            {t('desktop.onboarding.title')}
          </CardTitle>
          <CardDescription className="text-sm leading-relaxed">
            {t('desktop.onboarding.subtitle')}
          </CardDescription>
        </CardHeader>

        <CardContent className="px-6 sm:px-8">
          <form onSubmit={handleSubmit} className="space-y-5">
            <div className="space-y-2">
              <Label htmlFor="desktop-display-name" className="text-sm font-medium">
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

            <p className="text-center text-xs leading-relaxed text-muted-foreground">
              {t('desktop.onboarding.lockHint')}
            </p>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
