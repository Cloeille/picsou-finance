import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AlertTriangle, Info, Landmark, Lock } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { EBSubstepShell } from './EBSubstepShell'

interface Props {
  onNext: () => void
}

export function EBStep1Explain({ onNext }: Props) {
  const { t } = useTranslation()
  const [waitingOnSignup, setWaitingOnSignup] = useState(false)
  const [psd2Open, setPsd2Open] = useState(false)

  const handleHaveAccount = () => onNext()

  const handleCreateAccount = () => {
    window.open('https://enablebanking.com/sign-in/', '_blank', 'noopener,noreferrer')
    setWaitingOnSignup(true)
  }

  return (
    <EBSubstepShell current={1} total={5}>
      <div className="space-y-6">
        <div className="flex items-center justify-center gap-3 text-primary">
          <span className="rounded-xl bg-primary/10 p-3">
            <Landmark className="h-6 w-6" />
          </span>
          <span className="text-muted-foreground" aria-hidden="true">+</span>
          <span className="rounded-xl bg-primary/10 p-3">
            <Lock className="h-6 w-6" />
          </span>
        </div>

        <div className="text-center space-y-2">
          <h2 className="text-2xl sm:text-3xl font-semibold tracking-tight">
            {t('setup.enablebanking.explain.title')}
          </h2>
          <p className="mx-auto max-w-md text-sm text-muted-foreground">
            {t('setup.enablebanking.explain.body')}
          </p>
        </div>

        <div className="flex justify-center">
          <button
            type="button"
            onClick={() => setPsd2Open((v) => !v)}
            aria-expanded={psd2Open}
            className="inline-flex items-center gap-1 text-xs text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
          >
            <Info className="h-3.5 w-3.5" />
            {t('setup.enablebanking.explain.psd2Popover')}
          </button>
        </div>

        {psd2Open && (
          <div className="rounded-xl border border-border/60 bg-muted/40 p-4 text-xs text-muted-foreground">
            {t('setup.enablebanking.explain.psd2Body')}
          </div>
        )}

        <div className="flex items-start gap-3 rounded-xl border border-amber-500/40 bg-amber-500/10 p-4 text-left">
          <AlertTriangle className="mt-0.5 h-4 w-4 flex-shrink-0 text-amber-600 dark:text-amber-400" />
          <div className="space-y-1 text-xs sm:text-sm">
            <p className="font-medium text-amber-900 dark:text-amber-100">
              {t('setup.enablebanking.explain.prodWarningTitle')}
            </p>
            <p className="text-amber-900/80 dark:text-amber-100/80">
              {t('setup.enablebanking.explain.prodWarningBody')}
            </p>
          </div>
        </div>

        <div className="flex items-start gap-3 rounded-xl border border-border/60 bg-muted/40 p-4 text-left">
          <Info className="mt-0.5 h-4 w-4 flex-shrink-0 text-muted-foreground" />
          <p className="text-xs sm:text-sm text-muted-foreground">
            {t('setup.enablebanking.explain.psd2ScopeNote')}
          </p>
        </div>

        {!waitingOnSignup ? (
          <div className="mx-auto flex w-full max-w-lg flex-col gap-3">
            <Button
              variant="outline"
              onClick={handleCreateAccount}
              className="w-full rounded-full"
            >
              {t('setup.enablebanking.explain.needAccount')}
            </Button>
            <Button
              onClick={handleHaveAccount}
              className="w-full rounded-full"
            >
              {t('setup.enablebanking.explain.haveAccount')}
            </Button>
          </div>
        ) : (
          <div className="space-y-3 rounded-xl border border-border/60 bg-card p-4 text-center">
            <p className="text-sm text-muted-foreground">
              {t('setup.enablebanking.explain.signupOpened')}
            </p>
            <p className="text-sm text-muted-foreground">
              {t('setup.enablebanking.explain.magicLinkHint')}
            </p>
            <Button
              onClick={onNext}
              className="w-full rounded-full"
            >
              {t('setup.enablebanking.explain.continueAfterSignup')}
            </Button>
          </div>
        )}
      </div>
    </EBSubstepShell>
  )
}
