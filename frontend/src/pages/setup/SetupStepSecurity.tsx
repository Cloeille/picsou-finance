import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { useForm, useFieldArray, Controller, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { PlusCircle, X } from 'lucide-react'
import { formatApiError } from '@/lib/errors'
import { setupSecuritySchema, type SetupSecurityFormValues } from '@/features/setup/schemas'
import { useSubmitSecurity } from '@/features/setup/hooks'

/**
 * Step 2 — security. Pre-populated from the browser's current origin so
 * the typical user doesn't have to edit a thing. We derive
 * {@code secureCookies} from {@code location.protocol === 'https:'} — if
 * the wizard is loaded on http, cookies with the Secure flag silently
 * fail to round-trip, so defaulting to false there saves a debug session.
 */
export function SetupStepSecurity() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const submit = useSubmitSecurity()

  const defaultOrigin = typeof window !== 'undefined' ? window.location.origin : ''
  const defaultSecure = typeof window !== 'undefined' && window.location.protocol === 'https:'

  const { register, handleSubmit, control, formState } = useForm<SetupSecurityFormValues>({
    resolver: zodResolver(setupSecuritySchema),
    defaultValues: {
      allowedOrigins: defaultOrigin ? [defaultOrigin] : [''],
      secureCookies: defaultSecure,
    },
    mode: 'onBlur',
  })

  const { fields, append, remove } = useFieldArray({
    control,
    name: 'allowedOrigins' as never,
  })
  const allowedOrigins = useWatch({ control, name: 'allowedOrigins' })

  const onSubmit = handleSubmit(async (values) => {
    await submit.mutateAsync({
      allowedOrigins: values.allowedOrigins.filter((o) => o.trim().length > 0),
      secureCookies: values.secureCookies,
    })
    navigate('/setup/integrations')
  })

  return (
    <div className="space-y-8">
      <div className="text-center space-y-2">
        <h1 className="text-3xl sm:text-4xl font-semibold tracking-tight">
          {t('setup.security.title')}
        </h1>
        <p className="mx-auto max-w-md text-sm text-muted-foreground">
          {t('setup.security.subtitle')}
        </p>
      </div>

      <form onSubmit={onSubmit} className="mx-auto w-full max-w-2xl space-y-5" noValidate>
        <div className="space-y-2">
          <Label className="text-sm font-semibold">{t('setup.security.originsLabel')}</Label>
          <p className="text-sm text-muted-foreground">
            {t('setup.security.originsHint')}
          </p>
          <div className="space-y-3">
            {fields.map((field, idx) => {
              const originError = formState.errors.allowedOrigins?.[idx]
              const originMessage = typeof originError?.message === 'string' ? originError.message : undefined
              const originHasInput = (allowedOrigins?.[idx] ?? '').trim().length > 0
              const showOriginError = (originHasInput || formState.submitCount > 0) && !!originMessage

              return (
                <div key={field.id} className="space-y-1.5">
                  <div className="flex items-center gap-3">
                    <Input
                      placeholder="https://example.com"
                      aria-invalid={showOriginError}
                      aria-describedby={showOriginError ? `origin-error-${idx}` : undefined}
                      {...register(`allowedOrigins.${idx}` as const)}
                    />
                    {fields.length > 1 && (
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon"
                        onClick={() => remove(idx)}
                        aria-label={t('setup.security.originRemove')}
                        className="h-10 w-10 rounded-xl"
                      >
                        <X className="size-4" />
                      </Button>
                    )}
                  </div>
                  {showOriginError && (
                    <p id={`origin-error-${idx}`} className="text-xs text-destructive">
                      {t(originMessage)}
                    </p>
                  )}
                </div>
              )
            })}
          </div>
          {typeof formState.errors.allowedOrigins?.message === 'string' && formState.submitCount > 0 && (
            <p className="text-xs text-destructive">
              {t(formState.errors.allowedOrigins.message)}
            </p>
          )}
          <div className="pt-1">
            <Button
              type="button"
              variant="outline"
              onClick={() => append('')}
              className="rounded-xl px-4"
            >
              <PlusCircle className="mr-2 size-4" />
              {t('setup.security.originAdd')}
            </Button>
          </div>
        </div>

        <div className="flex items-center justify-between gap-4 rounded-xl border border-border/60 p-5">
          <div className="space-y-1">
            <Label htmlFor="setup-secure-cookies" className="text-sm font-semibold">
              {t('setup.security.secureCookiesLabel')}
            </Label>
            <p className="text-sm text-muted-foreground">
              {t('setup.security.secureCookiesHint')}
            </p>
          </div>
          <Controller
            control={control}
            name="secureCookies"
            render={({ field }) => (
              <Switch
                id="setup-secure-cookies"
                checked={field.value}
                onCheckedChange={field.onChange}
                className="origin-right scale-125"
              />
            )}
          />
        </div>

        {submit.error && (
          <p role="alert" className="text-sm text-destructive">
            {formatApiError(submit.error, t, 'setup.security.submitError')}
          </p>
        )}

        <div className="pt-2">
          <Button
            type="submit"
            disabled={submit.isPending}
            className="w-full rounded-full"
          >
            {submit.isPending ? t('setup.security.saving') : t('setup.security.cta')}
          </Button>
        </div>
      </form>
    </div>
  )
}
