import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { Button } from '@/components/ui/button'
import { HelloGreeting } from './HelloGreeting'
import { useSetupStatus } from '@/features/setup/hooks'

/**
 * Step 0 of the wizard — the signature moment. After the Hello greeting
 * cycles through 12 locales, the welcome hero fades in with the single
 * "Get started" CTA.
 */
export function SetupStepIntro() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [phase, setPhase] = useState<'greeting' | 'content'>('greeting')
  const { data: setupStatus } = useSetupStatus()

  return (
    <div className="relative">
      {phase === 'greeting' ? (
        <>
          <HelloGreeting onFinish={() => setPhase('content')} />
          <div className="mt-2 flex justify-center">
            <button
              type="button"
              onClick={() => setPhase('content')}
              aria-label={t('setup.intro.skipAria')}
              className="rounded-lg px-3 py-1.5 text-sm font-medium text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
            >
              {t('setup.intro.skip')}
            </button>
          </div>
        </>
      ) : (
        <div className="text-center space-y-6 animate-hello-in">
          <div className="space-y-3">
            <h1 className="text-4xl sm:text-5xl font-semibold tracking-tight leading-[1.08]">
              {t('setup.intro.title')}
            </h1>
            <p className="mx-auto max-w-lg text-base sm:text-lg leading-relaxed text-muted-foreground">
              {t('setup.intro.subtitle')}
            </p>
          </div>
          <div className="pt-2">
            <Button
              onClick={() =>
                navigate(
                  setupStatus?.state === 'IN_PROGRESS' ? '/setup/security' : '/setup/admin'
                )
              }
              className="h-10 min-w-44 rounded-lg px-6 text-sm font-medium"
            >
              {t('setup.intro.cta')}
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}
