import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { useMemberProfile, useSaveMemberProfile } from '@/features/profile/hooks'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { DateInput } from '@/components/shared/DateInput'
import { ErrorState } from '@/components/shared/ErrorState'
import { NumericInput } from '@/components/shared/NumericInput'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { extractErrorMessage } from '@/lib/errors'
import { getLocale, parseAmount } from '@/lib/utils'
import type {
  HouseholdStatus,
  MemberProfile,
  MemberProfileRequest,
  RiskProfile,
} from '@/types/api'

const HOUSEHOLD_STATUSES: HouseholdStatus[] = ['SINGLE', 'COUPLE']
const RISK_PROFILES: RiskProfile[] = ['PRUDENT', 'BALANCED', 'DYNAMIC', 'AGGRESSIVE']

function numberOrNull(raw: string): number | null {
  if (raw.trim() === '') return null
  const parsed = parseAmount(raw)
  return Number.isFinite(parsed) ? parsed : null
}

function text(value: number | null): string {
  return value == null
    ? ''
    : new Intl.NumberFormat(getLocale(), { useGrouping: false, maximumFractionDigits: 20 }).format(value)
}

function ChoiceRow<T extends string | number>({
  label,
  options,
  value,
  onChange,
  render,
}: {
  label: string
  options: readonly T[]
  value: T | null
  onChange: (next: T | null) => void
  render: (option: T) => string
}) {
  return (
    <div className="flex flex-col gap-2">
      <Label className="text-sm font-medium">{label}</Label>
      <div className="flex flex-wrap gap-2">
        {options.map(option => (
          <Button
            key={String(option)}
            type="button"
            size="sm"
            variant={value === option ? 'default' : 'outline'}
            aria-pressed={value === option}
            onClick={() => onChange(value === option ? null : option)}
          >
            {render(option)}
          </Button>
        ))}
      </div>
    </div>
  )
}

function ProfileGroup({
  title,
  children,
}: {
  title: string
  children: React.ReactNode
}) {
  return (
    <div className="space-y-4 border-t border-border/60 pt-5 first:border-t-0 first:pt-0">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
        {title}
      </h3>
      {children}
    </div>
  )
}

function ProfileForm({
  profile,
  saving,
  onSubmit,
}: {
  profile: MemberProfile
  saving: boolean
  onSubmit: (body: MemberProfileRequest) => void
}) {
  const { t } = useTranslation()

  const [birthDate, setBirthDate] = useState(() => profile.birthDate ?? '')
  const [marginalTaxRate, setMarginalTaxRate] = useState(() => text(profile.marginalTaxRate))
  const [householdStatus, setHouseholdStatus] = useState<HouseholdStatus | null>(
    () => profile.householdStatus,
  )
  const [parts, setParts] = useState(() => text(profile.taxHouseholdParts))
  const [dependents, setDependents] = useState(() => text(profile.dependents))
  const [annualGrossIncome, setAnnualGrossIncome] = useState(() => text(profile.annualGrossIncome))
  const [netBeforeTax, setNetBeforeTax] = useState(() => text(profile.monthlyNetBeforeTax))
  const [withholding, setWithholding] = useState(() => text(profile.withholdingTaxRate))
  const [savingsCapacity, setSavingsCapacity] = useState(
    () => text(profile.monthlySavingsCapacity),
  )
  const [retirementAge, setRetirementAge] = useState(() => text(profile.targetRetirementAge))
  const [riskProfile, setRiskProfile] = useState<RiskProfile | null>(() => profile.riskProfile)

  function submit() {
    onSubmit({
      birthDate: birthDate === '' ? null : birthDate,
      marginalTaxRate: numberOrNull(marginalTaxRate),
      householdStatus,
      taxHouseholdParts: numberOrNull(parts),
      dependents: numberOrNull(dependents),
      annualGrossIncome: numberOrNull(annualGrossIncome),
      monthlyNetBeforeTax: numberOrNull(netBeforeTax),
      withholdingTaxRate: numberOrNull(withholding),
      monthlySavingsCapacity: numberOrNull(savingsCapacity),
      targetRetirementAge: numberOrNull(retirementAge),
      riskProfile,
    })
  }

  return (
    <div className="space-y-6">
      <ProfileGroup title={t('settings.profile.groups.personal')}>
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="flex flex-col gap-2">
            <Label htmlFor="profile-birth-date">{t('settings.profile.birthDate')}</Label>
            <DateInput id="profile-birth-date" value={birthDate} onChange={setBirthDate} />
            {profile.age != null && (
              <p className="text-sm text-muted-foreground">
                {t('settings.profile.currentAge', { age: profile.age })}
              </p>
            )}
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="profile-parts">{t('settings.profile.taxHouseholdParts')}</Label>
            <NumericInput
              id="profile-parts"
              value={parts}
              onChange={e => setParts(e.target.value)}
              placeholder="1"
            />
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="profile-dependents">{t('settings.profile.dependents')}</Label>
            <NumericInput
              id="profile-dependents"
              inputMode="numeric"
              value={dependents}
              onChange={e => setDependents(e.target.value)}
              placeholder="0"
            />
          </div>
        </div>

        <ChoiceRow
          label={t('settings.profile.householdStatus')}
          options={HOUSEHOLD_STATUSES}
          value={householdStatus}
          onChange={setHouseholdStatus}
          render={status => t(`settings.profile.household.${status}`)}
        />
      </ProfileGroup>

      <ProfileGroup title={t('settings.profile.groups.taxAndIncome')}>
        <div className="grid items-end gap-4 sm:grid-cols-2">
          <div className="flex flex-col gap-2">
            <Label htmlFor="profile-marginal-tax-rate">
              {t('settings.profile.marginalTaxRate')}
            </Label>
            <NumericInput
              id="profile-marginal-tax-rate"
              value={marginalTaxRate}
              onChange={e => setMarginalTaxRate(e.target.value)}
              placeholder="30"
            />
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="profile-withholding">
              {t('settings.profile.withholdingTaxRate')}
            </Label>
            <NumericInput
              id="profile-withholding"
              value={withholding}
              onChange={e => setWithholding(e.target.value)}
              placeholder="7.3"
            />
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="profile-gross-income">{t('settings.profile.annualGrossIncome')}</Label>
            <NumericInput
              id="profile-gross-income"
              value={annualGrossIncome}
              onChange={e => setAnnualGrossIncome(e.target.value)}
              placeholder="45000"
            />
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="profile-net-before-tax">
              {t('settings.profile.monthlyNetBeforeTax')}
            </Label>
            <NumericInput
              id="profile-net-before-tax"
              value={netBeforeTax}
              onChange={e => setNetBeforeTax(e.target.value)}
              placeholder="2750"
            />
          </div>
        </div>

        {profile.monthlyNetIncome != null && (
          <div className="flex items-center justify-between rounded-lg border border-border/60 bg-muted/40 px-4 py-2.5 text-sm">
            <span className="text-muted-foreground">{t('settings.profile.netIncomeDerived')}</span>
            <CurrencyDisplay value={profile.monthlyNetIncome} className="font-semibold text-foreground" />
          </div>
        )}
      </ProfileGroup>

      <ProfileGroup title={t('settings.profile.groups.investing')}>
        <div className="grid items-end gap-4 sm:grid-cols-2">
          <div className="flex flex-col gap-2">
            <Label htmlFor="profile-savings">{t('settings.profile.savingsCapacity')}</Label>
            <NumericInput
              id="profile-savings"
              value={savingsCapacity}
              onChange={e => setSavingsCapacity(e.target.value)}
              placeholder="500"
            />
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="profile-retirement-age">{t('settings.profile.retirementAge')}</Label>
            <NumericInput
              id="profile-retirement-age"
              inputMode="numeric"
              value={retirementAge}
              onChange={e => setRetirementAge(e.target.value)}
              placeholder="62"
            />
          </div>
        </div>

        <ChoiceRow
          label={t('settings.profile.riskProfile')}
          options={RISK_PROFILES}
          value={riskProfile}
          onChange={setRiskProfile}
          render={risk => t(`settings.profile.risk.${risk}`)}
        />
      </ProfileGroup>

      <div className="flex justify-end border-t border-border/60 pt-4">
        <Button onClick={submit} disabled={saving}>
          {t('common.save')}
        </Button>
      </div>
    </div>
  )
}

export function ProfileSection() {
  const { t } = useTranslation()
  const { data: profile, isLoading, isError, refetch } = useMemberProfile()
  const save = useSaveMemberProfile()

  if (isError) {
    return <ErrorState title={t('common.error')} onRetry={() => void refetch()} />
  }

  if (isLoading || !profile) return <Skeleton className="h-64 w-full" />

  return (
    <ProfileForm
      key={JSON.stringify(profile)}
      profile={profile}
      saving={save.isPending}
      onSubmit={body =>
        save.mutate(body, {
          onSuccess: () => toast.success(t('settings.profile.saved')),
          onError: error => toast.error(extractErrorMessage(error)),
        })
      }
    />
  )
}
