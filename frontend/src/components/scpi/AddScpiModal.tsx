import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NumericInput } from '@/components/shared/NumericInput'
import { DateInput } from '@/components/shared/DateInput'
import { useCreateAccount, useScpiFunds, useUpdateScpiPosition } from '@/features/accounts/hooks'
import { formatApiError } from '@/lib/errors'
import { parseAmount } from '@/lib/utils'
import type { DistributionFrequency, DividendPolicy, ScpiPositionRequest } from '@/types/api'

interface AddScpiModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

const selectControlClassName =
  'flex h-10 w-full rounded-md border border-input bg-background text-foreground px-4 text-sm outline-none [color-scheme:light] dark:[color-scheme:dark]'

const num = (v: string): number | undefined => (v.trim() === '' ? undefined : parseAmount(v))

/**
 * One SCPI vehicle, in full ownership. There is no address and no floor area:
 * the open-data estimator cannot price a share, and must not be asked to.
 */
export function AddScpiModal({ open, onOpenChange }: AddScpiModalProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const createAccount = useCreateAccount()
  const updatePosition = useUpdateScpiPosition()
  // Funds a sync already saw with no account yet: picking one fills the name and the code,
  // so nobody has to find a provider's internal fund code by hand.
  const funds = useScpiFunds(open)
  // An ignored fund is listed too: picking it is how a member takes it back, since
  // setting its code lifts the tombstone.
  const pickable = (funds.data ?? []).filter(fund => fund.accountId == null
    && fund.lastOutcome !== 'MISSING_AT_PROVIDER')

  const [name, setName] = useState('')
  const [managementCompany, setManagementCompany] = useState('')
  const [shareCount, setShareCount] = useState('')
  const [subscriptionPrice, setSubscriptionPrice] = useState('')
  const [withdrawalPrice, setWithdrawalPrice] = useState('')
  const [dividendPolicy, setDividendPolicy] = useState<DividendPolicy>('CASH')
  const [annualDistribution, setAnnualDistribution] = useState('')
  const [distributionFrequency, setDistributionFrequency] = useState<DistributionFrequency | ''>('')
  const [jouissanceDate, setJouissanceDate] = useState('')
  const [isin, setIsin] = useState('')
  const [corumFundCode, setCorumFundCode] = useState('')
  const [sofidyFundCode, setSofidyFundCode] = useState('')
  const [createdAccountId, setCreatedAccountId] = useState<number | null>(null)

  const shares = num(shareCount)
  const distribution = num(annualDistribution)
  // Both set, or both empty. One without the other is a silent no-forecast.
  const amountSet = distribution != null
  const frequencySet = distributionFrequency !== ''
  const distributionPairOk = amountSet === frequencySet && (distribution == null || distribution >= 0)
  const canSubmit = name.trim() !== '' && shares != null && shares >= 0 && distributionPairOk
  const pending = createAccount.isPending || updatePosition.isPending
  const error = createAccount.error ?? updatePosition.error

  function reset() {
    setName('')
    setManagementCompany('')
    setShareCount('')
    setSubscriptionPrice('')
    setWithdrawalPrice('')
    setDividendPolicy('CASH')
    setAnnualDistribution('')
    setDistributionFrequency('')
    setJouissanceDate('')
    setIsin('')
    setCorumFundCode('')
    setSofidyFundCode('')
    setCreatedAccountId(null)
  }

  function close(next: boolean) {
    if (!next) reset()
    onOpenChange(next)
  }

  function pickFund(value: string) {
    const fund = pickable.find(f => `${f.provider}:${f.fundCode}` === value)
    if (!fund) return
    if (name.trim() === '' && fund.label) setName(fund.label)
    if (fund.provider === 'CORUM') setCorumFundCode(fund.fundCode)
    else setSofidyFundCode(fund.fundCode)
    if (managementCompany.trim() === '') setManagementCompany(fund.provider === 'CORUM' ? 'CORUM' : 'Sofidy')
  }

  async function submit() {
    if (!canSubmit || shares == null) return
    let accountId = createdAccountId
    if (accountId == null) {
      const created = await createAccount.mutateAsync({
        name: name.trim(),
        type: 'SCPI',
        provider: managementCompany.trim() || undefined,
        currency: 'EUR',
        isManual: true,
        color: '#7c3aed',
      })
      accountId = created.id
      setCreatedAccountId(accountId)
    }

    const position: ScpiPositionRequest = {
      isin: isin.trim() || null,
      managementCompany: managementCompany.trim() || null,
      corumFundCode: corumFundCode.trim() || null,
      sofidyFundCode: sofidyFundCode.trim() || null,
      shareCount: shares,
      subscriptionPriceEur: num(subscriptionPrice) ?? null,
      withdrawalPriceEur: num(withdrawalPrice) ?? null,
      dividendPolicy,
      jouissanceDate: jouissanceDate || null,
      annualDistributionPerShareEur: distribution != null && distribution >= 0 ? distribution : null,
      distributionFrequency: distributionFrequency || null,
    }
    await updatePosition.mutateAsync({ id: accountId, data: position })
    close(false)
    navigate(`/accounts/${accountId}`)
  }

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{t('scpi.add.title')}</DialogTitle>
          <DialogDescription>{t('scpi.add.description')}</DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          {pickable.length > 0 && (
            <div className="space-y-2">
              <Label htmlFor="scpi-seen-fund">{t('scpi.seenFund')}</Label>
              <select
                id="scpi-seen-fund"
                className={selectControlClassName}
                defaultValue=""
                onChange={(e) => pickFund(e.target.value)}
              >
                <option value="">{t('scpi.seenFundNone')}</option>
                {pickable.map(fund => (
                  <option key={`${fund.provider}:${fund.fundCode}`} value={`${fund.provider}:${fund.fundCode}`}>
                    {(fund.label ?? fund.fundCode) + ` (${fund.provider === 'CORUM' ? 'CORUM' : 'Sofidy'} ${fund.fundCode})`
                      + (fund.ignored ? ` — ${t('scpi.seenFundIgnored')}` : '')}
                  </option>
                ))}
              </select>
              <p className="text-xs text-muted-foreground">{t('scpi.seenFundHint')}</p>
            </div>
          )}
          <div className="space-y-2">
            <Label htmlFor="scpi-name">{t('scpi.name')}</Label>
            <Input id="scpi-name" value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className="space-y-2">
            <Label htmlFor="scpi-company">{t('scpi.managementCompany')}</Label>
            <Input
              id="scpi-company"
              value={managementCompany}
              onChange={(e) => setManagementCompany(e.target.value)}
            />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="scpi-shares">{t('scpi.shareCount')}</Label>
              <NumericInput id="scpi-shares" value={shareCount} onChange={(e) => setShareCount(e.target.value)} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="scpi-isin">{t('scpi.isin')}</Label>
              <Input id="scpi-isin" value={isin} maxLength={12} onChange={(e) => setIsin(e.target.value)} />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="scpi-withdrawal">{t('scpi.withdrawalPrice')}</Label>
              <NumericInput
                id="scpi-withdrawal"
                value={withdrawalPrice}
                onChange={(e) => setWithdrawalPrice(e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="scpi-subscription">{t('scpi.subscriptionPrice')}</Label>
              <NumericInput
                id="scpi-subscription"
                value={subscriptionPrice}
                onChange={(e) => setSubscriptionPrice(e.target.value)}
              />
            </div>
          </div>
          <p className="text-xs text-muted-foreground">{t('scpi.subscriptionHint')}</p>
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="scpi-annual-distribution">{t('scpi.annualDistribution')}</Label>
              <NumericInput
                id="scpi-annual-distribution"
                value={annualDistribution}
                onChange={(e) => setAnnualDistribution(e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="scpi-distribution-frequency">{t('scpi.distributionFrequency')}</Label>
              <select
                id="scpi-distribution-frequency"
                className={selectControlClassName}
                value={distributionFrequency}
                onChange={(e) => setDistributionFrequency(e.target.value as DistributionFrequency | '')}
              >
                <option value="">{t('scpi.frequencyNone')}</option>
                <option value="MONTHLY">{t('scpi.frequencyMonthly')}</option>
                <option value="QUARTERLY">{t('scpi.frequencyQuarterly')}</option>
              </select>
            </div>
          </div>
          <p className="text-xs text-muted-foreground">{t('scpi.annualDistributionHint')}</p>
          <details className="space-y-4 rounded-md border p-3">
          <summary className="cursor-pointer text-sm text-muted-foreground">{t('scpi.advancedLinks')}</summary>
          <div className="space-y-2">
            <Label htmlFor="scpi-corum-fund">{t('scpi.corumFundCode')}</Label>
            <Input
              id="scpi-corum-fund"
              value={corumFundCode}
              maxLength={40}
              onChange={(e) => setCorumFundCode(e.target.value.toUpperCase())}
            />
            <p className="text-xs text-muted-foreground">{t('scpi.corumFundHint')}</p>
          </div>
          <div className="space-y-2">
            <Label htmlFor="scpi-sofidy-fund">{t('scpi.sofidyFundCode')}</Label>
            <Input
              id="scpi-sofidy-fund"
              value={sofidyFundCode}
              maxLength={40}
              onChange={(e) => setSofidyFundCode(e.target.value.toUpperCase())}
            />
            <p className="text-xs text-muted-foreground">{t('scpi.sofidyFundHint')}</p>
          </div>
          </details>
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="scpi-dividend">{t('scpi.dividendPolicy')}</Label>
              <select
                id="scpi-dividend"
                className={selectControlClassName}
                value={dividendPolicy}
                onChange={(e) => setDividendPolicy(e.target.value as DividendPolicy)}
              >
                <option value="CASH">{t('scpi.cash')}</option>
                <option value="REINVEST">{t('scpi.reinvest')}</option>
              </select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="scpi-jouissance">{t('scpi.jouissanceDate')}</Label>
              <DateInput id="scpi-jouissance" value={jouissanceDate} onChange={setJouissanceDate} />
            </div>
          </div>
          {error && <p className="text-sm text-destructive">{formatApiError(error, t, 'common.error')}</p>}
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => close(false)} disabled={pending}>
            {t('common.cancel')}
          </Button>
          <Button type="button" onClick={submit} disabled={!canSubmit || pending}>
            {t('scpi.save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
