import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { EBSubstepShell } from './EBSubstepShell'
import {
  enableBankingConfigSchema,
  type EnableBankingConfigFormValues,
} from '@/features/setup/schemas'
import { useSetupFlowStore } from '@/stores/setup-flow-store'
import { useWriteEnableBankingConfig } from '@/features/setup/hooks'
import { formatApiError } from '@/lib/errors'

interface Props {
  onNext: () => void
  onBack: () => void
}

/**
 * Credentials step (Application ID only). Per Enable Banking's spec the JWT
 * `kid` is the application id itself, so we collect a single value and the
 * backend derives the Key ID from it. The redirect URI is shown to the user in
 * the previous substep so they can register it in their EB dashboard before
 * generating credentials; we read it back from the draft (or recompute from
 * window.location as a fallback) and ship the config to the backend here.
 */
export function EBStep2Credentials({ onNext, onBack }: Props) {
  const { t } = useTranslation()
  const draft = useSetupFlowStore((s) => s.ebDraft)
  const updateEbDraft = useSetupFlowStore((s) => s.updateEbDraft)
  const writeConfig = useWriteEnableBankingConfig()

  const [serverError, setServerError] = useState<string | null>(null)
  const [prodAcknowledged, setProdAcknowledged] = useState(false)

  const defaultRedirect =
    typeof window !== 'undefined' ? `${window.location.origin}/sync/callback` : ''

  const { register, handleSubmit, formState, setValue } = useForm<EnableBankingConfigFormValues>({
    resolver: zodResolver(enableBankingConfigSchema),
    defaultValues: {
      applicationId: draft.applicationId ?? '',
      redirectUri: draft.redirectUri || defaultRedirect,
    },
    mode: 'onBlur',
  })

  /**
   * Trim clipboard whitespace on paste — users copy the UUID from the EB
   * dashboard, which loves adding a trailing newline. Trimming here (instead
   * of in onChange) avoids eating legitimate typed characters.
   */
  const handlePasteApplicationId = (e: React.ClipboardEvent<HTMLInputElement>) => {
    const pasted = e.clipboardData.getData('text').trim()
    if (pasted) {
      e.preventDefault()
      setValue('applicationId', pasted, { shouldValidate: true })
    }
  }

  useEffect(() => {
    // Pre-populate draft with the autodiscovered redirect URI so the prior step
    // can pick it up without re-computing if the user navigated back.
    if (!draft.redirectUri && defaultRedirect) {
      updateEbDraft({ redirectUri: defaultRedirect })
    }
  }, [draft.redirectUri, defaultRedirect, updateEbDraft])

  const onSubmit = handleSubmit(async (values) => {
    setServerError(null)
    updateEbDraft({
      applicationId: values.applicationId.trim(),
      redirectUri: values.redirectUri,
    })
    try {
      await writeConfig.mutateAsync({
        applicationId: values.applicationId.trim(),
        redirectUri: values.redirectUri,
      })
      onNext()
    } catch (err) {
      setServerError(formatApiError(err, t))
    }
  })

  return (
    <EBSubstepShell current={3} total={5}>
      <form onSubmit={onSubmit} noValidate className="space-y-6">
        <div className="text-center space-y-2">
          <h2 className="text-2xl sm:text-3xl font-semibold tracking-tight">
            {t('setup.enablebanking.creds.title')}
          </h2>
          <p className="mx-auto max-w-md text-sm text-muted-foreground">
            {t('setup.enablebanking.creds.body')}
          </p>
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="eb-app-id">{t('setup.enablebanking.creds.applicationId')}</Label>
          <Input
            id="eb-app-id"
            autoComplete="off"
            spellCheck={false}
            placeholder="00000000-0000-0000-0000-000000000000"
            aria-invalid={!!formState.errors.applicationId}
            onPaste={handlePasteApplicationId}
            {...register('applicationId')}
          />
          {formState.errors.applicationId ? (
            <p className="text-xs text-destructive">
              {t(formState.errors.applicationId.message ?? 'setup.enablebanking.appIdFormat')}
            </p>
          ) : (
            <p className="text-xs text-muted-foreground">
              {t('setup.enablebanking.creds.applicationIdHint')}
            </p>
          )}
        </div>

        {/* Hidden redirectUri — validated but not user-editable here; the
            prior substep displays it for the user to register in EB. */}
        <input type="hidden" {...register('redirectUri')} />

        <label className="flex items-start gap-3 rounded-xl border border-border/60 bg-muted/30 p-3 text-left cursor-pointer">
          <input
            type="checkbox"
            checked={prodAcknowledged}
            onChange={(e) => setProdAcknowledged(e.target.checked)}
            className="mt-0.5 h-4 w-4 flex-shrink-0 cursor-pointer rounded border-border accent-primary"
            aria-describedby="eb-prod-ack-desc"
          />
          <span id="eb-prod-ack-desc" className="text-xs sm:text-sm">
            {t('setup.enablebanking.creds.prodAcknowledge')}
          </span>
        </label>

        {serverError && (
          <p role="alert" className="text-sm text-destructive">
            {serverError}
          </p>
        )}

        <div className="flex flex-col gap-2 sm:flex-row sm:justify-between">
          <Button
            type="button"
            variant="ghost"
            onClick={onBack}
            className="w-full sm:w-auto"
          >
            {t('setup.enablebanking.back')}
          </Button>
          <Button
            type="submit"
            disabled={writeConfig.isPending || !formState.isValid || !prodAcknowledged}
            className="w-full rounded-full sm:w-auto"
          >
            {t('setup.enablebanking.continue')}
          </Button>
        </div>
      </form>
    </EBSubstepShell>
  )
}
