import { useMemo, useState, type ComponentType } from 'react'
import { useTranslation } from 'react-i18next'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
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
import { IbkrPanel } from '@/components/sync/IbkrPanel'
import { SimplefinPanel } from '@/components/sync/SimplefinPanel'
import { WisePanel } from '@/components/sync/WisePanel'
import { useCreateAccount, useUpdateDebtMetadata } from '@/features/accounts/hooks'
import {
  Landmark,
  HousePlus,
  ArrowLeftRight,
  Wallet,
  Smartphone,
  CreditCard,
  Building2,
  FileSpreadsheet,
  PenLine,
  ArrowRight,
  BriefcaseBusiness,
  TrendingUp,
  PiggyBank,
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
  | 'lbp' | 'bnp' | 'bourseDirect' | 'degiro' | 'ibkr' | 'simplefin' | 'wise' | 'amundi' | 'natixis' | 'fortuneo' | 'amex' | 'corum' | 'sofidy'
  | 'finary' | 'property' | 'manual'

// ---------------------------------------------------------------------------
// Source config
// ---------------------------------------------------------------------------

const SOURCES: { key: WizardStep; icon: typeof Landmark; labelKey: string; descKey: string }[] = [
  { key: 'banks', icon: Landmark, labelKey: 'sync.banks.title', descKey: 'addAccount.desc.banks' },
  { key: 'exchanges', icon: ArrowLeftRight, labelKey: 'sync.exchanges.title', descKey: 'addAccount.desc.exchanges' },
  { key: 'wallets', icon: Wallet, labelKey: 'sync.wallets.title', descKey: 'addAccount.desc.wallets' },
  { key: 'tr', icon: Smartphone, labelKey: 'sync.tr.title', descKey: 'addAccount.desc.tr' },
  { key: 'revolut', icon: CreditCard, labelKey: 'sync.revolut.title', descKey: 'addAccount.desc.revolut' },
  { key: 'bourso', icon: Landmark, labelKey: 'sync.bourso.title', descKey: 'addAccount.desc.bourso' },
  { key: 'caisseEpargne', icon: Landmark, labelKey: 'sync.caisseEpargne.title', descKey: 'addAccount.desc.caisseEpargne' },
  { key: 'banquePopulaire', icon: Landmark, labelKey: 'sync.banquePopulaire.title', descKey: 'addAccount.desc.banquePopulaire' },
  { key: 'lbp', icon: Landmark, labelKey: 'sync.lbp.title', descKey: 'addAccount.desc.lbp' },
  { key: 'bnp', icon: Landmark, labelKey: 'sync.bnp.title', descKey: 'addAccount.desc.bnp' },
  { key: 'bourseDirect', icon: BriefcaseBusiness, labelKey: 'sync.bourseDirect.title', descKey: 'addAccount.desc.bourseDirect' },
  { key: 'degiro', icon: TrendingUp, labelKey: 'sync.degiro.title', descKey: 'addAccount.desc.degiro' },
  { key: 'ibkr', icon: BriefcaseBusiness, labelKey: 'sync.ibkr.title', descKey: 'addAccount.desc.ibkr' },
  { key: 'simplefin', icon: Landmark, labelKey: 'sync.simplefin.title', descKey: 'addAccount.desc.simplefin' },
  { key: 'wise', icon: PiggyBank, labelKey: 'sync.wise.title', descKey: 'addAccount.desc.wise' },
  { key: 'amundi', icon: PiggyBank, labelKey: 'sync.amundi.title', descKey: 'addAccount.desc.amundi' },
  { key: 'natixis', icon: PiggyBank, labelKey: 'sync.natixis.title', descKey: 'addAccount.desc.natixis' },
  { key: 'fortuneo', icon: PiggyBank, labelKey: 'sync.fortuneo.title', descKey: 'addAccount.desc.fortuneo' },
  { key: 'amex', icon: CreditCard, labelKey: 'sync.amex.title', descKey: 'addAccount.desc.amex' },
  { key: 'corum', icon: Building2, labelKey: 'sync.corum.title', descKey: 'addAccount.desc.corum' },
  { key: 'sofidy', icon: Building2, labelKey: 'sync.sofidy.title', descKey: 'addAccount.desc.sofidy' },
  { key: 'finary', icon: FileSpreadsheet, labelKey: 'sync.finary.title', descKey: 'addAccount.desc.finary' },
  { key: 'property', icon: HousePlus, labelKey: 'property.add.source', descKey: 'addAccount.desc.property' },
  { key: 'manual', icon: PenLine, labelKey: 'addAccount.manual', descKey: 'addAccount.desc.manual' },
]

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
}

// ---------------------------------------------------------------------------
// Main component
// ---------------------------------------------------------------------------

export function AddAccountModal({ open, onOpenChange, defaultType, onCreated }: AddAccountModalProps) {
  const { t } = useTranslation()
  const createAccount = useCreateAccount()
  const updateDebt = useUpdateDebtMetadata()
  const [step, setStep] = useState<WizardStep>('selector')
  const manualDefaults = useMemo(
    () => (defaultType ? { type: defaultType } : undefined),
    [defaultType],
  )

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
        <DialogContent className="max-h-[calc(100dvh-2rem)] max-w-xl overflow-y-auto sm:max-w-xl">
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
                      {/* Button is inline-flex + whitespace-nowrap: without min-w-0 and whitespace-normal the
                          text column keeps its one-line width, overflows the row and pushes the arrow out. */}
                      <div className="min-w-0 flex-1 whitespace-normal text-left">
                        <p className="text-sm font-medium">{t(labelKey)}</p>
                        <p className="line-clamp-2 text-xs text-muted-foreground" title={t(descKey)}>{t(descKey)}</p>
                      </div>
                      <ArrowRight className="size-4 text-muted-foreground shrink-0" />
                    </Button>
                  ))}
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
