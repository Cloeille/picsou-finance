import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useForm, useWatch, Controller } from 'react-hook-form'
import { NativeSelect } from '@/components/shared/NativeSelect'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Check, Key as KeyRound, PencilSimple, Plus, Trash, X } from '@phosphor-icons/react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { extractErrorMessage } from '@/lib/errors'
import { useUpdateAi, useTestAi } from '@/features/admin/hooks'
import type { AdminAiSettings } from '@/features/admin/api'

const schema = z.object({
  provider: z.string().min(1),
  model: z.string(),
  baseUrl: z.string(),
  apiKey: z.string(),
  maxConcurrency: z.number().int().min(1).max(16).optional(),
})

type FormValues = z.infer<typeof schema>

const PROVIDER_OPTIONS: { value: string; labelKey?: string; label?: string }[] = [
  { value: 'anthropic', label: 'Anthropic (Claude)' },
  { value: 'openrouter', label: 'OpenRouter' },
  { value: 'openai', labelKey: 'admin.ai.providerOpenAi' },
  { value: 'ollama', label: 'Ollama (local)' },
]

const PROVIDER_DEFAULTS: Record<string, { baseUrl: string; model: string }> = {
  anthropic: { baseUrl: 'https://api.anthropic.com', model: 'claude-haiku-4-5' },
  openrouter: { baseUrl: 'https://openrouter.ai/api', model: 'anthropic/claude-3.5-haiku' },
  openai: { baseUrl: 'https://api.openai.com', model: 'gpt-4o-mini' },
  ollama: { baseUrl: 'http://ollama:11434', model: 'qwen3:0.6b' },
}

export function AiCategorizationSection({ settings }: { settings: AdminAiSettings }) {
  const { t } = useTranslation()
  const update = useUpdateAi()
  const testAi = useTestAi()

  const isConfigured = settings.provider !== 'none'
  const [formOpen, setFormOpen] = useState(false)
  const [confirmRemove, setConfirmRemove] = useState(false)
  const [savedNotice, setSavedNotice] = useState(false)

  const { register, handleSubmit, reset, control, getValues, setError, formState } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      provider: isConfigured ? settings.provider : 'anthropic',
      model: settings.model,
      baseUrl: settings.baseUrl,
      apiKey: '',
      maxConcurrency: settings.maxConcurrency ?? 4,
    },
  })

  useEffect(() => {
    reset({
      provider: settings.provider !== 'none' ? settings.provider : 'anthropic',
      model: settings.model,
      baseUrl: settings.baseUrl,
      apiKey: '',
      maxConcurrency: settings.maxConcurrency ?? 4,
    })
  }, [settings, reset])

  const provider = useWatch({ control, name: 'provider' })
  const defaults = PROVIDER_DEFAULTS[provider] ?? null
  const keyKept = settings.apiKeyPresent && provider === settings.provider

  const providerLabel = (value: string) => {
    const opt = PROVIDER_OPTIONS.find((o) => o.value === value)
    if (!opt) return value
    return opt.labelKey ? t(opt.labelKey) : (opt.label ?? value)
  }

  const openAddForm = () => {
    setSavedNotice(false)
    testAi.reset?.()
    reset({
      provider: 'anthropic',
      model: '',
      baseUrl: '',
      apiKey: '',
      maxConcurrency: 4,
    })
    setFormOpen(true)
  }

  const openEditForm = () => {
    setSavedNotice(false)
    testAi.reset?.()
    reset({
      provider: settings.provider !== 'none' ? settings.provider : 'anthropic',
      model: settings.model,
      baseUrl: settings.baseUrl,
      apiKey: '',
      maxConcurrency: settings.maxConcurrency ?? 4,
    })
    setFormOpen(true)
  }

  const closeForm = () => {
    testAi.reset?.()
    reset({
      provider: settings.provider !== 'none' ? settings.provider : 'anthropic',
      model: settings.model,
      baseUrl: settings.baseUrl,
      apiKey: '',
      maxConcurrency: settings.maxConcurrency ?? 4,
    })
    setFormOpen(false)
  }

  const onSubmit = handleSubmit(async (values) => {
    const needsKey = values.provider !== 'none' && values.provider !== 'ollama'
    if (needsKey && !keyKept && !values.apiKey) {
      setError('apiKey', { type: 'manual', message: t('admin.ai.apiKeyRequired') })
      return
    }
    await update.mutateAsync({
      provider: values.provider,
      model: values.model,
      baseUrl: values.baseUrl,
      apiKey: values.apiKey,
      maxConcurrency: values.maxConcurrency,
    })
    testAi.reset?.()
    setFormOpen(false)
    setSavedNotice(true)
  })

  const handleTestForm = () => {
    const { provider: formProvider, model, baseUrl, apiKey } = getValues()
    testAi.mutate({ provider: formProvider, model, baseUrl, apiKey })
  }

  const handleTestSaved = () => {
    setSavedNotice(false)
    testAi.mutate({
      provider: settings.provider,
      model: settings.model,
      baseUrl: settings.baseUrl,
      apiKey: '',
    })
  }

  const handleRemoveProvider = async () => {
    await update.mutateAsync({
      provider: 'none',
      model: '',
      baseUrl: '',
      apiKey: '',
      maxConcurrency: 4,
    })
    testAi.reset?.()
    setConfirmRemove(false)
    setFormOpen(false)
    setSavedNotice(false)
  }

  const displayedModel = settings.model || PROVIDER_DEFAULTS[settings.provider]?.model || ''
  const displayedBaseUrl = settings.baseUrl || ''

  return (
    <Card className="rounded-xl bg-card">
      <CardHeader>
        <CardTitle className="text-base font-semibold">
            {t('admin.ai.title')}
        </CardTitle>
        <CardDescription>{t('admin.ai.description')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {!formOpen && !isConfigured && (
          <div className="flex flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-border/70 bg-muted/20 px-5 py-8 text-center">
            <p className="text-sm text-muted-foreground">{t('admin.ai.empty')}</p>
            <Button type="button" size="sm" onClick={openAddForm}>
              <Plus className="size-4" aria-hidden="true" />
              {t('admin.ai.addProvider')}
            </Button>
          </div>
        )}

        {!formOpen && isConfigured && (
          <div className="space-y-3 rounded-xl border border-border/60 bg-muted/15 p-4">
            <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
              <div className="min-w-0 space-y-2">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-semibold text-foreground">
                    {providerLabel(settings.provider)}
                  </span>
                  <Badge
                    variant="secondary"
                    className="gap-1 bg-emerald-500/10 text-emerald-700 dark:text-emerald-400"
                  >
                    <Check className="size-3" aria-hidden="true" />
                    {t('admin.ai.statusActive')}
                  </Badge>
                </div>

                {(displayedModel || displayedBaseUrl) && (
                  <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
                    {displayedModel && (
                      <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs text-foreground">
                        {displayedModel}
                      </code>
                    )}
                    {displayedBaseUrl && (
                      <span className="max-w-full truncate font-mono text-xs">{displayedBaseUrl}</span>
                    )}
                  </div>
                )}
              </div>

              <div className="flex shrink-0 flex-wrap items-center gap-2">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={testAi.isPending}
                  onClick={handleTestSaved}
                >
                  {testAi.isPending ? t('admin.ai.testing') : t('admin.ai.test')}
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={openEditForm}
                >
                  <PencilSimple className="size-3.5" aria-hidden="true" />
                  {t('common.edit')}
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  className="text-muted-foreground hover:text-destructive"
                  aria-label={t('admin.ai.removeProvider')}
                  title={t('admin.ai.removeProvider')}
                  onClick={() => setConfirmRemove(true)}
                >
                  <Trash className="size-4" aria-hidden="true" />
                </Button>
              </div>
            </div>

            {savedNotice && (
              <p className="text-sm text-emerald-600 dark:text-emerald-400">{t('admin.ai.saved')}</p>
            )}
            {testAi.data?.ok === true && (
              <p className="text-sm text-emerald-600 dark:text-emerald-400">{testAi.data.message}</p>
            )}
            {(testAi.isError || testAi.data?.ok === false) && (
              <p role="alert" className="text-sm text-destructive">
                {testAi.data?.message ?? extractErrorMessage(testAi.error)}
              </p>
            )}
          </div>
        )}

        {formOpen && (
          <form
            onSubmit={onSubmit}
            className="space-y-4 rounded-xl border border-border/60 bg-muted/10 p-4 sm:p-5"
            noValidate
          >
            <div className="flex items-center justify-between gap-2">
              <h3 className="text-sm font-semibold text-foreground">
                {isConfigured ? t('admin.ai.editProvider') : t('admin.ai.configureProvider')}
              </h3>
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                aria-label={t('common.close')}
                onClick={closeForm}
              >
                <X className="size-4" aria-hidden="true" />
              </Button>
            </div>

            {/* Provider select */}
            <div className="space-y-1.5">
              <Label htmlFor="admin-ai-provider">{t('admin.ai.provider')}</Label>
              <Controller
                name="provider"
                control={control}
                render={({ field }) => (
                  <NativeSelect
                    id="admin-ai-provider"
                    value={field.value}
                    onChange={(e) => field.onChange(e.target.value)}
                  >
                    {PROVIDER_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {opt.labelKey ? t(opt.labelKey) : opt.label}
                      </option>
                    ))}
                  </NativeSelect>
                )}
              />
            </div>

            {/* Base URL */}
            <div className="space-y-1.5">
              <Label htmlFor="admin-ai-baseUrl">{t('admin.ai.baseUrl')}</Label>
              <Input
                id="admin-ai-baseUrl"
                placeholder={defaults?.baseUrl}
                {...register('baseUrl')}
              />
            </div>

            {/* Model */}
            <div className="space-y-1.5">
              <Label htmlFor="admin-ai-model">{t('admin.ai.model')}</Label>
              <Input
                id="admin-ai-model"
                placeholder={defaults?.model}
                {...register('model')}
              />
            </div>

            {/* Max concurrency */}
            <div className="space-y-1.5">
              <Label htmlFor="admin-ai-maxConcurrency">{t('admin.ai.maxConcurrency')}</Label>
              <Input
                id="admin-ai-maxConcurrency"
                type="number"
                min={1}
                max={16}
                {...register('maxConcurrency', { valueAsNumber: true })}
              />
              <p className="text-sm text-muted-foreground">{t('admin.ai.maxConcurrencyHint')}</p>
            </div>

            {/* API Key — hidden for Ollama */}
            {provider !== 'ollama' && (
              <div className="space-y-1.5">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <Label htmlFor="admin-ai-apiKey">{t('admin.ai.apiKey')}</Label>
                  {keyKept && (
                    <Badge
                      variant="secondary"
                      className="gap-1 bg-emerald-500/10 text-emerald-700 dark:text-emerald-400"
                    >
                      <KeyRound className="size-3" aria-hidden="true" />
                      {t('admin.ai.apiKeySaved')}
                    </Badge>
                  )}
                </div>
                <Input
                  id="admin-ai-apiKey"
                  type="password"
                  placeholder={keyKept ? t('admin.ai.apiKeyPlaceholderKeep') : undefined}
                  {...register('apiKey')}
                />
                {keyKept && (
                  <p className="text-sm text-muted-foreground">
                    {t('admin.ai.apiKeyHintPresent')}
                  </p>
                )}
                {formState.errors.apiKey && (
                  <p role="alert" className="text-sm text-destructive">
                    {formState.errors.apiKey.message}
                  </p>
                )}
              </div>
            )}

            {/* Test result */}
            {testAi.data?.ok === true && (
              <p className="text-sm text-emerald-600 dark:text-emerald-400">{testAi.data.message}</p>
            )}
            {(testAi.isError || testAi.data?.ok === false) && (
              <p role="alert" className="text-sm text-destructive">
                {testAi.data?.message ?? extractErrorMessage(testAi.error)}
              </p>
            )}

            {/* Save error */}
            {update.error && (
              <p role="alert" className="text-sm text-destructive">
                {extractErrorMessage(update.error)}
              </p>
            )}

            {/* Actions */}
            <div className="flex flex-col gap-2 pt-1 sm:flex-row sm:items-center sm:justify-between">
              <Button
                type="button"
                variant="outline"
                disabled={testAi.isPending}
                onClick={handleTestForm}
              >
                {testAi.isPending ? t('admin.ai.testing') : t('admin.ai.test')}
              </Button>
              <div className="flex flex-col gap-2 sm:flex-row">
                <Button type="button" variant="outline" onClick={closeForm}>
                  {t('common.cancel')}
                </Button>
                <Button type="submit" disabled={update.isPending}>
                  {update.isPending ? t('admin.ai.saving') : t('admin.ai.save')}
                </Button>
              </div>
            </div>
          </form>
        )}

        <ConfirmDialog
          open={confirmRemove}
          onOpenChange={setConfirmRemove}
          title={t('admin.ai.removeProvider')}
          description={t('admin.ai.removeProviderConfirm')}
          confirmLabel={t('common.delete')}
          variant="destructive"
          onConfirm={() => void handleRemoveProvider()}
          loading={update.isPending}
        />
      </CardContent>
    </Card>
  )
}
