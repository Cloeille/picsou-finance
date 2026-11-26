import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import {
  Warning,
  Check,
  CheckCircle,
  Copy,
  DownloadSimple,
  Key as KeyRound,
  CircleNotch,
  UploadSimple,
} from '@phosphor-icons/react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Separator } from '@/components/ui/separator'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { extractErrorMessage } from '@/lib/errors'
import {
  useGenerateEnableBankingKeyPair,
  useImportEnableBankingPrivateKey,
  useUpdateEnableBanking,
} from '@/features/admin/hooks'
import type { AdminEnableBankingSettings } from '@/features/admin/api'
import {
  DESKTOP_ENABLE_BANKING_REDIRECT_URI,
  isDesktopEnableBankingMode,
} from '@/features/desktop/enable-banking'
import { useAppStore } from '@/stores/app-store'

const schema = z.object({
  applicationId: z.string().min(1),
  redirectUri: z.string().url(),
})

type FormValues = z.infer<typeof schema>

export function EnableBankingSection({ settings }: { settings: AdminEnableBankingSettings }) {
  const { t, i18n } = useTranslation()
  const update = useUpdateEnableBanking()
  const desktopMode = isDesktopEnableBankingMode(useAppStore((s) => s.desktopMode))

  const { register, handleSubmit, reset, formState } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: settings,
  })

  useEffect(() => { reset(settings) }, [settings, reset])

  const onSubmit = handleSubmit(async (values) => {
    await update.mutateAsync(values)
  })

  const FIELDS: { name: keyof FormValues; labelKey: string; placeholder?: string; hintKey?: string }[] = [
    { name: 'applicationId', labelKey: 'admin.enableBanking.applicationId', hintKey: 'admin.enableBanking.applicationIdHint' },
    {
      name: 'redirectUri',
      labelKey: 'admin.enableBanking.redirectUri',
      placeholder: desktopMode ? DESKTOP_ENABLE_BANKING_REDIRECT_URI : 'https://app.com/sync/callback',
      hintKey: desktopMode ? 'setup.enablebanking.redirect.desktopNote' : undefined,
    },
  ]

  // Which of the required pieces are missing — computed from the persisted
  // settings, not the dirty form, so the banner reflects the server's truth.
  // The Key ID isn't listed: it's derived from the Application ID server-side.
  const missing: string[] = []
  if (!settings.applicationId) missing.push(t('admin.enableBanking.missing.applicationId'))
  if (!settings.redirectUri) missing.push(t('admin.enableBanking.missing.redirectUri'))
  if (!settings.privateKeyPresent) missing.push(t('admin.enableBanking.missing.privateKey'))
  const missingFormatted = missing.length > 0
    ? new Intl.ListFormat(i18n.resolvedLanguage ?? i18n.language, { style: 'long', type: 'conjunction' }).format(missing)
    : ''

  return (
    <Card className="rounded-xl bg-card">
      <CardHeader>
        <CardTitle className="text-base font-semibold">
          {t('admin.enableBanking.title')}
        </CardTitle>
        <CardDescription>{t('admin.enableBanking.description')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-6">
        {/* Readiness banner — names every missing piece, not just the key. */}
        {missing.length === 0 ? (
          <div className="flex items-start gap-2 rounded-xl border border-emerald-500/40 bg-emerald-500/5 p-3 text-sm text-emerald-700 dark:text-emerald-400">
            <CheckCircle className="mt-0.5 size-4 shrink-0" />
            <span>{t('admin.enableBanking.ready')}</span>
          </div>
        ) : (
          <div role="alert" className="flex items-start gap-2.5 rounded-xl border border-border bg-muted/40 p-3.5 text-sm text-foreground">
            <Warning className="mt-0.5 size-4 shrink-0" />
            <span>{t('admin.enableBanking.incomplete', { fields: missingFormatted })}</span>
          </div>
        )}

        <p className="text-sm text-muted-foreground">
          {t('admin.enableBanking.restrictedModeNote')}
        </p>

        <form onSubmit={onSubmit} className="space-y-4" noValidate>
          {FIELDS.map(({ name, labelKey, placeholder, hintKey }) => (
            <div key={name} className="space-y-1.5">
              <Label htmlFor={`admin-eb-${name}`}>{t(labelKey)}</Label>
              <Input
                id={`admin-eb-${name}`}
                placeholder={placeholder}
                aria-invalid={!!formState.errors[name]}
                {...register(name)}
              />
              {formState.errors[name] ? (
                <p className="text-sm text-destructive">{formState.errors[name]?.message}</p>
              ) : hintKey ? (
                <p className="text-sm text-muted-foreground">{t(hintKey)}</p>
              ) : null}
            </div>
          ))}

          {update.error && (
            <p role="alert" className="text-sm text-destructive">
              {extractErrorMessage(update.error)}
            </p>
          )}

          <Button type="submit" disabled={update.isPending || !formState.isDirty}>
            {update.isPending ? t('admin.enableBanking.saving') : t('admin.enableBanking.save')}
          </Button>
          {update.isSuccess && !formState.isDirty && (
            <span className="ml-3 text-sm text-emerald-600">{t('admin.enableBanking.saved')}</span>
          )}
        </form>

        <Separator />

        <KeypairPanel privateKeyPresent={settings.privateKeyPresent} />
      </CardContent>
    </Card>
  )
}

type Mode = 'generate' | 'import'

/**
 * Self-contained private-key manager for the admin page. Mirrors the setup
 * wizard's EBStep3Keypair (generate → show/copy/download public PEM, or import
 * a .pem) but talks to the admin endpoints, which — unlike the wizard's — are
 * not gated by "setup not complete", so an operator can recover a lost key
 * after first-run. Generating is idempotent and never invalidates a public key
 * already uploaded to the Enable Banking dashboard.
 */
function KeypairPanel({ privateKeyPresent }: { privateKeyPresent: boolean }) {
  const { t } = useTranslation()
  const generate = useGenerateEnableBankingKeyPair()
  const importKey = useImportEnableBankingPrivateKey()

  const [mode, setMode] = useState<Mode>('generate')
  const [publicPem, setPublicPem] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [privatePem, setPrivatePem] = useState('')
  const [importError, setImportError] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const handleGenerate = () => {
    generate.mutate(undefined, { onSuccess: (data) => setPublicPem(data.publicKeyPem) })
  }

  const handleCopy = async () => {
    if (!publicPem) return
    try {
      await navigator.clipboard.writeText(publicPem)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      /* clipboard unavailable — the PEM is visible for manual copy */
    }
  }

  const handleDownload = () => {
    if (!publicPem) return
    const blob = new Blob([publicPem], { type: 'application/x-pem-file' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'picsou-enablebanking-public.pem'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>): void => {
    const file = e.target.files?.[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = (ev) => setPrivatePem((ev.target?.result as string) ?? '')
    reader.readAsText(file)
  }

  const handleImport = () => {
    if (!privatePem.trim()) return
    setImportError(null)
    importKey.mutate(privatePem.trim(), {
      onSuccess: (data) => { setPublicPem(data.publicKeyPem); setPrivatePem('') },
      onError: (err) => setImportError(extractErrorMessage(err) || t('admin.enableBanking.keypair.importError')),
    })
  }

  const switchMode = (next: Mode) => {
    setMode(next)
    setPrivatePem('')
    setImportError(null)
    setPublicPem(null)
  }

  return (
    <div className="space-y-4">
      <div className="space-y-1">
        <h3 className="text-sm font-medium">
            {t('admin.enableBanking.keypair.title')}
        </h3>
        <p className="text-sm text-muted-foreground">
          {privateKeyPresent
            ? t('admin.enableBanking.keypair.statusPresent')
            : t('admin.enableBanking.keypair.statusMissing')}
        </p>
      </div>

      {/* Mode toggle */}
      <div className="flex gap-1 rounded-lg bg-muted p-1">
        {(['generate', 'import'] as Mode[]).map((m) => (
          <button
            key={m}
            type="button"
            onClick={() => switchMode(m)}
            className={`flex-1 rounded-xl px-6 py-2 text-sm font-medium transition-colors ${
              mode === m
                ? 'bg-primary text-primary-foreground'
                : 'text-muted-foreground hover:text-foreground'
            }`}
          >
            {m === 'generate'
              ? t('admin.enableBanking.keypair.modeGenerate')
              : t('admin.enableBanking.keypair.modeImport')}
          </button>
        ))}
      </div>

      {mode === 'generate' && (
        <div className="space-y-3">
          <p className="text-sm text-muted-foreground">{t('admin.enableBanking.keypair.body')}</p>
          {generate.isError && (
            <p role="alert" className="text-sm text-destructive">{extractErrorMessage(generate.error)}</p>
          )}
          {!publicPem && (
            <Button type="button" onClick={handleGenerate} disabled={generate.isPending} className="w-full sm:w-auto">
              {generate.isPending ? (
                <><CircleNotch className="size-4 animate-spin" />{t('admin.enableBanking.keypair.generating')}</>
              ) : (
                <><KeyRound className="size-4" />{
                  privateKeyPresent
                    ? t('admin.enableBanking.keypair.regenerate')
                    : t('admin.enableBanking.keypair.generate')
                }</>
              )}
            </Button>
          )}
        </div>
      )}

      {mode === 'import' && !publicPem && (
        <div className="space-y-3">
          <p className="text-sm text-muted-foreground">{t('admin.enableBanking.keypair.importBody')}</p>
          <input
            ref={fileInputRef}
            type="file"
            accept=".pem,application/x-pem-file,text/plain"
            className="hidden"
            onChange={handleFileChange}
          />
          <Button type="button" variant="outline" className="w-full" onClick={() => fileInputRef.current?.click()}>
            <UploadSimple className="size-4" />
            {t('admin.enableBanking.keypair.chooseFile')}
          </Button>
          <p className="text-center text-sm text-muted-foreground">{t('admin.enableBanking.keypair.orPaste')}</p>
          <textarea
            value={privatePem}
            onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) => setPrivatePem(e.target.value)}
            placeholder={'-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----'}
            className="min-h-[140px] w-full resize-none rounded-xl border border-input bg-input/20 p-3 font-mono text-sm leading-snug placeholder:text-muted-foreground/80 dark:bg-input/30"
          />
          {importError && <p role="alert" className="text-sm text-destructive">{importError}</p>}
          <Button
            className="w-full sm:w-auto"
            disabled={!privatePem.trim() || importKey.isPending}
            onClick={handleImport}
          >
            {importKey.isPending ? (
              <><CircleNotch className="size-4 animate-spin" />{t('admin.enableBanking.keypair.importing')}</>
            ) : (
              t('admin.enableBanking.keypair.importCta')
            )}
          </Button>
        </div>
      )}

      {/* Public PEM result (shared by both modes) */}
      {publicPem && (
        <div className="space-y-3">
          <p className="text-sm text-muted-foreground">{t('admin.enableBanking.keypair.uploadInstruction')}</p>
          <pre className="max-h-64 overflow-auto rounded-xl border border-border/60 bg-muted/40 p-4 text-xs leading-relaxed whitespace-pre-wrap break-all">
            {publicPem}
          </pre>
          <div className="flex flex-col gap-2 sm:flex-row">
            <Button type="button" variant="outline" size="sm" onClick={handleCopy} className="w-full sm:w-auto">
              {copied ? (
                <><Check className="size-4" />{t('admin.enableBanking.keypair.copied')}</>
              ) : (
                <><Copy className="size-4" />{t('admin.enableBanking.keypair.copy')}</>
              )}
            </Button>
            <Button type="button" variant="outline" size="sm" onClick={handleDownload} className="w-full sm:w-auto">
              <DownloadSimple className="size-4" />
              {t('admin.enableBanking.keypair.download')}
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}
