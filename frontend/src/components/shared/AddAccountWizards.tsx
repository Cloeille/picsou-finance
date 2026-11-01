import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { TradeRepublicLogin } from '@/components/sync/TradeRepublicLogin'
import { CryptoWalletForm } from '@/components/sync/CryptoWalletForm'
import { CryptoExchangeForm } from '@/components/sync/CryptoExchangeForm'
import { InstitutionSearch } from '@/components/sync/InstitutionSearch'
import { getErrorDetail, formatApiError } from '@/lib/errors'
import {
  useInitiateBankSync,
  useRevolutStatus,
  useConfirmRevolutSync,
  useFinaryConnectionStatus,
  useFinaryLogin,
  useCheckFinaryTotp,
} from '@/features/sync/hooks'
import {
  FinaryMappingStep,
  FinaryResultStep,
  FinaryStepIndicator,
  FinaryUploadZone,
} from '@/components/sync/FinaryImportSteps'
import { useFinaryImport } from '@/features/sync/use-finary-import'
import { RevolutCredentialsForm, type RevolutCredentials } from '@/components/sync/RevolutCredentialsForm'
import { useRevolutDiscovery } from '@/features/sync/use-revolut-discovery'
import { RevolutSelectionCard } from '@/components/sync/RevolutSelectionCard'
import { revolutPhaseLabel } from '@/features/sync/revolut-phase'
import {
  ArrowRight,
  ArrowLeft,
  Loader2,
  CheckCircle2,
  Eye,
  EyeOff,
  RefreshCw,
  AlertTriangle,
} from 'lucide-react'


// ---------------------------------------------------------------------------
// Shared: Back button + Success state
// ---------------------------------------------------------------------------

export function BackButton({ onClick }: { onClick: () => void }) {
  const { t } = useTranslation()
  return (
    <Button variant="ghost" size="sm" className="mb-2" onClick={onClick}>
      <ArrowLeft className="size-4" />
      {t('common.back')}
    </Button>
  )
}

export function SuccessState({ message }: { message: string }) {
  return (
    <div className="flex flex-col items-center gap-3 py-10 text-center">
      <CheckCircle2 className="size-8 text-green-500" />
      <p className="text-sm font-medium">{message}</p>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Wizard: Banques
// ---------------------------------------------------------------------------

export function BankWizard({ onBack }: { onDone: () => void; onBack: () => void }) {
  const { t } = useTranslation()
  const [error, setError] = useState<string | null>(null)
  const initiateMutation = useInitiateBankSync()

  function handleConnect(institutionId: string, institutionName: string) {
    setError(null)
    initiateMutation.mutate(
      { institutionId, institutionName },
      {
        onSuccess: (data) => {
          window.location.href = data.authLink
        },
        onError: (err: unknown) => {
          setError(getErrorDetail(err) || (err as { message?: string })?.message || t('sync.banks.initiateError'))
        },
      },
    )
  }

  return (
    <>
      <BackButton onClick={onBack} />
      <div className="space-y-4">
        {error && (
          <div className="flex items-center gap-2 rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive">
            <span className="flex-1">{error}</span>
            <Button variant="ghost" size="sm" onClick={() => setError(null)}>x</Button>
          </div>
        )}
        <InstitutionSearch layout="modal" onConnect={handleConnect} connecting={initiateMutation.isPending} />
      </div>
    </>
  )
}

// ---------------------------------------------------------------------------
// Wizard: Exchanges
// ---------------------------------------------------------------------------

export function ExchangeWizard({ onBack }: { onDone: () => void; onBack: () => void }) {
  const { t } = useTranslation()
  const [done, setDone] = useState(false)

  return (
    <>
      <BackButton onClick={onBack} />
      {done
        ? <SuccessState message={t('addAccount.exchangeConnected')} />
        : <CryptoExchangeForm variant="modal" onSuccess={() => setDone(true)} />}
    </>
  )
}

// ---------------------------------------------------------------------------
// Wizard: Wallets
// ---------------------------------------------------------------------------

export function WalletWizard({ onBack }: { onDone: () => void; onBack: () => void }) {
  const { t } = useTranslation()
  const [done, setDone] = useState(false)

  return (
    <>
      <BackButton onClick={onBack} />
      {done
        ? <SuccessState message={t('addAccount.walletConnected')} />
        : <CryptoWalletForm variant="modal" onSuccess={() => setDone(true)} />}
    </>
  )
}

// ---------------------------------------------------------------------------
// Wizard: Trade Republic
// ---------------------------------------------------------------------------

export function TradeRepublicWizard({ onBack }: { onDone: () => void; onBack: () => void }) {
  const { t } = useTranslation()
  const [connected, setConnected] = useState(false)

  return (
    <>
      <BackButton onClick={onBack} />
      {connected
        ? <SuccessState message={t('addAccount.trConnected')} />
        : <TradeRepublicLogin variant="modal" onConnected={() => setConnected(true)} />}
    </>
  )
}

// ---------------------------------------------------------------------------
// Wizard: Revolut (on-demand phone+passcode sync — mirrors TradeRepublicWizard)
// ---------------------------------------------------------------------------

const EMPTY_REVOLUT_CREDENTIALS: RevolutCredentials = { phoneNumber: '', passcode: '', remember: false }

export function RevolutWizard({ onDone, onBack }: { onDone: () => void; onBack: () => void }) {
  const { t } = useTranslation()
  const [credentials, setCredentials] = useState<RevolutCredentials>(EMPTY_REVOLUT_CREDENTIALS)
  // Set once a discovery completes successfully; distinguishes a fresh mount (no result yet,
  // possibly stale cached progress from an earlier discovery elsewhere) from an actual result.
  const [resultReady, setResultReady] = useState(false)

  const { data: status } = useRevolutStatus()
  const confirmSync = useConfirmRevolutSync()

  const { errorMsg, setErrorMsg, isSyncing, progress, startPending, startDiscovery } = useRevolutDiscovery({
    onStart: () => setResultReady(false),
    onFinished: () => setResultReady(true),
  })

  const remembered = status?.remembered ?? false
  const busy = isSyncing || startPending

  function handleQuickSync() {
    startDiscovery({})
  }

  function handleFormSync(e: React.FormEvent) {
    e.preventDefault()
    if (!credentials.phoneNumber || credentials.passcode.length !== 6) return
    startDiscovery({ phoneNumber: credentials.phoneNumber, passcode: credentials.passcode })
  }

  function handleConfirm(selectedExternalIds: string[]) {
    confirmSync.mutate(
      { selectedExternalIds, remember: credentials.remember, voluntary: true },
      {
        onSuccess: onDone,
        onError: (err: unknown) => setErrorMsg(formatApiError(err, t)),
      },
    )
  }

  // Add-account is purely additive: only offer accounts not already imported.
  const newAccounts = (progress.data?.discovered ?? []).filter((d) => !d.alreadyImported)

  if (resultReady && newAccounts.length === 0) {
    return (
      <>
        <BackButton onClick={onBack} />
        <SuccessState message={t('sync.revolut.selection.allImported')} />
      </>
    )
  }

  return (
    <>
      <BackButton onClick={onBack} />
      <div className="space-y-4">
        {errorMsg && (
          <div className="flex items-center gap-2 rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive">
            <AlertTriangle className="size-4 shrink-0" />
            <span className="flex-1">{errorMsg}</span>
            <Button variant="ghost" size="sm" onClick={() => setErrorMsg(null)}>x</Button>
          </div>
        )}

        {isSyncing && (
          <div className="flex items-center gap-2 rounded-lg bg-muted/50 px-3 py-2 text-sm text-muted-foreground">
            <Loader2 className="size-4 animate-spin shrink-0" />
            {revolutPhaseLabel(t, progress.data)}
          </div>
        )}

        {resultReady && newAccounts.length > 0 && (
          <RevolutSelectionCard
            discovered={newAccounts}
            confirming={confirmSync.isPending}
            onConfirm={handleConfirm}
          />
        )}

        {!resultReady && (
          remembered ? (
            <Button onClick={handleQuickSync} disabled={busy} className="w-full">
              {busy ? <Loader2 className="size-4 animate-spin" /> : <RefreshCw className="size-4" />}
              {busy ? t('sync.revolut.syncing') : t('sync.revolut.sync')}
            </Button>
          ) : (
            <RevolutCredentialsForm
              variant="modal"
              value={credentials}
              onChange={setCredentials}
              onSubmit={handleFormSync}
              busy={busy}
            />
          )
        )}
      </div>
    </>
  )
}

// ---------------------------------------------------------------------------
// Wizard: Finary (3-step: Login/Upload → Mapping → Results)
// ---------------------------------------------------------------------------

export function FinaryWizard({ onDone, onBack }: { onDone: () => void; onBack: () => void }) {
  const { t } = useTranslation()
  const { data: connectionStatus } = useFinaryConnectionStatus()
  const loginMutation = useFinaryLogin()
  const checkTotpMutation = useCheckFinaryTotp()

  const isConnected = connectionStatus?.connected ?? false

  const wizard = useFinaryImport({
    errorMessage: (err) => (err instanceof Error ? err.message : t('common.retry')),
  })
  const { step, previewData, mappings, importResult, loading, error, totpRequired, totpCode } = wizard

  // Login form state
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)

  function handleLogin(e: React.FormEvent) {
    e.preventDefault()
    if (!email || !password) return
    wizard.setLoading(true)
    wizard.setError(null)
    loginMutation.mutate(
      { email, password },
      {
        onSuccess: () => {
          setEmail('')
          setPassword('')
          checkTotpMutation.mutate(undefined, {
            onSuccess: ({ totpRequired }) => {
              wizard.setLoading(false)
              if (totpRequired) {
                wizard.setTotpRequired(true)
              } else {
                wizard.previewApiSync()
              }
            },
            onError: (err: unknown) => {
              wizard.setLoading(false)
              wizard.setError(getErrorDetail(err) || t('common.retry'))
            },
          })
        },
        onError: () => {
          wizard.setLoading(false)
        },
      },
    )
  }

  return (
    <>
      <BackButton onClick={onBack} />

      <FinaryStepIndicator step={step} variant="modal" />

      {/* Error banner */}
      {error && (
        <div className="flex items-center gap-2 rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive mb-4">
          <span className="flex-1">{error}</span>
          <Button variant="ghost" size="sm" onClick={() => wizard.setError(null)}>x</Button>
        </div>
      )}

      {/* Step 1: Login form (not connected) OR Sync (connected) */}
      {step === 1 && (
        <div className="space-y-4">
          {!isConnected ? (
            /* Login form */
            <form onSubmit={handleLogin} className="space-y-3">
              <div className="space-y-1">
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
              <div className="space-y-1">
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
                    aria-label={showPassword ? t('auth.hidePassword') : t('auth.showPassword')}
                    className="absolute top-1/2 right-3 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                  >
                    {showPassword ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                  </button>
                </div>
              </div>
              <Button type="submit" disabled={loading || !email || !password} className="w-full" size="sm">
                {loading && <Loader2 className="size-3.5 animate-spin" />}
                {t('sync.finary.login')}
              </Button>
            </form>
          ) : (
            /* Connected: Sync + TOTP */
            <div className="space-y-3">
              {connectionStatus?.maskedEmail && (
                <p className="text-xs text-muted-foreground text-center">
                  {connectionStatus.maskedEmail}
                </p>
              )}
              <Button onClick={wizard.syncWithTotp} disabled={loading} className="w-full" size="sm">
                {loading ? (
                  <><Loader2 className="size-3.5 animate-spin" />{t('sync.finary.syncing')}</>
                ) : (
                  <><RefreshCw className="size-3.5" />{t('sync.finary.sync')}</>
                )}
              </Button>

              {totpRequired && (
                <div className="flex gap-2">
                  <div className="flex-1">
                    <Label htmlFor="finary-totp">{t('sync.finary.totp')}</Label>
                    <Input id="finary-totp" value={totpCode} onChange={(e) => wizard.setTotpCode(e.target.value)} placeholder="000000" maxLength={6} className="mt-1" />
                  </div>
                  <Button className="mt-4" onClick={wizard.syncWithTotp} disabled={totpCode.length !== 6 || loading} size="sm">
                    <ArrowRight className="size-3.5" />
                  </Button>
                </div>
              )}
            </div>
          )}

          <FinaryUploadZone variant="modal" onFile={wizard.uploadFile} disabled={loading} />
        </div>
      )}

      {/* Step 2: Account Mapping */}
      {step === 2 && previewData && (
        <FinaryMappingStep
          variant="modal"
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
        <FinaryResultStep variant="modal" result={importResult} onDone={onDone} />
      )}
    </>
  )
}
