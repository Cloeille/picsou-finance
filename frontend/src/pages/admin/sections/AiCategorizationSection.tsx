import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useForm, useWatch, Controller } from 'react-hook-form'
import { NativeSelect } from '@/components/shared/NativeSelect'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import {
  Check,
  CircleNotch,
  Key as KeyRound,
  PencilSimple,
  Plus,
  Trash,
  X,
} from '@phosphor-icons/react'
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
import { useUpdateAi, useTestAi, useListAiModels } from '@/features/admin/hooks'
import type { AdminAiSettings } from '@/features/admin/api'
import { useAppStore } from '@/stores/app-store'

const schema = z.object({
  provider: z.string().min(1),
  model: z.string(),
  baseUrl: z.string(),
  apiKey: z.string(),
  maxConcurrency: z.number().int().min(1).max(16).optional(),
})

type FormValues = z.infer<typeof schema>

type ApiKeyRequirement = 'required' | 'optional' | 'none'

interface ProviderSpec {
  value: string
  label?: string
  labelKey?: string
  baseUrl: string
  desktopBaseUrl?: string
  model: string
  maxConcurrency: number
  apiKey: ApiKeyRequirement
}

const PROVIDERS: readonly ProviderSpec[] = [
  {
    value: 'anthropic',
    label: 'Anthropic',
    baseUrl: 'https://api.anthropic.com',
    model: 'claude-haiku-4-5',
    maxConcurrency: 8,
    apiKey: 'required',
  },
  {
    value: 'openai',
    label: 'OpenAI',
    baseUrl: 'https://api.openai.com',
    model: 'gpt-4o-mini',
    maxConcurrency: 8,
    apiKey: 'required',
  },
  {
    value: 'gemini',
    label: 'Google Gemini',
    baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai',
    model: 'gemini-flash-latest',
    maxConcurrency: 4,
    apiKey: 'required',
  },
  {
    value: 'mistral',
    label: 'Mistral',
    baseUrl: 'https://api.mistral.ai',
    model: 'mistral-small-latest',
    maxConcurrency: 4,
    apiKey: 'required',
  },
  {
    value: 'openrouter',
    label: 'OpenRouter',
    baseUrl: 'https://openrouter.ai/api',
    model: 'anthropic/claude-3.5-haiku',
    maxConcurrency: 8,
    apiKey: 'required',
  },
  {
    value: 'ollama',
    label: 'Ollama',
    baseUrl: 'http://ollama:11434',
    desktopBaseUrl: 'http://localhost:11434',
    model: 'qwen3:0.6b',
    maxConcurrency: 4,
    apiKey: 'none',
  },
  {
    value: 'custom',
    labelKey: 'admin.ai.providerCustom',
    baseUrl: '',
    model: '',
    maxConcurrency: 4,
    apiKey: 'optional',
  },
]

function getProviderSpec(value: string): ProviderSpec | undefined {
  return PROVIDERS.find((p) => p.value === value)
}

function defaultBaseUrlFor(spec: ProviderSpec | undefined, desktopMode: boolean): string {
  if (!spec) return ''
  return desktopMode && spec.desktopBaseUrl ? spec.desktopBaseUrl : spec.baseUrl
}

function formValuesFromSettings(settings: AdminAiSettings, desktopMode: boolean): FormValues {
  const provider = settings.provider !== 'none' ? settings.provider : 'anthropic'
  const spec = getProviderSpec(provider)
  return {
    provider,
    model: settings.model || spec?.model || '',
    baseUrl: settings.baseUrl || defaultBaseUrlFor(spec, desktopMode),
    apiKey: '',
    maxConcurrency: settings.maxConcurrency ?? spec?.maxConcurrency ?? 4,
  }
}

export function AiCategorizationSection({ settings }: { settings: AdminAiSettings }) {
  const { t } = useTranslation()
  const desktopMode = useAppStore((s) => s.desktopMode)
  const update = useUpdateAi()
  const testAi = useTestAi()
  const listModels = useListAiModels()

  const isConfigured = settings.provider !== 'none'
  const [formOpen, setFormOpen] = useState(false)
  const [confirmRemove, setConfirmRemove] = useState(false)
  const [savedNotice, setSavedNotice] = useState(false)
  const [lastFetchedModelsKey, setLastFetchedModelsKey] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    reset,
    control,
    getValues,
    setValue,
    setError,
    clearErrors,
    formState,
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: formValuesFromSettings(settings, desktopMode),
  })

  useEffect(() => {
    if (!formOpen) {
      reset(formValuesFromSettings(settings, desktopMode))
    }
  }, [settings, desktopMode, formOpen, reset])

  const provider = useWatch({ control, name: 'provider' })
  const currentSpec = getProviderSpec(provider)
  const keyKept = settings.apiKeyPresent && provider === settings.provider
  const modelSuggestions = listModels.data?.ok ? listModels.data.models : []
  const modelsFailed = listModels.isError || listModels.data?.ok === false

  const providerLabel = (value: string) => {
    const spec = getProviderSpec(value)
    if (!spec) return value
    return spec.labelKey ? t(spec.labelKey) : (spec.label ?? value)
  }

  const canFetchModelsFor = (
    targetProvider: string,
    targetBaseUrl: string,
    targetApiKey: string,
  ): boolean => {
    const spec = getProviderSpec(targetProvider)
    if (!spec) return false
    if (spec.apiKey === 'required') {
      const kept = settings.apiKeyPresent && targetProvider === settings.provider
      return Boolean(targetApiKey.trim() || kept)
    }
    if (targetProvider === 'custom') {
      return Boolean(targetBaseUrl.trim())
    }
    return true
  }

  const requestModelList = (
    targetProvider: string,
    targetBaseUrl: string,
    targetApiKey: string,
    force = false,
  ) => {
    if (!canFetchModelsFor(targetProvider, targetBaseUrl, targetApiKey)) return
    const payload = {
      provider: targetProvider,
      baseUrl: targetBaseUrl.trim(),
      apiKey: targetProvider === 'ollama' ? '' : targetApiKey,
    }
    const requestKey = `${payload.provider}\0${payload.baseUrl}\0${payload.apiKey}`
    // Skip duplicate fetches when tabbing past an already-loaded field without changes.
    if (!force && lastFetchedModelsKey === requestKey && listModels.data?.ok) {
      return
    }
    setLastFetchedModelsKey(requestKey)
    listModels.mutate(payload)
  }

  const openAddForm = () => {
    setSavedNotice(false)
    testAi.reset?.()
    listModels.reset?.()
    setLastFetchedModelsKey(null)
    const spec = getProviderSpec('anthropic')
    reset({
      provider: 'anthropic',
      model: spec?.model ?? '',
      baseUrl: defaultBaseUrlFor(spec, desktopMode),
      apiKey: '',
      maxConcurrency: spec?.maxConcurrency ?? 8,
    })
    setFormOpen(true)
  }

  const openEditForm = () => {
    setSavedNotice(false)
    testAi.reset?.()
    listModels.reset?.()
    setLastFetchedModelsKey(null)
    const nextValues = formValuesFromSettings(settings, desktopMode)
    reset(nextValues)
    setFormOpen(true)
    requestModelList(nextValues.provider, nextValues.baseUrl, '', true)
  }

  const closeForm = () => {
    testAi.reset?.()
    listModels.reset?.()
    setLastFetchedModelsKey(null)
    reset(formValuesFromSettings(settings, desktopMode))
    setFormOpen(false)
  }

  const handleProviderChange = (nextProvider: string, onFieldChange: (value: string) => void) => {
    const prevProvider = getValues('provider')
    const prevDefaultUrl = defaultBaseUrlFor(getProviderSpec(prevProvider), desktopMode)
    const nextSpec = getProviderSpec(nextProvider)
    const nextDefaultUrl = defaultBaseUrlFor(nextSpec, desktopMode)
    const currentBaseUrl = getValues('baseUrl')
    const nextBaseUrl =
      currentBaseUrl.trim() === prevDefaultUrl ? nextDefaultUrl : currentBaseUrl

    onFieldChange(nextProvider)
    setValue('baseUrl', nextBaseUrl)
    setValue('model', nextSpec?.model ?? '')
    setValue('maxConcurrency', nextSpec?.maxConcurrency ?? 4)
    clearErrors(['apiKey', 'baseUrl'])
    listModels.reset?.()
    setLastFetchedModelsKey(null)

    if (
      nextProvider === 'ollama' ||
      (settings.apiKeyPresent && nextProvider === settings.provider)
    ) {
      requestModelList(nextProvider, nextBaseUrl, '', true)
    }
  }

  const handleBlurFetchModels = () => {
    const { provider: currentProvider, baseUrl, apiKey } = getValues()
    requestModelList(currentProvider, baseUrl, apiKey)
  }

  const onSubmit = handleSubmit(async (values) => {
    const spec = getProviderSpec(values.provider)
    if (values.provider === 'custom' && !values.baseUrl.trim()) {
      setError('baseUrl', { type: 'manual', message: t('admin.ai.baseUrlRequired') })
      return
    }
    const needsKey = spec ? spec.apiKey === 'required' : values.provider !== 'none'
    if (needsKey && !keyKept && !values.apiKey.trim()) {
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
    listModels.reset?.()
    setLastFetchedModelsKey(null)
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
    listModels.reset?.()
    setLastFetchedModelsKey(null)
    setConfirmRemove(false)
    setFormOpen(false)
    setSavedNotice(false)
  }

  const displayedModel = settings.model || getProviderSpec(settings.provider)?.model || ''
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
                    onChange={(e) => handleProviderChange(e.target.value, field.onChange)}
                  >
                    {PROVIDERS.map((opt) => (
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
                {...register('baseUrl', {
                  onChange: () => {
                    if (formState.errors.baseUrl) clearErrors('baseUrl')
                  },
                  onBlur: handleBlurFetchModels,
                })}
              />
              {formState.errors.baseUrl && (
                <p role="alert" className="text-sm text-destructive">
                  {formState.errors.baseUrl.message}
                </p>
              )}
            </div>

            {/* Model */}
            <div className="space-y-1.5">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <Label htmlFor="admin-ai-model">{t('admin.ai.model')}</Label>
                {listModels.isPending && (
                  <span
                    role="status"
                    className="inline-flex items-center gap-1.5 text-sm text-muted-foreground"
                  >
                    <CircleNotch className="size-3.5 animate-spin" aria-hidden="true" />
                    {t('admin.ai.modelsLoading')}
                  </span>
                )}
              </div>
              <Input
                id="admin-ai-model"
                list={modelSuggestions.length > 0 ? 'admin-ai-model-suggestions' : undefined}
                {...register('model')}
              />
              {modelSuggestions.length > 0 && (
                <datalist id="admin-ai-model-suggestions">
                  {modelSuggestions.map((modelId) => (
                    <option key={modelId} value={modelId} />
                  ))}
                </datalist>
              )}
              {modelsFailed && (
                <p className="text-sm text-muted-foreground">
                  {t('admin.ai.modelsLoadFailed')}
                </p>
              )}
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
            {currentSpec?.apiKey !== 'none' && (
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
                  {...register('apiKey', {
                    onChange: () => {
                      if (formState.errors.apiKey) clearErrors('apiKey')
                    },
                    onBlur: handleBlurFetchModels,
                  })}
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
