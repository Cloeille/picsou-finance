import { useMemo, useState, type ComponentType } from 'react'
import { useTranslation } from 'react-i18next'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import {
  AmexLogo,
  AmundiLogo,
  BanquePopulaireLogo,
  BitcoinLogo,
  BnpParibasLogo,
  BourseDirectLogo,
  BoursoBankLogo,
  CaisseEpargneLogo,
  CorumLogo,
  DegiroLogo,
  EnableBankingLogo,
  EthereumLogo,
  FinaryLogo,
  FortuneoLogo,
  IbkrLogo,
  LaBanquePostaleLogo,
  NatixisLogo,
  RevolutLogo,
  SimplefinLogo,
  SofidyLogo,
  TradeRepublicLogo,
  WiseLogo,
} from '@/pages/setup/components/IntegrationLogos'
import {
  BackButton,
  BankWizard,
  ExchangeWizard,
  FinaryWizard,
  RevolutWizard,
  TradeRepublicWizard,
  WalletWizard,
} from '@/components/shared/AddAccountWizards'
import { AccountForm } from '@/components/shared/AccountForm'
import { AddPropertyModal } from '@/components/property/AddPropertyModal'
import { BourseDirectPanel } from '@/components/sync/BourseDirectPanel'
import { BoursoPanel } from '@/components/sync/BoursoPanel'
import { CaisseEpargnePanel } from '@/components/sync/CaisseEpargnePanel'
import { BanquePopulairePanel } from '@/components/sync/BanquePopulairePanel'
import { LbpPanel } from '@/components/sync/LbpPanel'
import { BnpPanel } from '@/components/sync/BnpPanel'
import { DegiroPanel } from '@/components/sync/DegiroPanel'
import { AmundiPanel } from '@/components/sync/AmundiPanel'
import { NatixisPanel } from '@/components/sync/NatixisPanel'
import { FortuneoPanel } from '@/components/sync/FortuneoPanel'
import { AmexPanel } from '@/components/sync/AmexPanel'
import { CorumPanel } from '@/components/sync/CorumPanel'
import { SofidyPanel } from '@/components/sync/SofidyPanel'
import { LouvePanel } from '@/components/sync/LouvePanel'
import { IbkrPanel } from '@/components/sync/IbkrPanel'
import { SimplefinPanel } from '@/components/sync/SimplefinPanel'
import { WisePanel } from '@/components/sync/WisePanel'
import { useCreateAccount, useUpdateDebtMetadata } from '@/features/accounts/hooks'
import {
  Building2,
  HousePlus,
  PenLine,
  ArrowRight,
  Search,
  X,
} from 'lucide-react'
import type { Account, AccountRequest, AccountType } from '@/types/api'

// ---------------------------------------------------------------------------
// Props & types
// ---------------------------------------------------------------------------

interface AddAccountModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Skip the source picker and open the manual form with this type preselected. */
  defaultType?: AccountType
  /** Called with the created account, so the caller can land on its detail page. */
  onCreated?: (account: Account) => void
}

type WizardStep =
  | 'selector' | 'banks' | 'exchanges' | 'wallets' | 'tr' | 'revolut' | 'bourso' | 'caisseEpargne' | 'banquePopulaire'
  | 'lbp' | 'bnp' | 'bourseDirect' | 'degiro' | 'ibkr' | 'simplefin' | 'wise' | 'amundi' | 'natixis' | 'fortuneo' | 'amex' | 'corum' | 'sofidy' | 'louve'
  | 'finary' | 'property' | 'manual'

// ---------------------------------------------------------------------------
// Source config
// ---------------------------------------------------------------------------

type SourceCategory = 'all' | 'banks' | 'investing' | 'crypto' | 'manual'

const SOURCE_CATEGORIES: SourceCategory[] = ['all', 'banks', 'investing', 'crypto', 'manual']

const SOURCES: Array<{
  key: WizardStep
  category: Exclude<SourceCategory, 'all'>
  icon: ComponentType<{ className?: string }>
  labelKey: string
  descKey: string
}> = [
  { key: 'banks', category: 'banks', icon: EnableBankingLogo, labelKey: 'sync.banks.title', descKey: 'addAccount.desc.banks' },
  { key: 'bnp', category: 'banks', icon: BnpParibasLogo, labelKey: 'sync.bnp.title', descKey: 'addAccount.desc.bnp' },
  { key: 'bourso', category: 'banks', icon: BoursoBankLogo, labelKey: 'sync.bourso.title', descKey: 'addAccount.desc.bourso' },
  { key: 'fortuneo', category: 'banks', icon: FortuneoLogo, labelKey: 'sync.fortuneo.title', descKey: 'addAccount.desc.fortuneo' },
  { key: 'lbp', category: 'banks', icon: LaBanquePostaleLogo, labelKey: 'sync.lbp.title', descKey: 'addAccount.desc.lbp' },
  { key: 'caisseEpargne', category: 'banks', icon: CaisseEpargneLogo, labelKey: 'sync.caisseEpargne.title', descKey: 'addAccount.desc.caisseEpargne' },
  { key: 'banquePopulaire', category: 'banks', icon: BanquePopulaireLogo, labelKey: 'sync.banquePopulaire.title', descKey: 'addAccount.desc.banquePopulaire' },
  { key: 'revolut', category: 'banks', icon: RevolutLogo, labelKey: 'sync.revolut.title', descKey: 'addAccount.desc.revolut' },
  { key: 'amex', category: 'banks', icon: AmexLogo, labelKey: 'sync.amex.title', descKey: 'addAccount.desc.amex' },
  { key: 'wise', category: 'banks', icon: WiseLogo, labelKey: 'sync.wise.title', descKey: 'addAccount.desc.wise' },
  { key: 'simplefin', category: 'banks', icon: SimplefinLogo, labelKey: 'sync.simplefin.title', descKey: 'addAccount.desc.simplefin' },
  { key: 'tr', category: 'investing', icon: TradeRepublicLogo, labelKey: 'sync.tr.title', descKey: 'addAccount.desc.tr' },
  { key: 'bourseDirect', category: 'investing', icon: BourseDirectLogo, labelKey: 'sync.bourseDirect.title', descKey: 'addAccount.desc.bourseDirect' },
  { key: 'degiro', category: 'investing', icon: DegiroLogo, labelKey: 'sync.degiro.title', descKey: 'addAccount.desc.degiro' },
  { key: 'ibkr', category: 'investing', icon: IbkrLogo, labelKey: 'sync.ibkr.title', descKey: 'addAccount.desc.ibkr' },
  { key: 'amundi', category: 'investing', icon: AmundiLogo, labelKey: 'sync.amundi.title', descKey: 'addAccount.desc.amundi' },
  { key: 'natixis', category: 'investing', icon: NatixisLogo, labelKey: 'sync.natixis.title', descKey: 'addAccount.desc.natixis' },
  { key: 'corum', category: 'investing', icon: CorumLogo, labelKey: 'sync.corum.title', descKey: 'addAccount.desc.corum' },
  { key: 'sofidy', category: 'investing', icon: SofidyLogo, labelKey: 'sync.sofidy.title', descKey: 'addAccount.desc.sofidy' },
  { key: 'louve', category: 'investing', icon: Building2, labelKey: 'sync.louve.title', descKey: 'addAccount.desc.louve' },
  { key: 'exchanges', category: 'crypto', icon: BitcoinLogo, labelKey: 'sync.exchanges.title', descKey: 'addAccount.desc.exchanges' },
  { key: 'wallets', category: 'crypto', icon: EthereumLogo, labelKey: 'sync.wallets.title', descKey: 'addAccount.desc.wallets' },
  { key: 'finary', category: 'manual', icon: FinaryLogo, labelKey: 'sync.finary.title', descKey: 'addAccount.desc.finary' },
  { key: 'property', category: 'manual', icon: HousePlus, labelKey: 'property.add.source', descKey: 'addAccount.desc.property' },
  { key: 'manual', category: 'manual', icon: PenLine, labelKey: 'addAccount.manual', descKey: 'addAccount.desc.manual' },
]

function normalizeQuery(v: string): string {
  return v.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim()
}

// Steps that are nothing but a sync Panel behind a Back button.
const PANEL_STEPS: Partial<Record<WizardStep, ComponentType<{ onConnected?: () => void }>>> = {
  bourso: BoursoPanel,
  caisseEpargne: CaisseEpargnePanel,
  banquePopulaire: BanquePopulairePanel,
  lbp: LbpPanel,
  bnp: BnpPanel,
  bourseDirect: BourseDirectPanel,
  degiro: DegiroPanel,
  ibkr: IbkrPanel,
  simplefin: SimplefinPanel,
  wise: WisePanel,
  amundi: AmundiPanel,
  natixis: NatixisPanel,
  fortuneo: FortuneoPanel,
  amex: AmexPanel,
  corum: CorumPanel,
  sofidy: SofidyPanel,
  louve: LouvePanel,
}

// ---------------------------------------------------------------------------
// Main component
// ---------------------------------------------------------------------------

export function AddAccountModal({ open, onOpenChange, defaultType, onCreated }: AddAccountModalProps) {
  const { t } = useTranslation()
  const createAccount = useCreateAccount()
  const updateDebt = useUpdateDebtMetadata()
  const [step, setStep] = useState<WizardStep>('selector')
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState<SourceCategory>('all')
  const manualDefaults = useMemo(
    () => (defaultType ? { type: defaultType } : undefined),
    [defaultType],
  )

  const filteredSources = useMemo(() => {
    const q = normalizeQuery(query)
    return SOURCES.filter((s) => {
      if (category !== 'all' && s.category !== category) return false
      if (!q) return true
      return normalizeQuery(`${t(s.labelKey)} ${t(s.descKey)}`).includes(q)
    })
  }, [category, query, t])

  const [showManualForm, setShowManualForm] = useState(false)
  const [showPropertyForm, setShowPropertyForm] = useState(false)
  const Panel = PANEL_STEPS[step]

  // "Create a loan" from the Receivables tab skips the source picker: the manual form is
  // the only route, and it is open as soon as the caller supplies the type.
  const manualOpen = showManualForm || (open && Boolean(defaultType))

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
      setQuery('')
      setCategory('all')
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
    openedAt?: string
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
      openedAt: data.openedAt || undefined,
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
    if (defaultType) onOpenChange(false)
    onCreated?.(created)
  }

  return (
    <>
      <Dialog open={open && !defaultType} onOpenChange={handleDialogChange}>
        {/* sm:max-w-xl, not just max-w-xl: DialogContent's own `sm:max-w-sm` is a different
            Tailwind variant, so tailwind-merge keeps it and an unprefixed max-w-xl silently has
            no effect above 640px — which is what squeezed this wizard into 24rem. */}
        <DialogContent
          className={cn(
            'max-w-2xl sm:max-w-2xl',
            step === 'selector'
              ? 'flex h-[min(680px,calc(100dvh-2rem))] flex-col overflow-hidden'
              : 'max-h-[calc(100dvh-2rem)] overflow-y-auto'
          )}
        >
          <DialogHeader className="shrink-0">
            <DialogTitle>
              {step === 'selector' ? t('addAccount.title') : t(`sync.${step === 'tr' ? 'tr' : step}.title`)}
            </DialogTitle>
            <DialogDescription />
          </DialogHeader>

          <>
              {step === 'selector' && (
                <div className="flex min-h-0 flex-1 flex-col gap-3">
                  <div className="relative shrink-0">
                    <Search
                      className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
                      aria-hidden="true"
                    />
                    <Input
                      type="search"
                      value={query}
                      onChange={(e) => setQuery(e.target.value)}
                      placeholder={t('sync.searchPlaceholder')}
                      aria-label={t('sync.searchPlaceholder')}
                      className="pl-10 pr-10"
                    />
                    {query && (
                      <button
                        type="button"
                        onClick={() => setQuery('')}
                        aria-label={t('setup.integrations.clearSearch')}
                        className="absolute right-1 top-1/2 inline-flex size-8 -translate-y-1/2 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                      >
                        <X className="size-4" aria-hidden="true" />
                      </button>
                    )}
                  </div>

                  <div
                    role="tablist"
                    aria-label={t('sync.categoriesLabel')}
                    className="flex w-full shrink-0 flex-wrap items-center gap-1 rounded-lg bg-muted p-1"
                  >
                    {SOURCE_CATEGORIES.map((cat) => {
                      const active = category === cat
                      return (
                        <button
                          key={cat}
                          role="tab"
                          type="button"
                          aria-selected={active}
                          onClick={() => setCategory(cat)}
                          className={cn(
                            'inline-flex h-8 flex-auto items-center justify-center rounded-md px-2.5 text-xs font-medium whitespace-nowrap transition-[background-color,color]',
                            active
                              ? 'bg-primary text-primary-foreground'
                              : 'text-muted-foreground hover:text-foreground'
                          )}
                        >
                          {t(`addAccount.categories.${cat}`)}
                        </button>
                      )
                    })}
                  </div>

                  <div className="min-h-0 flex-1 overflow-y-auto pr-1">
                    {filteredSources.length > 0 ? (
                      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                        {filteredSources.map(({ key, icon: Icon, labelKey, descKey }) => (
                          <Button
                            key={key}
                            variant="outline"
                            className="h-auto justify-start gap-3 rounded-xl px-3.5 py-3"
                            onClick={() => handleSourceClick(key)}
                          >
                            <span className="inline-flex size-8 shrink-0 items-center justify-center text-foreground/80">
                              <Icon className="size-5" />
                            </span>
                            <div className="min-w-0 flex-1 whitespace-normal text-left">
                              <p className="text-sm font-medium leading-snug">{t(labelKey)}</p>
                              <p className="mt-0.5 line-clamp-2 text-xs font-normal text-muted-foreground" title={t(descKey)}>
                                {t(descKey)}
                              </p>
                            </div>
                            <ArrowRight className="size-4 shrink-0 text-muted-foreground" />
                          </Button>
                        ))}
                      </div>
                    ) : (
                      <div className="flex h-full min-h-48 flex-col items-center justify-center gap-2 rounded-xl border border-dashed border-border/70 p-6 text-center">
                        <p className="text-sm text-muted-foreground">{t('setup.integrations.empty')}</p>
                        <Button
                          type="button"
                          variant="outline"
                          size="xs"
                          onClick={() => {
                            setQuery('')
                            setCategory('all')
                          }}
                        >
                          {t('setup.integrations.resetFilter')}
                        </Button>
                      </div>
                    )}
                  </div>
                </div>
              )}

              {step === 'banks' && <BankWizard onDone={handleDone} onBack={() => setStep('selector')} />}
              {step === 'exchanges' && <ExchangeWizard onDone={handleDone} onBack={() => setStep('selector')} />}
              {step === 'wallets' && <WalletWizard onDone={handleDone} onBack={() => setStep('selector')} />}
              {step === 'tr' && <TradeRepublicWizard onDone={handleDone} onBack={() => setStep('selector')} />}
              {step === 'revolut' && <RevolutWizard onDone={handleDone} onBack={() => setStep('selector')} />}
              {Panel && (
                <>
                  <BackButton onClick={() => setStep('selector')} />
                  <Panel onConnected={handleDone} />
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
        open={manualOpen}
        onOpenChange={(next) => {
          setShowManualForm(next)
          if (!next && defaultType) onOpenChange(false)
        }}
        onSubmit={handleManualSubmit}
        defaultValues={manualDefaults}
        title={t('addAccount.manual')}
        loading={createAccount.isPending}
      />
    </>
  )
}
