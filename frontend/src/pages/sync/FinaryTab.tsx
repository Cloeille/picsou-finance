import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { getErrorStatus, getErrorDetail } from '@/lib/errors'
import { Button } from '@/components/ui/button'
import { AlertBanner } from '@/components/shared/AlertBanner'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { formatDate } from '@/lib/utils'
import {
  FinaryMappingStep,
  FinaryResultStep,
  FinaryStepIndicator,
  FinaryUploadZone,
} from '@/components/sync/FinaryImportSteps'
import {
  ArrowRight,
  X,
  Loader2,
  LogOut,
  RefreshCw,
} from 'lucide-react'
import {
  useFinaryConnectionStatus,
  useFinaryLogin,
  useFinaryDeleteSession,
  useFinaryAutoSync,
} from '@/features/sync/hooks'
import { useFinaryImport } from '@/features/sync/use-finary-import'
import type { FinaryAutoSyncResponse } from '@/types/api'

export function FinaryTab() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()

  const { data: connectionStatus, isLoading: statusLoading } = useFinaryConnectionStatus()
  const loginMutation = useFinaryLogin()
  const deleteMutation = useFinaryDeleteSession()
  const autoSyncMutation = useFinaryAutoSync()

  const isConnected = connectionStatus?.connected ?? false
  const isTotpRequired = connectionStatus?.status === 'TOTP_REQUIRED'

  const wizard = useFinaryImport({
    errorMessage: getFinaryError,
    onImported: () => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
  const { step, previewData, mappings, importResult, loading, error, totpRequired, totpCode } = wizard

  // Login form state
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)

  // Delete confirmation
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)

  // ----- Login -----

  function handleLogin(e: React.FormEvent) {
    e.preventDefault()
    if (!email || !password) return
    wizard.setLoading(true)
    wizard.setError(null)
    loginMutation.mutate(
      { email, password },
      {
        onSuccess: () => {
          wizard.setLoading(false)
          setEmail('')
          setPassword('')
          // Now trigger preview with stored credentials
          wizard.previewApiSync()
        },
        onError: () => wizard.setLoading(false),
      },
    )
  }

  // ----- Auto-sync (primary path when connected) -----

  function handleAutoSync() {
    wizard.setLoading(true)
    wizard.setError(null)
    autoSyncMutation.mutate(undefined, {
      onSuccess: (data: FinaryAutoSyncResponse) => {
        wizard.setLoading(false)
        if (data.status === 'OK') {
          toast.success(t('sync.finary.accountsSyncedToast', { count: data.accountsSynced }))
        } else if (data.status === 'NEEDS_MAPPING') {
          wizard.previewApiSync()
        } else if (data.status === 'TOTP_REQUIRED') {
          wizard.setTotpRequired(true)
        } else {
          wizard.setError(t('sync.finary.notConnected'))
        }
      },
      onError: (err: unknown) => {
        wizard.setLoading(false)
        wizard.setError(getFinaryError(err))
      },
    })
  }

  function handleDelete() {
    deleteMutation.mutate(undefined, {
      onSuccess: () => setShowDeleteConfirm(false),
    })
  }

  function getFinaryError(err: unknown): string {
    const status = getErrorStatus(err)
    if (status === 502) return t('sync.finary.serviceUnavailable')
    const detail = getErrorDetail(err)
    if (detail) return detail
    return t('sync.finary.syncFailed')
  }

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
                  {t('common.labelColon', { label: t('sync.finary.lastSync') })} {formatDate(connectionStatus.lastSyncedAt)}
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
        <AlertBanner
          variant="roomy"
          icon={<X className="size-4 shrink-0" />}
          action={
            <Button variant="ghost" size="icon-xs" onClick={() => wizard.setError(null)}>
              <X className="size-3" />
            </Button>
          }
        >
          {error}
        </AlertBanner>
      )}

      {/* Step indicator (when in wizard) */}
      {(step === 2 || step === 3) && <FinaryStepIndicator step={step} variant="page" />}

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
                      onChange={(e) => wizard.setTotpCode(e.target.value)}
                      placeholder="000000"
                      maxLength={6}
                      className="mt-1"
                    />
                  </div>
                  <Button
                    className="mt-6"
                    onClick={wizard.syncWithTotp}
                    disabled={totpCode.length !== 6 || loading}
                  >
                    <ArrowRight />
                  </Button>
                </div>
              )}
            </div>
          )}

          <FinaryUploadZone variant="page" onFile={wizard.uploadFile} disabled={loading} />
        </div>
      )}

      {/* Step 2: Account Mapping */}
      {step === 2 && previewData && (
        <FinaryMappingStep
          variant="page"
          preview={previewData}
          mappings={mappings}
          loading={loading}
          hasSkipAll={wizard.hasSkipAll}
          onBack={wizard.backToStart}
          onImport={wizard.importMapped}
          onActionChange={wizard.setMappingAction}
          onTargetChange={(index, id) => wizard.updateMapping(index, { targetAccountId: id })}
          onNewAccountField={wizard.updateNewAccountField}
        />
      )}

      {/* Step 3: Results */}
      {step === 3 && importResult && (
        <FinaryResultStep variant="page" result={importResult} onDone={wizard.reset} />
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
