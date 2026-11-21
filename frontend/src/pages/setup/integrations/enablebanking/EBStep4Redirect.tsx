import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Copy } from '@phosphor-icons/react'
import { Button } from '@/components/ui/button'
import { EBSubstepShell } from './EBSubstepShell'
import { useSetupFlowStore } from '@/stores/setup-flow-store'

interface Props {
  onNext: () => void
  onBack: () => void
}

export function EBStep4Redirect({ onNext, onBack }: Props) {
  const { t } = useTranslation()
  const draft = useSetupFlowStore((s) => s.ebDraft)

  const redirectUri =
    draft.redirectUri ||
    (typeof window !== 'undefined' ? `${window.location.origin}/sync/callback` : '')

  const [copied, setCopied] = useState(false)

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(redirectUri)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      /* noop — user can Ctrl+C from the readonly input */
    }
  }

  return (
    <EBSubstepShell current={2} total={5}>
      <div className="space-y-6">
        <div className="text-center space-y-2">
          <h2 className="text-2xl sm:text-3xl font-semibold tracking-tight">
            {t('setup.enablebanking.redirect.title')}
          </h2>
          <p className="mx-auto max-w-md text-sm text-muted-foreground">
            {t('setup.enablebanking.redirect.body')}
          </p>
        </div>

        <div className="flex flex-col gap-2 sm:flex-row">
          <input
            aria-label={t('setup.enablebanking.redirect.uriLabel')}
            readOnly
            value={redirectUri}
            onFocus={(e) => e.currentTarget.select()}
            className="w-full rounded-lg border border-border/60 bg-muted/30 px-3 py-2 text-sm font-mono text-foreground"
          />
          <Button
            type="button"
            variant="outline"
            onClick={handleCopy}
            className="rounded-lg shrink-0 sm:w-auto"
          >
            {copied ? (
              <>
                <Check className="h-4 w-4" />
                {t('setup.enablebanking.redirect.copied')}
              </>
            ) : (
              <>
                <Copy className="h-4 w-4" />
                {t('setup.enablebanking.redirect.copy')}
              </>
            )}
          </Button>
        </div>

        <div className="space-y-3 rounded-xl border border-border/60 bg-muted/30 p-4 text-sm">
          <h3 className="font-semibold">{t('setup.enablebanking.redirect.checklistTitle')}</h3>
          <ul className="list-disc space-y-2 pl-5 text-muted-foreground">
            <li>{t('setup.enablebanking.redirect.environment')}</li>
            <li>{t('setup.enablebanking.redirect.name')}</li>
            <li>{t('setup.enablebanking.redirect.description')}</li>
            <li>{t('setup.enablebanking.redirect.email')}</li>
            <li>
              {t('setup.enablebanking.redirect.policyUrls')}
              <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1">
                <a className="underline underline-offset-4" href="https://github.com/Cloeille/picsou-finance/blob/main/SECURITY.md" target="_blank" rel="noopener noreferrer">SECURITY.md</a>
                <a className="underline underline-offset-4" href="https://github.com/Cloeille/picsou-finance/blob/main/LICENSE" target="_blank" rel="noopener noreferrer">LICENSE</a>
              </div>
            </li>
            <li>{t('setup.enablebanking.redirect.keyMethod')}</li>
          </ul>
        </div>

        <div className="space-y-2 rounded-xl border border-border/60 bg-muted/30 p-4 text-sm">
          <h3 className="font-semibold">{t('setup.enablebanking.redirect.activationTitle')}</h3>
          <p className="text-muted-foreground">{t('setup.enablebanking.redirect.activationBody')}</p>
          <p className="text-muted-foreground">{t('setup.enablebanking.redirect.restrictedMode')}</p>
        </div>

        <div className="flex flex-col gap-2 sm:flex-row sm:justify-between">
          <Button
            type="button"
            variant="ghost"
            onClick={onBack}
            className="rounded-lg w-full sm:w-auto"
          >
            {t('setup.enablebanking.back')}
          </Button>
          <Button
            onClick={onNext}
            className="w-full rounded-lg sm:w-auto"
          >
            {t('setup.enablebanking.redirect.addedCta')}
          </Button>
        </div>
      </div>
    </EBSubstepShell>
  )
}
