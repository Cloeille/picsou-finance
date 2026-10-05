import { useState, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ACCOUNT_COLORS, ACCOUNT_TYPES } from '@/lib/constants'
import { formatDate } from '@/lib/utils'
import { getErrorStatus, getErrorDetail } from '@/lib/errors'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import {
  Upload,
  ArrowLeft,
  ArrowRight,
  CheckCircle2,
  X,
  Loader2,
  LogOut,
  RefreshCw,
} from 'lucide-react'
import {
  useFinaryConnectionStatus,
  useFinaryLogin,
  useFinaryDeleteSession,
  usePreviewFinaryFile,
  usePreviewFinaryApi,
  useImportFinary,
  useExecuteFinaryApiSync,
  useFinaryAutoSync,
} from '@/features/sync/hooks'
import type {
  Account,
  FinaryPreviewResponse,
  FinaryAccountMapping,
  FinaryImportResultResponse,
  FinaryMappingAction,
  AccountType,
  FinaryAutoSyncResponse,
} from '@/types/api'

type WizardStep = 1 | 2 | 3

interface NewAccountForm {
  name: string
  type: AccountType
  provider: string
  currency: string
  color: string
}

const defaultNewAccount: NewAccountForm = {
  name: '',
  type: 'CHECKING',
  provider: 'Finary',
  currency: 'EUR',
  color: ACCOUNT_COLORS[0],
}

export function FinaryTab() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()

  const { data: connectionStatus, isLoading: statusLoading } = useFinaryConnectionStatus()
  const loginMutation = useFinaryLogin()
  const deleteMutation = useFinaryDeleteSession()
  const previewFileMutation = usePreviewFinaryFile()
  const previewApiMutation = usePreviewFinaryApi()
  const importMutation = useImportFinary()
  const executeApiMutation = useExecuteFinaryApiSync()
  const autoSyncMutation = useFinaryAutoSync()

  const isConnected = connectionStatus?.connected ?? false
  const isTotpRequired = connectionStatus?.status === 'TOTP_REQUIRED'

  // Wizard state
  const [step, setStep] = useState<WizardStep>(1)
  const [isApiSync, setIsApiSync] = useState(false)
  const [previewData, setPreviewData] = useState<FinaryPreviewResponse | null>(null)
  const [mappings, setMappings] = useState<FinaryAccountMapping[]>([])
  const [importResult, setImportResult] = useState<FinaryImportResultResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Login form state
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)

  // TOTP state
  const [totpRequired, setTotpRequired] = useState(false)
  const [totpCode, setTotpCode] = useState('')

  // Delete confirmation
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)

  // File upload
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [dragOver, setDragOver] = useState(false)

  // ----- Login -----

  function handleLogin(e: React.FormEvent) {
    e.preventDefault()
    if (!email || !password) return
    setLoading(true)
    setError(null)
    loginMutation.mutate(
      { email, password },
      {
        onSuccess: () => {
          setLoading(false)
          setEmail('')
          setPassword('')
          // Now trigger preview with stored credentials
          handleApiSyncPreview()
        },
        onError: () => setLoading(false),
      },
    )
  }

  // ----- Auto-sync (primary path when connected) -----

  function handleAutoSync() {
    setLoading(true)
    setError(null)
    autoSyncMutation.mutate(undefined, {
      onSuccess: (data: FinaryAutoSyncResponse) => {
        setLoading(false)
        if (data.status === 'OK') {
          toast.success(t('sync.finary.accountsSyncedToast', { count: data.accountsSynced }))
        } else if (data.status === 'NEEDS_MAPPING') {
          handleApiSyncPreview()
        } else if (data.status === 'TOTP_REQUIRED') {
          setTotpRequired(true)
        } else {
          setError(t('sync.finary.notConnected'))
        }
      },
      onError: (err: unknown) => {
        setLoading(false)
        setError(getFinaryError(err))
      },
    })
  }

  // ----- Preview with TOTP (fallback when TOTP required) -----

  function handleSync() {
    setLoading(true)
    setError(null)
    previewApiMutation.mutate(totpCode || undefined, {
      onSuccess: (data) => {
        setLoading(false)
        setPreviewData(data)
        initMappings(data)
        setIsApiSync(true)
        setTotpRequired(false)
        setTotpCode('')

        if (data.autoMapped && data.suggestedMappings) {
          executeWithMappings(data.fileToken, data.suggestedMappings)
        } else {
          setStep(2)
        }
      },
      onError: (err: unknown) => {
        setLoading(false)
        if (getErrorStatus(err) === 403) {
          setTotpRequired(true)
        } else {
          setError(getFinaryError(err))
        }
      },
    })
  }

  function handleApiSyncPreview() {
    setLoading(true)
    setError(null)
    previewApiMutation.mutate(totpCode || undefined, {
      onSuccess: (data) => {
        setLoading(false)
        setPreviewData(data)
        initMappings(data)
        setIsApiSync(true)
        setTotpRequired(false)
        setStep(2)
      },
      onError: (err: unknown) => {
        setLoading(false)
        if (getErrorStatus(err) === 403) {
          setTotpRequired(true)
        } else {
          setError(getFinaryError(err))
        }
      },
    })
  }

  function executeWithMappings(token: string, mappingsToUse: FinaryAccountMapping[]) {
    setLoading(true)
    setError(null)
    const onSuccess = (data: FinaryImportResultResponse) => {
      setLoading(false)
      setImportResult(data)
      setStep(3)
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    }
    const onError = (err: unknown) => {
      setLoading(false)
      setError(getFinaryError(err))
    }

    // Each mutation takes a distinct payload shape — branch so the call stays
    // type-safe instead of erasing it with a cast.
    if (isApiSync) {
      executeApiMutation.mutate({ syncToken: token, mappings: mappingsToUse }, { onSuccess, onError })
    } else {
      importMutation.mutate({ fileToken: token, mappings: mappingsToUse }, { onSuccess, onError })
    }
  }

  // ----- File upload -----

  function handleFileUpload(file: File) {
    setLoading(true)
    setError(null)
    previewFileMutation.mutate(file, {
      onSuccess: (data) => {
        setLoading(false)
        setPreviewData(data)
        initMappings(data)
        setIsApiSync(false)
        setStep(2)
      },
      onError: (err: unknown) => {
        setLoading(false)
        setError(getFinaryError(err))
      },
    })
  }

  function onFileSelected(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (file) handleFileUpload(file)
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

  function onDrop(e: React.DragEvent) {
    e.preventDefault()
    setDragOver(false)
    const file = e.dataTransfer.files?.[0]
    if (file) handleFileUpload(file)
  }

  // ----- Mapping -----

  function initMappings(preview: FinaryPreviewResponse) {
    const initialMappings: FinaryAccountMapping[] = preview.accounts.map((account) => ({
      finaryId: account.finaryId,
      finaryName: account.finaryName,
      finaryCategory: account.finaryCategory,
      action: 'CREATE_NEW' as FinaryMappingAction,
      targetAccountId: undefined,
      newAccount: {
        name: account.finaryName,
        type: account.suggestedType,
        provider: account.finaryInstitution,
        currency: account.nativeCurrency,
        color: ACCOUNT_COLORS[preview.accounts.indexOf(account) % ACCOUNT_COLORS.length],
      },
    }))
    setMappings(initialMappings)
  }

  function setMappingAction(index: number, action: FinaryMappingAction) {
    const mapping = mappings[index]
    const account = previewData?.accounts[index]

    if (action === 'SKIP') {
      updateMapping(index, { action, targetAccountId: undefined, newAccount: undefined })
    } else if (action === 'MAP_EXISTING') {
      updateMapping(index, { action, newAccount: undefined })
    } else {
      updateMapping(index, {
        action,
        targetAccountId: undefined,
        newAccount: {
          name: mapping.newAccount?.name ?? account?.finaryName ?? '',
          type: mapping.newAccount?.type ?? account?.suggestedType ?? 'OTHER',
          provider: mapping.newAccount?.provider ?? account?.finaryInstitution ?? 'Finary',
          currency: mapping.newAccount?.currency ?? account?.nativeCurrency ?? 'EUR',
          color: mapping.newAccount?.color ?? ACCOUNT_COLORS[0],
        },
      })
    }
  }

  function updateMapping(index: number, patch: Partial<FinaryAccountMapping>) {
    setMappings((prev) =>
      prev.map((m, i) => (i === index ? { ...m, ...patch } : m))
    )
  }

  function updateNewAccountField(index: number, field: keyof NewAccountForm, value: string) {
    const current = mappings[index].newAccount ?? { ...defaultNewAccount }
    updateMapping(index, { newAccount: { ...current, [field]: value } })
  }

  function handleImport() {
    if (!previewData) return
    executeWithMappings(previewData.fileToken, mappings)
  }

  function handleDelete() {
    deleteMutation.mutate(undefined, {
      onSuccess: () => setShowDeleteConfirm(false),
    })
  }

  function resetWizard() {
    setStep(1)
    setPreviewData(null)
    setMappings([])
    setImportResult(null)
    setError(null)
    setTotpRequired(false)
    setTotpCode('')
    setIsApiSync(false)
  }

  function getFinaryError(err: unknown): string {
    const status = getErrorStatus(err)
    if (status === 502) return t('sync.finary.serviceUnavailable')
    const detail = getErrorDetail(err)
    if (detail) return detail
    return t('sync.finary.syncFailed')
  }

  const hasSkipAll = mappings.every((m) => m.action === 'SKIP')

  // ----- Render -----

  if (statusLoading) {
    return <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
  }

  return (
    <div className="space-y-6">
      {/* Connection status card */}
      {isConnected && connectionStatus && (
        <Card size="sm">
          <CardContent className="flex items-center justify-between py-4">
            <div className="flex items-center gap-3">
              {isTotpRequired ? (
                <Badge className="bg-amber-500/10 text-amber-600 dark:text-amber-400">
                  {t('sync.finary.totpRequired')}
                </Badge>
              ) : (
                <Badge className="bg-green-500/10 text-green-600 dark:text-green-400">
                  {t('sync.finary.connected')}
                </Badge>
              )}
              <span className="text-sm text-muted-foreground">{connectionStatus.maskedEmail}</span>
              {connectionStatus.lastSyncedAt && (
                <span className="text-xs text-muted-foreground">
                  {t('sync.finary.lastSync')}: {formatDate(connectionStatus.lastSyncedAt)}
                </span>
              )}
              {!connectionStatus.lastSyncedAt && (
                <span className="text-xs text-muted-foreground">{t('sync.finary.neverSynced')}</span>
              )}
            </div>
          </CardContent>
        </Card>
      )}

      {/* Error banner */}
      {error && (
        <div className="flex items-center gap-2 rounded-lg bg-destructive/10 px-4 py-3 text-sm text-destructive">
          <X className="size-4 shrink-0" />
          <span className="flex-1">{error}</span>
          <Button variant="ghost" size="icon-xs" onClick={() => setError(null)}>
            <X className="size-3" />
          </Button>
        </div>
      )}

      {/* Step indicator (when in wizard) */}
      {(step === 2 || step === 3) && (
        <div className="flex items-center justify-center gap-2">
          {([1, 2, 3] as const).map((s) => (
            <div key={s} className="flex items-center gap-2">
              <div
                className={`flex size-8 items-center justify-center rounded-full text-sm font-medium transition-colors ${
                  step >= s ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground'
                }`}
              >
                {step > s ? <CheckCircle2 className="size-4" /> : s}
              </div>
              <span className="text-sm text-muted-foreground">{t(`sync.finary.step${s}`)}</span>
              {s < 3 && <div className={`mx-1 h-px w-8 ${step > s ? 'bg-primary' : 'bg-muted'}`} />}
            </div>
          ))}
        </div>
      )}

      {/* Step 1: Login form (not connected) OR Sync (connected) */}
      {step === 1 && (
        <div className="mx-auto max-w-lg space-y-4">
          {!isConnected ? (
            /* Login form */
            <form onSubmit={handleLogin} className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="finary-email">{t('sync.finary.email')}</Label>
                <Input
                  id="finary-email"
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder={t('sync.finary.emailPlaceholder')}
                  required
                  autoFocus
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="finary-password">{t('sync.finary.password')}</Label>
                <div className="relative">
                  <Input
                    id="finary-password"
                    type={showPassword ? 'text' : 'password'}
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    required
                    className="pr-10"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword((p) => !p)}
                    className="absolute top-1/2 right-3 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                  >
                    {showPassword ? <X className="size-4" /> : <span className="text-xs">•••</span>}
                  </button>
                </div>
              </div>
              <Button type="submit" disabled={loading || !email || !password} className="w-full">
                {loading && <Loader2 className="size-4 animate-spin" />}
                {t('sync.finary.login')}
              </Button>
            </form>
          ) : (
            /* Connected: Sync + TOTP */
            <div className="space-y-4">
              <div className="flex gap-3">
                <Button onClick={handleAutoSync} disabled={loading} className="flex-1">
                  {loading ? (
                    <>
                      <Loader2 className="size-4 animate-spin" />
                      {t('sync.finary.syncing')}
                    </>
                  ) : (
                    <>
                      <RefreshCw className="size-4" />
                      {t('sync.finary.sync')}
                    </>
                  )}
                </Button>
                <Button
                  variant="destructive"
                  onClick={() => setShowDeleteConfirm(true)}
                  disabled={deleteMutation.isPending}
                >
                  <LogOut className="size-4" />
                  {t('sync.finary.deleteConnection')}
                </Button>
              </div>

              {(totpRequired || isTotpRequired) && (
                <div className="flex gap-2">
                  <div className="flex-1">
                    <Label htmlFor="finary-totp">{t('sync.finary.totp')}</Label>
                    <Input
                      id="finary-totp"
                      value={totpCode}
                      onChange={(e) => setTotpCode(e.target.value)}
                      placeholder="000000"
                      maxLength={6}
                      className="mt-1"
                    />
                  </div>
                  <Button
                    className="mt-6"
                    onClick={handleSync}
                    disabled={totpCode.length !== 6 || loading}
                  >
                    <ArrowRight />
                  </Button>
                </div>
              )}
            </div>
          )}

          {/* File upload divider */}
          <div className="flex items-center gap-3">
            <div className="h-px flex-1 bg-border" />
            <span className="text-xs text-muted-foreground">ou</span>
            <div className="h-px flex-1 bg-border" />
          </div>

          {/* File upload zone */}
          <div
            className={`flex flex-col items-center justify-center gap-4 rounded-2xl border-2 border-dashed p-12 text-center transition-colors ${
              dragOver ? 'border-primary bg-primary/5' : 'border-muted-foreground/25 hover:border-muted-foreground/50'
            }`}
            onDragOver={(e) => { e.preventDefault(); setDragOver(true) }}
            onDragLeave={() => setDragOver(false)}
            onDrop={onDrop}
          >
            <Upload className="size-6 text-muted-foreground" />
            <div>
              <p className="font-medium">{t('sync.finary.uploadFile')}</p>
              <p className="mt-1 text-sm text-muted-foreground">{t('sync.finary.uploadHint')}</p>
            </div>
            <Button variant="outline" onClick={() => fileInputRef.current?.click()} disabled={loading}>
              <Upload />
              {t('sync.finary.uploadFile')}
            </Button>
            <input ref={fileInputRef} type="file" accept=".xlsx" className="hidden" onChange={onFileSelected} />
          </div>
        </div>
      )}

      {/* Step 2: Account Mapping */}
      {step === 2 && previewData && (
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <Button variant="ghost" size="sm" onClick={() => { setStep(1); setPreviewData(null) }}>
              <ArrowLeft />
              {t('sync.finary.back')}
            </Button>
            <Button onClick={handleImport} disabled={loading || hasSkipAll}>
              {loading ? t('common.loading') : t('sync.finary.import')}
              <ArrowRight />
            </Button>
          </div>

          <div className="space-y-3">
            {previewData.accounts.map((account, index) => (
              <MappingCard
                key={account.finaryCategory + account.finaryId}
                account={account}
                mapping={mappings[index]}
                existingAccounts={previewData.existingPicsouAccounts}
                onActionChange={(action) => setMappingAction(index, action)}
                onTargetChange={(id) => updateMapping(index, { targetAccountId: id })}
                onNewAccountField={(field, value) => updateNewAccountField(index, field, value)}
              />
            ))}
          </div>
        </div>
      )}

      {/* Step 3: Results */}
      {step === 3 && importResult && (
        <div className="mx-auto max-w-lg space-y-6">
          <div className="grid grid-cols-2 gap-4">
            <ResultStat label={t('sync.finary.accountsCreated')} value={importResult.accountsCreated} color="text-emerald-600" />
            <ResultStat label={t('sync.finary.accountsMapped')} value={importResult.accountsMapped} color="text-blue-600" />
            <ResultStat label={t('sync.finary.accountsSkipped')} value={importResult.accountsSkipped} color="text-muted-foreground" />
            <ResultStat label={t('sync.finary.transactionsImported')} value={importResult.transactionsImported} color="text-violet-600" />
          </div>

          {importResult.importedAccounts.length > 0 && (
            <Card size="sm">
              <CardContent className="space-y-2 pt-0">
                {importResult.importedAccounts.map((account) => (
                  <div key={account.id} className="flex items-center gap-3 rounded-lg px-3 py-2">
                    <div className="size-3 shrink-0 rounded-full" style={{ backgroundColor: account.color }} />
                    <div className="flex-1 min-w-0">
                      <p className="truncate text-sm font-medium">{account.name}</p>
                      <Badge variant="secondary">{account.type}</Badge>
                    </div>
                    <CurrencyDisplay value={account.currentBalance} />
                  </div>
                ))}
              </CardContent>
            </Card>
          )}

          <div className="flex justify-center">
            <Button onClick={resetWizard}>
              <CheckCircle2 />
              {t('sync.finary.done')}
            </Button>
          </div>
        </div>
      )}

      {/* Delete confirmation */}
      <ConfirmDialog
        open={showDeleteConfirm}
        onOpenChange={setShowDeleteConfirm}
        title={t('sync.finary.deleteConnection')}
        description={t('sync.finary.deleteConnectionConfirm')}
        onConfirm={handleDelete}
        loading={deleteMutation.isPending}
      />
    </div>
  )
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

function MappingCard({
  account,
  mapping,
  existingAccounts,
  onActionChange,
  onTargetChange,
  onNewAccountField,
}: {
  account: FinaryPreviewResponse['accounts'][number]
  mapping: FinaryAccountMapping
  existingAccounts: Account[]
  onActionChange: (action: FinaryMappingAction) => void
  onTargetChange: (id: number) => void
  onNewAccountField: (field: keyof NewAccountForm, value: string) => void
}) {
  const { t } = useTranslation()

  return (
    <Card size="sm">
      <CardContent className="space-y-3 pt-0">
        <div className="flex items-center justify-between">
          <div className="min-w-0">
            <p className="truncate font-medium">{account.finaryName}</p>
            <p className="text-sm text-muted-foreground">
              {account.finaryInstitution} &middot; {account.finaryCategory}
            </p>
          </div>
          <div className="text-right shrink-0 ml-3">
            <CurrencyDisplay value={account.currentBalance} />
            <p className="text-xs text-muted-foreground">{account.transactionCount} tx</p>
          </div>
        </div>

        <div className="flex gap-1.5">
          {(['SKIP', 'MAP_EXISTING', 'CREATE_NEW'] as const).map((action) => (
            <Button
              key={action}
              variant={mapping.action === action ? 'default' : 'outline'}
              size="xs"
              onClick={() => onActionChange(action)}
            >
              {t(`sync.finary.${action === 'SKIP' ? 'skip' : action === 'MAP_EXISTING' ? 'mapExisting' : 'createNew'}`)}
            </Button>
          ))}
        </div>

        {mapping.action === 'MAP_EXISTING' && (
          <select
            className="h-10 w-full rounded-xl border border-input bg-background text-foreground px-4 text-sm outline-none [color-scheme:light] dark:[color-scheme:dark]"
            value={mapping.targetAccountId ?? ''}
            onChange={(e) => {
              const val = e.target.value
              if (val) onTargetChange(Number(val))
            }}
          >
            <option value="" disabled>
              {t('sync.finary.mapExisting')}...
            </option>
            {existingAccounts.map((acc) => (
              <option key={acc.id} value={acc.id}>
                {acc.name} ({acc.type}) &mdash; <CurrencyDisplay value={acc.currentBalance} />
              </option>
            ))}
          </select>
        )}

        {mapping.action === 'CREATE_NEW' && mapping.newAccount && (
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1">
              <Label>{t('accounts.accountName')}</Label>
              <Input
                value={mapping.newAccount.name}
                onChange={(e) => onNewAccountField('name', e.target.value)}
                placeholder={account.finaryName}
              />
            </div>
            <div className="space-y-1">
              <Label>{t('sync.exchanges.type')}</Label>
              <select
                className="h-10 w-full rounded-xl border border-input bg-background text-foreground px-4 text-sm outline-none [color-scheme:light] dark:[color-scheme:dark]"
                value={mapping.newAccount.type}
                onChange={(e) => onNewAccountField('type', e.target.value)}
              >
                {ACCOUNT_TYPES.map(({ value, labelKey }) => (
                  <option key={value} value={value}>
                    {t(labelKey)}
                  </option>
                ))}
              </select>
            </div>
            <div className="space-y-1">
              <Label>{t('accounts.provider')}</Label>
              <Input
                value={mapping.newAccount.provider}
                onChange={(e) => onNewAccountField('provider', e.target.value)}
              />
            </div>
            <div className="space-y-1">
              <Label>{t('common.currency')}</Label>
              <Input
                value={mapping.newAccount.currency}
                onChange={(e) => onNewAccountField('currency', e.target.value)}
                maxLength={3}
              />
            </div>
            <div className="space-y-1 sm:col-span-2">
              <Label>{t('accounts.color')}</Label>
              <div className="flex flex-wrap gap-2">
                {ACCOUNT_COLORS.map((color) => (
                  <button
                    key={color}
                    type="button"
                    className={`size-8 rounded-full border-2 transition-[border-color,box-shadow] ${
                      mapping.newAccount?.color === color
                        ? 'border-background ring-2 ring-foreground'
                        : 'border-transparent'
                    }`}
                    style={{ backgroundColor: color }}
                    onClick={() => onNewAccountField('color', color)}
                  />
                ))}
              </div>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function ResultStat({ label, value, color }: { label: string; value: number; color: string }) {
  return (
    <div className="rounded-xl bg-muted/50 p-4 text-center">
      <p className={`text-2xl font-semibold ${color}`}>{value}</p>
      <p className="mt-1 text-xs text-muted-foreground">{label}</p>
    </div>
  )
}
