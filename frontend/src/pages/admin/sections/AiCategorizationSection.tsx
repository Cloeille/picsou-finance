import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { BrainCircuit } from 'lucide-react'
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
  { value: 'none', labelKey: 'admin.ai.providerNone' },
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

  const { register, handleSubmit, reset, watch, getValues, setError, formState } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      provider: settings.provider,
      model: settings.model,
      baseUrl: settings.baseUrl,
      apiKey: '',
      maxConcurrency: settings.maxConcurrency ?? 4,
    },
  })

  useEffect(() => {
    reset({
      provider: settings.provider,
      model: settings.model,
      baseUrl: settings.baseUrl,
      apiKey: '',
      maxConcurrency: settings.maxConcurrency ?? 4,
    })
  }, [settings, reset])

  const provider = watch('provider')
  const defaults = PROVIDER_DEFAULTS[provider] ?? null
  const keyKept = settings.apiKeyPresent && provider === settings.provider

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
  })

  const handleTest = () => {
    const { provider, model, baseUrl, apiKey } = getValues()
    testAi.mutate({ provider, model, baseUrl, apiKey })
  }

  return (
    <Card className="rounded-4xl bg-card shadow-md">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-lg">
          <BrainCircuit className="size-5 text-muted-foreground" />
          {t('admin.ai.title')}
        </CardTitle>
        <CardDescription>{t('admin.ai.description')}</CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={onSubmit} className="space-y-4" noValidate>
          {/* Provider select */}
          <div className="space-y-1.5">
            <Label htmlFor="admin-ai-provider">{t('admin.ai.provider')}</Label>
            <select
              id="admin-ai-provider"
              className="h-7 w-full min-w-0 rounded-md border border-input bg-input/20 px-2 py-0.5 text-sm transition-colors outline-none focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/30 dark:bg-input/30"
              {...register('provider')}
            >
              {PROVIDER_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.labelKey ? t(opt.labelKey) : opt.label}
                </option>
              ))}
            </select>
          </div>

          {provider === 'none' ? (
            <p className="text-sm text-muted-foreground">{t('admin.ai.disabledHint')}</p>
          ) : (
            <>
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
                <p className="text-xs text-muted-foreground">{t('admin.ai.maxConcurrencyHint')}</p>
              </div>

              {/* API Key — hidden for Ollama */}
              {provider !== 'ollama' && (
                <div className="space-y-1.5">
                  <Label htmlFor="admin-ai-apiKey">{t('admin.ai.apiKey')}</Label>
                  <Input
                    id="admin-ai-apiKey"
                    type="password"
                    {...register('apiKey')}
                  />
                  {keyKept && (
                    <p className="text-xs text-muted-foreground">
                      {t('admin.ai.apiKeyHintPresent')}
                    </p>
                  )}
                  {formState.errors.apiKey && (
                    <p role="alert" className="text-xs text-destructive">
                      {formState.errors.apiKey.message}
                    </p>
                  )}
                </div>
              )}

              {/* Test result */}
              {testAi.data?.ok === true && (
                <p className="text-sm text-emerald-600">{testAi.data.message}</p>
              )}
              {(testAi.isError || testAi.data?.ok === false) && (
                <p role="alert" className="text-sm text-destructive">
                  {testAi.data?.message ?? extractErrorMessage(testAi.error)}
                </p>
              )}
            </>
          )}

          {/* Save error */}
          {update.error && (
            <p role="alert" className="text-sm text-destructive">
              {extractErrorMessage(update.error)}
            </p>
          )}

          {/* Buttons — stack on mobile, row on sm */}
          <div className="flex flex-col gap-2 sm:flex-row">
            {provider !== 'none' && (
              <Button
                type="button"
                variant="outline"
                disabled={testAi.isPending}
                onClick={handleTest}
              >
                {testAi.isPending ? t('admin.ai.testing') : t('admin.ai.test')}
              </Button>
            )}
            <Button type="submit" disabled={update.isPending}>
              {update.isPending ? t('admin.ai.saving') : t('admin.ai.save')}
            </Button>
          </div>
          {update.isSuccess && !formState.isDirty && (
            <span className="text-sm text-emerald-600">{t('admin.ai.saved')}</span>
          )}
        </form>
      </CardContent>
    </Card>
  )
}
