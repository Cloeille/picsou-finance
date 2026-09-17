import { useContext, useState, useRef } from 'react'
import { OTPInputContext } from 'input-otp'
import { useTranslation } from 'react-i18next'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Avatar, AvatarImage, AvatarFallback } from '@/components/ui/avatar'
import { Badge } from '@/components/ui/badge'
import { AccountForm } from '@/components/shared/AccountForm'
import { AddPropertyModal } from '@/components/property/AddPropertyModal'
import { BankCountrySelect, DEFAULT_BANK_COUNTRY } from '@/components/shared/BankCountrySelect'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { BourseDirectPanel } from '@/components/sync/BourseDirectPanel'
import { BoursoPanel } from '@/components/sync/BoursoPanel'
import { DegiroPanel } from '@/components/sync/DegiroPanel'
import { AmundiPanel } from '@/components/sync/AmundiPanel'
import { IbkrPanel } from '@/components/sync/IbkrPanel'
import {
  ACCOUNT_COLORS,
  ACCOUNT_TYPES,
  EXCHANGE_API_KEY_MAX_LENGTH,
  EXCHANGE_API_SECRET_MAX_LENGTH,
  TR_VERIFICATION_CODE_LENGTH,
} from '@/lib/constants'
import { extractErrorMessage, formatTrAuthError, getErrorStatus, getErrorDetail } from '@/lib/errors'
import { useCreateAccount, useUpdateDebtMetadata } from '@/features/accounts/hooks'
import {
  useSearchInstitutions,
  useInitiateBankSync,
  useInitiateTrAuth,
  useCompleteTrAuth,
  useAddCryptoExchange,
  useAddCryptoWallet,
  useFinaryConnectionStatus,
  useFinaryLogin,
  usePreviewFinaryFile,
  usePreviewFinaryApi,
  useImportFinary,
  useExecuteFinaryApiSync,
  useCheckFinaryTotp,
} from '@/features/sync/hooks'
import { InputOTP, InputOTPGroup, InputOTPSlot } from '@/components/ui/input-otp'
import {
  Landmark,
  HousePlus,
  ArrowLeftRight,
  Wallet,
  Smartphone,
  FileSpreadsheet,
  PenLine,
  ArrowRight,
  ArrowLeft,
  Loader2,
  CheckCircle2,
  Search,
  Eye,
  EyeOff,
  Upload,
  ShieldCheck,
  RefreshCw,
  BriefcaseBusiness,
  TrendingUp,
  PiggyBank,
} from 'lucide-react'
import type { ExchangeType, ChainType, AccountRequest, FinaryPreviewResponse, FinaryAccountMapping, FinaryMappingAction, FinaryImportResultResponse, AccountType } from '@/types/api'
import { initialFinaryMappings } from '@/features/sync/finary-mappings'
import { SUPPORTED_CHAINS, SUPPORTED_EXCHANGES, exchangeRequiresApiSecret } from '@/types/api'

// ---------------------------------------------------------------------------
// Props & types
// ---------------------------------------------------------------------------

interface AddAccountModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

type WizardStep =
  | 'selector' | 'banks' | 'exchanges' | 'wallets' | 'tr' | 'bourso'
  | 'bourseDirect' | 'degiro' | 'ibkr' | 'amundi' | 'finary' | 'property' | 'manual'

/**
 * Masked variant of InputOTPSlot — replaces the typed character with a bullet
 * so the slot reads like a password field. We can't edit components/ui/input-otp
 * (shadcn primitives), so we re-derive slot state from OTPInputContext here.
 */
function MaskedOTPSlot({ index }: { index: number }) {
  const ctx = useContext(OTPInputContext)
  const slot = ctx?.slots[index]
  const isActive = slot?.isActive
  const hasFakeCaret = slot?.hasFakeCaret
  const hasChar = slot?.char != null && slot.char !== ''
  return (
    <div
      data-active={isActive}
      className="relative flex size-10 items-center justify-center border-y border-r border-input bg-input/20 text-sm transition-[border-color,box-shadow,background-color] outline-none first:rounded-l-xl first:border-l last:rounded-r-xl aria-invalid:border-destructive data-[active=true]:z-10 data-[active=true]:border-ring data-[active=true]:ring-2 data-[active=true]:ring-ring/30 dark:bg-input/30"
    >
      {hasChar ? '•' : null}
      {hasFakeCaret && (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
          <div className="h-4 w-px animate-caret-blink bg-foreground duration-1000" />
        </div>
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Source config
// ---------------------------------------------------------------------------

const SOURCES: { key: WizardStep; icon: typeof Landmark; labelKey: string; descKey: string }[] = [
  { key: 'banks', icon: Landmark, labelKey: 'sync.banks.title', descKey: 'addAccount.desc.banks' },
  { key: 'exchanges', icon: ArrowLeftRight, labelKey: 'sync.exchanges.title', descKey: 'addAccount.desc.exchanges' },
  { key: 'wallets', icon: Wallet, labelKey: 'sync.wallets.title', descKey: 'addAccount.desc.wallets' },
  { key: 'tr', icon: Smartphone, labelKey: 'sync.tr.title', descKey: 'addAccount.desc.tr' },
  { key: 'bourso', icon: Landmark, labelKey: 'sync.bourso.title', descKey: 'addAccount.desc.bourso' },
  { key: 'bourseDirect', icon: BriefcaseBusiness, labelKey: 'sync.bourseDirect.title', descKey: 'addAccount.desc.bourseDirect' },
  { key: 'degiro', icon: TrendingUp, labelKey: 'sync.degiro.title', descKey: 'addAccount.desc.degiro' },
  { key: 'ibkr', icon: BriefcaseBusiness, labelKey: 'sync.ibkr.title', descKey: 'addAccount.desc.ibkr' },
  { key: 'amundi', icon: PiggyBank, labelKey: 'sync.amundi.title', descKey: 'addAccount.desc.amundi' },
  { key: 'finary', icon: FileSpreadsheet, labelKey: 'sync.finary.title', descKey: 'addAccount.desc.finary' },
  { key: 'property', icon: HousePlus, labelKey: 'property.add.source', descKey: 'addAccount.desc.property' },
  { key: 'manual', icon: PenLine, labelKey: 'addAccount.manual', descKey: 'addAccount.desc.manual' },
]

// ---------------------------------------------------------------------------
// Main component
// ---------------------------------------------------------------------------

export function AddAccountModal({ open, onOpenChange }: AddAccountModalProps) {
  const { t } = useTranslation()
  const createAccount = useCreateAccount()
  const updateDebt = useUpdateDebtMetadata()
  const [step, setStep] = useState<WizardStep>('selector')
  const [showManualForm, setShowManualForm] = useState(false)
  const [showPropertyForm, setShowPropertyForm] = useState(false)

  function handleSourceClick(key: string) {
    if (key === 'manual') {
      onOpenChange(false)
      setShowManualForm(true)
      return
    }
    if (key === 'property') {
      // Its own guided flow rather than a step here: it creates the account, saves the
      // description and runs the first estimate in one pass.
      onOpenChange(false)
      setShowPropertyForm(true)
      return
    }
    setStep(key as WizardStep)
  }

  function handleDialogChange(open: boolean) {
    if (open) {
      setStep('selector')
    }
    onOpenChange(open)
  }

  function handleDone() {
    setStep('selector')
    onOpenChange(false)
  }

  async function handleManualSubmit(data: {
    name: string
    type: AccountType
    provider?: string
    currency: string
    currentBalance?: number
    isManual: boolean
    color: string
    ticker?: string
    institutionId?: string
    borrowedAmount?: number
    interestRatePct?: number
    monthlyPayment?: number
    insuranceMonthly?: number
    fileFees?: number
    startDate?: string
    endDate?: string
  }) {
    const request: AccountRequest = {
      name: data.name,
      type: data.type,
      provider: data.provider || undefined,
      currency: data.currency,
      currentBalance: data.currentBalance,
      isManual: true,
      color: data.color,
      ticker: data.ticker || undefined,
      // Set only when a bank was picked from the catalog; the backend resolves its logo from it.
      institutionId: data.institutionId,
    }
    const created = await createAccount.mutateAsync(request)

    if (data.type === 'LOAN' && data.borrowedAmount && data.borrowedAmount > 0) {
      await updateDebt.mutateAsync({
        id: created.id,
        data: {
          borrowedAmount: data.borrowedAmount,
          interestRate: data.interestRatePct != null ? data.interestRatePct / 100 : undefined,
          monthlyPayment: data.monthlyPayment,
          insuranceMonthly: data.insuranceMonthly,
          fileFees: data.fileFees,
          lenderName: data.provider,
          startDate: data.startDate || undefined,
          endDate: data.endDate || undefined,
        },
      })
    }

    setShowManualForm(false)
  }

  return (
    <>
      <Dialog open={open} onOpenChange={handleDialogChange}>
        {/* sm:max-w-xl, not just max-w-xl: DialogContent's own `sm:max-w-sm` is a different
            Tailwind variant, so tailwind-merge keeps it and an unprefixed max-w-xl silently has
            no effect above 640px — which is what squeezed this wizard into 24rem. */}
        <DialogContent className="max-w-xl sm:max-w-xl">
          <DialogHeader>
            <DialogTitle>
              {step === 'selector' ? t('addAccount.title') : t(`sync.${step === 'tr' ? 'tr' : step}.title`)}
            </DialogTitle>
            <DialogDescription />
          </DialogHeader>

          <>
              {step === 'selector' && (
                <div className="grid grid-cols-1 gap-2">
                  {SOURCES.map(({ key, icon: Icon, labelKey, descKey }) => (
                    <Button
                      key={key}
                      variant="outline"
                      className="h-auto justify-start gap-3 px-4 py-3"
                      onClick={() => handleSourceClick(key)}
                    >
                      <Icon className="size-5 text-muted-foreground shrink-0" />
                      <div className="text-left">
                        <p className="text-sm font-medium">{t(labelKey)}</p>
                        <p className="text-xs text-muted-foreground">{t(descKey)}</p>
                      </div>
                      <ArrowRight className="size-4 text-muted-foreground ml-auto shrink-0" />
                    </Button>
                  ))}
                </div>
              )}

              {step === 'banks' && <BankWizard onDone={handleDone} onBack={() => setStep('selector')} />}
              {step === 'exchanges' && <ExchangeWizard onDone={handleDone} onBack={() => setStep('selector')} />}
              {step === 'wallets' && <WalletWizard onDone={handleDone} onBack={() => setStep('selector')} />}
              {step === 'tr' && <TradeRepublicWizard onDone={handleDone} onBack={() => setStep('selector')} />}
              {step === 'bourso' && (
                <>
                  <BackButton onClick={() => setStep('selector')} />
                  <BoursoPanel onConnected={handleDone} />
                </>
              )}
              {step === 'bourseDirect' && (
                <>
                  <BackButton onClick={() => setStep('selector')} />
                  <BourseDirectPanel onConnected={handleDone} />
                </>
              )}
              {step === 'degiro' && (
                <>
                  <BackButton onClick={() => setStep('selector')} />
                  <DegiroPanel onConnected={handleDone} />
                </>
              )}
              {step === 'ibkr' && (
                <>
                  <BackButton onClick={() => setStep('selector')} />
                  <IbkrPanel onConnected={handleDone} />
                </>
              )}
              {step === 'amundi' && (
                <>
                  <BackButton onClick={() => setStep('selector')} />
                  <AmundiPanel onConnected={handleDone} />
                </>
              )}
              {step === 'finary' && <FinaryWizard onDone={handleDone} onBack={() => setStep('selector')} />}
            </>
        </DialogContent>
      </Dialog>

      {showPropertyForm && (
        <AddPropertyModal open onOpenChange={setShowPropertyForm} />
      )}

      <AccountForm
        open={showManualForm}
        onOpenChange={setShowManualForm}
        onSubmit={handleManualSubmit}
        title={t('addAccount.manual')}
        loading={createAccount.isPending}
      />
    </>
  )
}

// ---------------------------------------------------------------------------
// Shared: Back button + Success state
// ---------------------------------------------------------------------------

function BackButton({ onClick }: { onClick: () => void }) {
  const { t } = useTranslation()
  return (
    <Button variant="ghost" size="sm" className="mb-2" onClick={onClick}>
      <ArrowLeft className="size-4" />
      {t('common.back')}
    </Button>
  )
}

function SuccessState({ message }: { message: string }) {
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

function InstitutionLogo({ logoUrl }: { logoUrl?: string | null }) {
  return (
    <Avatar className="size-4 shrink-0 rounded-sm after:rounded-sm">
      {logoUrl && <AvatarImage src={logoUrl} alt="" className="rounded-sm object-contain" />}
      <AvatarFallback className="rounded-sm bg-transparent">
        <Landmark className="size-4 text-muted-foreground" />
      </AvatarFallback>
    </Avatar>
  )
}

function BankWizard({ onBack }: { onDone: () => void; onBack: () => void }) {
  const { t } = useTranslation()
  const [searchQuery, setSearchQuery] = useState('')
  const [country, setCountry] = useState(DEFAULT_BANK_COUNTRY)
  const [error, setError] = useState<string | null>(null)

  const {
    data: institutions,
    isError: searchFailed,
    isLoading: searchLoading,
    error: searchError,
  } = useSearchInstitutions(searchQuery.trim(), country)
  const initiateMutation = useInitiateBankSync()

  const searchEnabled = searchQuery.trim().length >= 2

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
        <div className="flex items-start gap-2">
          <div className="relative flex-1">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 size-4 text-muted-foreground" />
            <Input
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder={t('sync.banks.searchPlaceholder')}
              className="pl-10"
              autoFocus
            />
          </div>
          <BankCountrySelect value={country} onChange={setCountry} />
        </div>

        {searchLoading && (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="size-4 animate-spin" />
            {t('common.loading')}
          </div>
        )}

        {searchEnabled && searchFailed && !searchLoading && (
          <div className="flex items-center gap-2 rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive">
            <span className="flex-1">
              {extractErrorMessage(searchError, t('sync.banks.searchError'))}
            </span>
          </div>
        )}

        {searchEnabled && !searchFailed && institutions && institutions.length > 0 && (
          <div className="space-y-2 max-h-64 overflow-y-auto">
            {institutions.map((inst) => (
              <div
                key={inst.id}
                className="grid grid-cols-[minmax(0,1fr)_2.5rem_auto] items-center gap-4 rounded-lg border px-3 py-2"
              >
                <div className="flex min-w-0 items-center gap-2">
                  <InstitutionLogo logoUrl={inst.logoUrl} />
                  <span className="min-w-0 text-sm font-medium leading-5">{inst.name}</span>
                  {inst.psuType === 'business' && (
                    <Badge variant="outline" title={t('sync.banks.proBadgeTitle')}>
                      {t('sync.banks.proBadge')}
                    </Badge>
                  )}
                </div>
                <span className="justify-self-center text-xs text-muted-foreground">{inst.country}</span>
                <Button
                  size="sm"
                  className="justify-self-end"
                  onClick={() => handleConnect(inst.id, inst.name)}
                  disabled={initiateMutation.isPending}
                >
                  {t('sync.banks.connect')}
                </Button>
              </div>
            ))}
          </div>
        )}

        {searchEnabled && !searchFailed && institutions && institutions.length === 0 && !searchLoading && (
          <p className="text-sm text-muted-foreground">{t('sync.banks.noConnections')}</p>
        )}
      </div>
    </>
  )
}

// ---------------------------------------------------------------------------
// Wizard: Exchanges
// ---------------------------------------------------------------------------

function ExchangeWizard({ onBack }: { onDone: () => void; onBack: () => void }) {
  const { t } = useTranslation()
  const [exchangeType, setExchangeType] = useState<ExchangeType>('BINANCE')
  const [apiKey, setApiKey] = useState('')
  const [apiSecret, setApiSecret] = useState('')
  const [showSecret, setShowSecret] = useState(false)
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const addMutation = useAddCryptoExchange()
  const requiresSecret = exchangeRequiresApiSecret(exchangeType)

  function selectExchange(type: ExchangeType) {
    setExchangeType(type)
    // Drop anything typed under the previous exchange: sending a secret to a single-key exchange
    // is a 400, and it would be entirely self-inflicted.
    setApiSecret('')
    setShowSecret(false)
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    addMutation.mutate(
      { type: exchangeType, apiKey, apiSecret: requiresSecret ? apiSecret : undefined },
      {
        onSuccess: () => setDone(true),
        onError: (err: unknown) => {
          // The fallback names only the credentials this exchange actually takes — a Meria user
          // told to check a secret goes looking for a field the form never showed them.
          const fallback = requiresSecret
            ? 'sync.exchanges.connectError' : 'sync.exchanges.connectErrorKeyOnly'
          setError(getErrorDetail(err) || (err as { message?: string })?.message || t(fallback))
        },
      },
    )
  }

  if (done) {
    return (
      <>
        <BackButton onClick={onBack} />
        <SuccessState message={t('addAccount.exchangeConnected')} />
      </>
    )
  }

  return (
    <>
      <BackButton onClick={onBack} />
      {error && (
        <div className="flex items-center gap-2 rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive">
          <span className="flex-1">{error}</span>
          <Button variant="ghost" size="sm" onClick={() => setError(null)}>x</Button>
        </div>
      )}
      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="space-y-2">
          <Label>{t('sync.exchanges.type')}</Label>
          {/* wrap: these buttons are wide (px-8) and there is no room for three in a row on a
              narrow viewport — without it the row stretches the whole form past the dialog. */}
          <div className="flex flex-wrap gap-2">
            {SUPPORTED_EXCHANGES.map((exchange) => (
              <Button
                key={exchange.type}
                type="button"
                variant={exchangeType === exchange.type ? 'default' : 'outline'}
                size="sm"
                onClick={() => selectExchange(exchange.type)}
              >
                {exchange.type}
              </Button>
            ))}
          </div>
        </div>

        <div className="space-y-2">
          <Label htmlFor="exchange-api-key">{t('sync.exchanges.apiKey')}</Label>
          <Input
            id="exchange-api-key"
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            placeholder={t('sync.exchanges.apiKey')}
            required
            maxLength={EXCHANGE_API_KEY_MAX_LENGTH}
          />
          {!requiresSecret && (
            <p className="text-xs text-muted-foreground">{t('sync.exchanges.apiKeyOnly')}</p>
          )}
        </div>

        {requiresSecret && (
          <div className="space-y-2">
            <Label htmlFor="exchange-api-secret">{t('sync.exchanges.apiSecret')}</Label>
            <div className="relative">
              <Input
                id="exchange-api-secret"
                type={showSecret ? 'text' : 'password'}
                value={apiSecret}
                onChange={(e) => setApiSecret(e.target.value)}
                placeholder={t('sync.exchanges.apiSecret')}
                required
                maxLength={EXCHANGE_API_SECRET_MAX_LENGTH}
                className="pr-10"
              />
              <button
                type="button"
                onClick={() => setShowSecret((p) => !p)}
                aria-label={t(showSecret ? 'sync.exchanges.hideSecret' : 'sync.exchanges.showSecret')}
                className="absolute top-1/2 right-3 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              >
                {showSecret ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
              </button>
            </div>
          </div>
        )}

        <Button type="submit" disabled={addMutation.isPending} className="w-full">
          {addMutation.isPending && <Loader2 className="size-4 animate-spin" />}
          {t('sync.exchanges.connect')}
        </Button>
      </form>
    </>
  )
}

// ---------------------------------------------------------------------------
// Wizard: Wallets
// ---------------------------------------------------------------------------

function WalletWizard({ onBack }: { onDone: () => void; onBack: () => void }) {
  const { t } = useTranslation()
  const [chain, setChain] = useState<ChainType>('EVM')
  const [address, setAddress] = useState('')
  const [label, setLabel] = useState('')
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const addMutation = useAddCryptoWallet()

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    addMutation.mutate(
      { chain, address, label: label || undefined },
      {
        onSuccess: () => setDone(true),
        onError: (err: unknown) => {
          setError(getErrorDetail(err) || (err as { message?: string })?.message || t('sync.wallets.connectError'))
        },
      },
    )
  }

  if (done) {
    return (
      <>
        <BackButton onClick={onBack} />
        <SuccessState message={t('addAccount.walletConnected')} />
      </>
    )
  }

  return (
    <>
      <BackButton onClick={onBack} />
      {error && (
        <div className="flex items-center gap-2 rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive">
          <span className="flex-1">{error}</span>
          <Button variant="ghost" size="sm" onClick={() => setError(null)}>x</Button>
        </div>
      )}
      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="space-y-2">
          <Label>{t('sync.wallets.chain')}</Label>
          <div className="flex gap-2">
            {SUPPORTED_CHAINS.map((c) => (
              <Button
                key={c}
                type="button"
                variant={chain === c ? 'default' : 'outline'}
                size="sm"
                onClick={() => setChain(c)}
              >
                {c}
              </Button>
            ))}
          </div>
          {chain === 'EVM' && (
            <p className="text-xs text-muted-foreground">{t('sync.wallets.evmHint')}</p>
          )}
        </div>

        <div className="space-y-2">
          <Label htmlFor="wallet-address">{t('sync.wallets.address')}</Label>
          <Input
            id="wallet-address"
            value={address}
            onChange={(e) => setAddress(e.target.value)}
            placeholder={t('sync.wallets.address')}
            required
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="wallet-label">{t('sync.wallets.label')}</Label>
          <Input
            id="wallet-label"
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder={t('sync.wallets.label')}
          />
        </div>

        <Button type="submit" disabled={addMutation.isPending} className="w-full">
          {addMutation.isPending && <Loader2 className="size-4 animate-spin" />}
          <Wallet className="size-4" />
          {t('sync.wallets.track')}
        </Button>
      </form>
    </>
  )
}

// ---------------------------------------------------------------------------
// Wizard: Trade Republic
// ---------------------------------------------------------------------------

type TrState = 'IDLE' | 'AWAITING_TAN' | 'CONNECTED' | 'ERROR'

function TradeRepublicWizard({ onBack }: { onDone: () => void; onBack: () => void }) {
  const { t } = useTranslation()
  const [authState, setAuthState] = useState<TrState>('IDLE')
  const [phone, setPhone] = useState('')
  const [pin, setPin] = useState('')
  const [tan, setTan] = useState('')
  const [processId, setProcessId] = useState<string | null>(null)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  const initiateMutation = useInitiateTrAuth()
  const completeMutation = useCompleteTrAuth()

  function handleInitiate(e: React.FormEvent) {
    e.preventDefault()
    if (pin.length === 0) return
    initiateMutation.mutate(
      { phoneNumber: phone, pin },
      {
        onSuccess: (data) => {
          setProcessId(data.processId)
          setTan('')
          setAuthState('AWAITING_TAN')
          setErrorMsg(null)
        },
        onError: (err: unknown) => {
          setErrorMsg(formatTrAuthError(err, t))
          setProcessId(null)
          setTan('')
          setAuthState('IDLE')
        },
      },
    )
  }

  function handleTan(e: React.FormEvent) {
    e.preventDefault()
    if (!processId || tan.length !== TR_VERIFICATION_CODE_LENGTH) return
    completeMutation.mutate(
      { processId, tan },
      {
        onSuccess: () => {
          setAuthState('CONNECTED')
          setTan('')
          setPhone('')
          setPin('')
          setProcessId(null)
          setErrorMsg(null)
        },
        onError: (err: unknown) => {
          setErrorMsg(formatTrAuthError(err, t))
          setTan('')
          setAuthState('AWAITING_TAN')
        },
      },
    )
  }

  // Only dismiss the banner: the mutations' onError handlers already reset
  // authState/processId appropriately per failure type. Resetting to IDLE here
  // would throw away a still-valid TAN session after a completion failure.
  function handleRetry() {
    setErrorMsg(null)
  }

  if (authState === 'CONNECTED') {
    return (
      <>
        <BackButton onClick={onBack} />
        <SuccessState message={t('addAccount.trConnected')} />
      </>
    )
  }

  return (
    <>
      <BackButton onClick={onBack} />
      <div className="space-y-4">
        {errorMsg && (
          <div className="flex flex-col gap-3 rounded-lg bg-destructive/10 px-4 py-3 text-sm text-destructive">
            <span className="leading-6">{errorMsg}</span>
            <Button
              variant="ghost"
              size="sm"
              className="self-end px-4 text-sm text-destructive hover:bg-destructive/10 hover:text-destructive"
              onClick={handleRetry}
            >
              {t('sync.banks.retry')}
            </Button>
          </div>
        )}

        {authState !== 'AWAITING_TAN' && (
          <form onSubmit={handleInitiate} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="tr-phone">
                <Smartphone className="size-4 inline-block mr-1" />
                {t('sync.tr.phone')}
              </Label>
              <Input id="tr-phone" type="tel" value={phone} onChange={(e) => setPhone(e.target.value)} required placeholder="+49..." autoFocus />
            </div>
            <div className="space-y-2">
              <Label htmlFor="tr-pin">
                <ShieldCheck className="size-4 inline-block mr-1" />
                {t('sync.tr.pin')}
              </Label>
              <InputOTP maxLength={4} value={pin} onChange={setPin} autoFocus>
                <InputOTPGroup>
                  {[0, 1, 2, 3].map((i) => (
                    <MaskedOTPSlot key={i} index={i} />
                  ))}
                </InputOTPGroup>
              </InputOTP>
            </div>
            <Button type="submit" disabled={initiateMutation.isPending} className="w-full">
              {initiateMutation.isPending && <Loader2 className="size-4 animate-spin" />}
              {t('sync.tr.connect')}
            </Button>
          </form>
        )}

        {authState === 'AWAITING_TAN' && (
          <form onSubmit={handleTan} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="tr-tan">
                <ShieldCheck className="size-4 inline-block mr-1" />
                {t('sync.tr.tan')}
              </Label>
              <InputOTP
                id="tr-tan"
                maxLength={TR_VERIFICATION_CODE_LENGTH}
                value={tan}
                onChange={(value) => setTan(value.replace(/\D/g, '').slice(0, TR_VERIFICATION_CODE_LENGTH))}
                autoFocus
              >
                <InputOTPGroup>
                  {[0, 1, 2, 3].map((i) => (
                    <InputOTPSlot key={i} index={i} />
                  ))}
                </InputOTPGroup>
              </InputOTP>
            </div>
            <Button type="submit" disabled={completeMutation.isPending || tan.length !== TR_VERIFICATION_CODE_LENGTH} className="w-full">
              {completeMutation.isPending && <Loader2 className="size-4 animate-spin" />}
              {t('sync.tr.connect')}
            </Button>
          </form>
        )}
      </div>
    </>
  )
}

// ---------------------------------------------------------------------------
// Wizard: Finary (3-step: Login/Upload → Mapping → Results)
// ---------------------------------------------------------------------------

type FinaryStep = 1 | 2 | 3

function FinaryWizard({ onDone, onBack }: { onDone: () => void; onBack: () => void }) {
  const { t } = useTranslation()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const { data: connectionStatus } = useFinaryConnectionStatus()
  const loginMutation = useFinaryLogin()

  const isConnected = connectionStatus?.connected ?? false

  const [step, setStep] = useState<FinaryStep>(1)
  const [isApiSync, setIsApiSync] = useState(false)
  const [previewData, setPreviewData] = useState<FinaryPreviewResponse | null>(null)
  const [mappings, setMappings] = useState<FinaryAccountMapping[]>([])
  const [importResult, setImportResult] = useState<FinaryImportResultResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [totpRequired, setTotpRequired] = useState(false)
  const [totpCode, setTotpCode] = useState('')
  const [dragOver, setDragOver] = useState(false)

  // Login form state
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)

  const previewFileMutation = usePreviewFinaryFile()
  const previewApiMutation = usePreviewFinaryApi()
  const importMutation = useImportFinary()
  const executeApiMutation = useExecuteFinaryApiSync()
  const checkTotpMutation = useCheckFinaryTotp()

  // --- Login ---

  function handleLogin(e: React.FormEvent) {
    e.preventDefault()
    if (!email || !password) return
    setLoading(true)
    setError(null)
    loginMutation.mutate(
      { email, password },
      {
        onSuccess: () => {
          setEmail('')
          setPassword('')
          checkTotpMutation.mutate(undefined, {
            onSuccess: ({ totpRequired }) => {
              setLoading(false)
              if (totpRequired) {
                setTotpRequired(true)
              } else {
                handleApiSyncPreview()
              }
            },
            onError: (err: unknown) => {
              setLoading(false)
              setError(getErrorDetail(err) || t('common.retry'))
            },
          })
        },
        onError: () => {
          setLoading(false)
        },
      },
    )
  }

  // --- Sync ---

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
          setError(err instanceof Error ? err.message : t('common.retry'))
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
          setError(err instanceof Error ? err.message : t('common.retry'))
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
    }
    const onError = (err: unknown) => {
      setLoading(false)
      setError(err instanceof Error ? err.message : t('common.retry'))
    }

    if (isApiSync) {
      executeApiMutation.mutate({ syncToken: token, mappings: mappingsToUse }, { onSuccess, onError })
    } else {
      importMutation.mutate({ fileToken: token, mappings: mappingsToUse }, { onSuccess, onError })
    }
  }

  // --- File upload ---

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
        setError(err instanceof Error ? err.message : t('common.retry'))
      },
    })
  }

  function initMappings(preview: FinaryPreviewResponse) {
    setMappings(initialFinaryMappings(preview))
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

  // --- Step 2: Mapping ---

  function setMappingAction(index: number, action: FinaryMappingAction) {
    setMappings((prev) => prev.map((m, i) => {
      if (i !== index) return m
      const account = previewData?.accounts[index]
      if (action === 'SKIP') return { ...m, action, targetAccountId: undefined, newAccount: undefined }
      if (action === 'MAP_EXISTING') return { ...m, action, newAccount: undefined }
      return {
        ...m,
        action,
        targetAccountId: undefined,
        newAccount: {
          name: m.newAccount?.name ?? account?.finaryName ?? '',
          type: m.newAccount?.type ?? account?.suggestedType ?? 'OTHER' as AccountType,
          provider: m.newAccount?.provider ?? account?.finaryInstitution ?? 'Finary',
          currency: m.newAccount?.currency ?? account?.nativeCurrency ?? 'EUR',
          color: m.newAccount?.color ?? ACCOUNT_COLORS[0],
        },
      }
    }))
  }

  function updateNewAccountField(index: number, field: string, value: string) {
    setMappings((prev) => prev.map((m, i) => {
      if (i !== index || !m.newAccount) return m
      return { ...m, newAccount: { ...m.newAccount, [field]: value } }
    }))
  }

  function handleImport() {
    if (!previewData) return
    executeWithMappings(previewData.fileToken, mappings)
  }

  const hasSkipAll = mappings.every((m) => m.action === 'SKIP')

  return (
    <>
      <BackButton onClick={onBack} />

      {/* Step indicator */}
      <div className="flex items-center justify-center gap-2 mb-4">
        {([1, 2, 3] as const).map((s) => (
          <div key={s} className="flex items-center gap-2">
            <div
              className={`flex size-7 items-center justify-center rounded-full text-xs font-medium transition-colors ${
                step >= s ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground'
              }`}
            >
              {step > s ? <CheckCircle2 className="size-3.5" /> : s}
            </div>
            <span className="text-xs text-muted-foreground">{t(`sync.finary.step${s}`)}</span>
            {s < 3 && <div className={`mx-1 h-px w-6 ${step > s ? 'bg-primary' : 'bg-muted'}`} />}
          </div>
        ))}
      </div>

      {/* Error banner */}
      {error && (
        <div className="flex items-center gap-2 rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive mb-4">
          <span className="flex-1">{error}</span>
          <Button variant="ghost" size="sm" onClick={() => setError(null)}>x</Button>
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
              <Button onClick={handleSync} disabled={loading} className="w-full" size="sm">
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
                    <Input id="finary-totp" value={totpCode} onChange={(e) => setTotpCode(e.target.value)} placeholder="000000" maxLength={6} className="mt-1" />
                  </div>
                  <Button className="mt-4" onClick={handleSync} disabled={totpCode.length !== 6 || loading} size="sm">
                    <ArrowRight className="size-3.5" />
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
            className={`flex flex-col items-center justify-center gap-3 rounded-xl border-2 border-dashed p-8 text-center transition-colors ${
              dragOver ? 'border-primary bg-primary/5' : 'border-muted-foreground/25 hover:border-muted-foreground/50'
            }`}
            onDragOver={(e) => { e.preventDefault(); setDragOver(true) }}
            onDragLeave={() => setDragOver(false)}
            onDrop={onDrop}
          >
            <Upload className="size-5 text-muted-foreground" />
            <div>
              <p className="text-sm font-medium">{t('sync.finary.uploadFile')}</p>
              <p className="text-xs text-muted-foreground">{t('sync.finary.uploadHint')}</p>
            </div>
            <Button variant="outline" size="sm" onClick={() => fileInputRef.current?.click()} disabled={loading}>
              <Upload className="size-4" />
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
              <ArrowLeft className="size-4" />
              {t('sync.finary.back')}
            </Button>
            <Button onClick={handleImport} disabled={loading || hasSkipAll} size="sm">
              {loading ? t('common.loading') : t('sync.finary.import')}
              <ArrowRight className="size-4" />
            </Button>
          </div>

          <div className="space-y-3 max-h-80 overflow-y-auto">
            {previewData.accounts.map((account, index) => (
              <div key={account.finaryCategory + account.finaryId} className="rounded-lg border p-3 space-y-2">
                <div className="flex items-center justify-between">
                  <div className="min-w-0">
                    <p className="truncate text-sm font-medium">{account.finaryName}</p>
                    <p className="text-xs text-muted-foreground">{account.finaryInstitution} &middot; {account.finaryCategory}</p>
                  </div>
                  <div className="text-right shrink-0 ml-2">
                    <CurrencyDisplay value={account.currentBalance} />
                    <p className="text-xs text-muted-foreground">{account.transactionCount} tx</p>
                  </div>
                </div>

                <div className="flex gap-1.5">
                  {(['SKIP', 'MAP_EXISTING', 'CREATE_NEW'] as const).map((action) => (
                    <Button
                      key={action}
                      variant={mappings[index]?.action === action ? 'default' : 'outline'}
                      size="xs"
                      onClick={() => setMappingAction(index, action)}
                    >
                      {t(`sync.finary.${action === 'SKIP' ? 'skip' : action === 'MAP_EXISTING' ? 'mapExisting' : 'createNew'}`)}
                    </Button>
                  ))}
                </div>

                {mappings[index]?.action === 'MAP_EXISTING' && (
                  <select
                    className="h-10 w-full rounded-xl border border-input bg-background text-foreground px-4 text-sm outline-none [color-scheme:light] dark:[color-scheme:dark]"
                    value={mappings[index].targetAccountId ?? ''}
                    onChange={(e) => {
                      const val = e.target.value
                      if (val) {
                        setMappings((prev) => prev.map((m, i) => i === index ? { ...m, targetAccountId: Number(val) } : m))
                      }
                    }}
                  >
                    <option value="" disabled>{t('sync.finary.mapExisting')}...</option>
                    {previewData.existingPicsouAccounts.map((acc) => (
                      <option key={acc.id} value={acc.id}>{acc.name} ({acc.type})</option>
                    ))}
                  </select>
                )}

                {mappings[index]?.action === 'CREATE_NEW' && mappings[index].newAccount && (
                  <div className="grid gap-2 sm:grid-cols-2">
                    <div className="space-y-1">
                      <Label>{t('accounts.accountName')}</Label>
                      <Input
                        value={mappings[index].newAccount!.name}
                        onChange={(e) => updateNewAccountField(index, 'name', e.target.value)}
                      />
                    </div>
                    <div className="space-y-1">
                      <Label>{t('sync.exchanges.type')}</Label>
                      <select
                        className="h-10 w-full rounded-xl border border-input bg-background text-foreground px-4 text-sm outline-none [color-scheme:light] dark:[color-scheme:dark]"
                        value={mappings[index].newAccount!.type}
                        onChange={(e) => updateNewAccountField(index, 'type', e.target.value)}
                      >
                        {/* The shared list, not a local copy: deriving a label key from the
                            type name only held while every key was the lowercased value, and
                            broke the moment a type needed its own (livretA, employeeSavings). */}
                        {ACCOUNT_TYPES.map((at) => (
                          <option key={at.value} value={at.value}>{t(at.labelKey)}</option>
                        ))}
                      </select>
                    </div>
                    <div className="space-y-1">
                      <Label>{t('accounts.provider')}</Label>
                      <Input
                        value={mappings[index].newAccount!.provider ?? ''}
                        onChange={(e) => updateNewAccountField(index, 'provider', e.target.value)}
                      />
                    </div>
                    <div className="space-y-1">
                      <Label>{t('common.currency')}</Label>
                      <Input
                        value={mappings[index].newAccount!.currency}
                        onChange={(e) => updateNewAccountField(index, 'currency', e.target.value)}
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
                              mappings[index].newAccount?.color === color
                                ? 'border-background ring-2 ring-foreground'
                                : 'border-transparent'
                            }`}
                            style={{ backgroundColor: color }}
                            onClick={() => updateNewAccountField(index, 'color', color)}
                          />
                        ))}
                      </div>
                    </div>
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Step 3: Results */}
      {step === 3 && importResult && (
        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-3">
            <ResultStat label={t('sync.finary.accountsCreated')} value={importResult.accountsCreated} color="text-emerald-600" />
            <ResultStat label={t('sync.finary.accountsMapped')} value={importResult.accountsMapped} color="text-blue-600" />
            <ResultStat label={t('sync.finary.accountsSkipped')} value={importResult.accountsSkipped} color="text-muted-foreground" />
            <ResultStat label={t('sync.finary.transactionsImported')} value={importResult.transactionsImported} color="text-violet-600" />
          </div>

          {importResult.importedAccounts.length > 0 && (
            <div className="space-y-2">
              {importResult.importedAccounts.map((account) => (
                <div key={account.id} className="flex items-center gap-3 rounded-lg border px-3 py-2">
                  <div className="size-3 shrink-0 rounded-full" style={{ backgroundColor: account.color }} />
                  <div className="flex-1 min-w-0">
                    <p className="truncate text-sm font-medium">{account.name}</p>
                    <span className="text-xs text-muted-foreground">{account.type}</span>
                  </div>
                  <CurrencyDisplay value={account.currentBalance} />
                </div>
              ))}
            </div>
          )}

          <Button onClick={onDone} className="w-full">
            <CheckCircle2 className="size-4" />
            {t('sync.finary.done')}
          </Button>
        </div>
      )}
    </>
  )
}

function ResultStat({ label, value, color }: { label: string; value: number; color?: string }) {
  return (
    <div className="rounded-xl bg-muted/50 p-3 text-center">
      <p className={`text-xl font-semibold ${color ?? ''}`}>{value}</p>
      <p className="text-xs text-muted-foreground">{label}</p>
    </div>
  )
}
