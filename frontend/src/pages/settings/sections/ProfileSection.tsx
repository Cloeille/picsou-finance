import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { useMemberProfile, useSaveMemberProfile } from '@/features/profile/hooks'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { DateInput } from '@/components/shared/DateInput'
import { NumericInput } from '@/components/shared/NumericInput'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { extractErrorMessage } from '@/lib/errors'
import { parseAmount } from '@/lib/utils'
import type {
  HouseholdStatus,
  MemberProfile,
  MemberProfileRequest,
  RiskProfile,
} from '@/types/api'

/**
 * The French marginal brackets, offered as choices rather than a free field.
 *
 * The column is numeric and permissive so a value set through the API survives a round trip —
 * this list is the shortcut for the ninety-nine percent of members it fits, not the schema.
 */
const TMI_BRACKETS = [0, 11, 30, 41, 45] as const
const HOUSEHOLD_STATUSES: HouseholdStatus[] = ['SINGLE', 'COUPLE']
const RISK_PROFILES: RiskProfile[] = ['PRUDENT', 'BALANCED', 'DYNAMIC', 'AGGRESSIVE']

/** An empty field means "not stated", which the backend stores as null. */
function numberOrNull(raw: string): number | null {
  if (raw.trim() === '') return null
  const parsed = parseAmount(raw)
  return Number.isFinite(parsed) ? parsed : null
}

function text(value: number | null): string {
  return value == null ? '' : String(value)
}

/**
 * A row of mutually exclusive choices, with the selected one clearable by clicking it again —
 * the only way to withdraw an answer once given.
 */
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
      <Label>{label}</Label>
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

/**
 * The form body, mounted only once the profile has loaded and keyed on it.
 *
 * Every field seeds from props through a lazy `useState` initializer rather than an effect,
 * which is what the React Compiler's `set-state-in-effect` rule requires and what
 * `AllocationTargetsModal` already does.
 */
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
  const [marginalTaxRate, setMarginalTaxRate] = useState<number | null>(
    () => profile.marginalTaxRate,
  )
  const [householdStatus, setHouseholdStatus] = useState<HouseholdStatus | null>(
    () => profile.householdStatus,
  )
  const [riskProfile, setRiskProfile] = useState<RiskProfile | null>(() => profile.riskProfile)
  const [parts, setParts] = useState(() => text(profile.taxHouseholdParts))
  const [dependents, setDependents] = useState(() => text(profile.dependents))
  const [annualGrossIncome, setAnnualGrossIncome] = useState(() => text(profile.annualGrossIncome))
  const [netBeforeTax, setNetBeforeTax] = useState(() => text(profile.monthlyNetBeforeTax))
  const [withholding, setWithholding] = useState(() => text(profile.withholdingTaxRate))
  const [savingsCapacity, setSavingsCapacity] = useState(
    () => text(profile.monthlySavingsCapacity),
  )
  const [retirementAge, setRetirementAge] = useState(() => text(profile.targetRetirementAge))

  function submit() {
    onSubmit({
      birthDate: birthDate === '' ? null : birthDate,
      marginalTaxRate,
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
      <p className="text-sm text-muted-foreground">{t('settings.profile.hint')}</p>

      <div className="grid gap-4 sm:grid-cols-2">
        <div className="flex flex-col gap-2">
          <Label htmlFor="profile-birth-date">{t('settings.profile.birthDate')}</Label>
          <DateInput id="profile-birth-date" value={birthDate} onChange={setBirthDate} />
          {/* Derived server-side from the stored date; an age typed in would be wrong the
              morning after a birthday. */}
          {profile.age != null && (
            <p className="text-sm text-muted-foreground">
              {t('settings.profile.currentAge', { age: profile.age })}
            </p>
          )}
        </div>

        <div className="flex flex-col gap-2">
          <Label htmlFor="profile-retirement-age">{t('settings.profile.retirementAge')}</Label>
          <NumericInput
            id="profile-retirement-age"
            value={retirementAge}
            onChange={e => setRetirementAge(e.target.value)}
            placeholder="64"
          />
        </div>
      </div>

      <ChoiceRow
        label={t('settings.profile.marginalTaxRate')}
        options={TMI_BRACKETS}
        value={marginalTaxRate}
        onChange={setMarginalTaxRate}
        render={bracket => `${bracket} %`}
      />

      <ChoiceRow
        label={t('settings.profile.householdStatus')}
        options={HOUSEHOLD_STATUSES}
        value={householdStatus}
        onChange={setHouseholdStatus}
        render={status => t(`settings.profile.household.${status}`)}
      />

      <div className="grid gap-4 sm:grid-cols-2">
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
            value={dependents}
            onChange={e => setDependents(e.target.value)}
            placeholder="0"
          />
        </div>

        <div className="flex flex-col gap-2">
          <Label htmlFor="profile-income">{t('settings.profile.annualGrossIncome')}</Label>
          <NumericInput
            id="profile-income"
            value={annualGrossIncome}
            onChange={e => setAnnualGrossIncome(e.target.value)}
            placeholder="45000"
          />
          <p className="text-sm text-muted-foreground">{t('settings.profile.incomeHint')}</p>
        </div>

        <div className="flex flex-col gap-2">
          <Label htmlFor="profile-savings">{t('settings.profile.savingsCapacity')}</Label>
          <NumericInput
            id="profile-savings"
            value={savingsCapacity}
            onChange={e => setSavingsCapacity(e.target.value)}
            placeholder="500"
          />
        </div>
      </div>

      <div className="space-y-4">
        <div>
          <Label>{t('settings.profile.netPay')}</Label>
          {/* Why two fields rather than one: gross cannot reach net on its own -- social
              contributions come off first, at a rate that varies by status and that nobody
              knows offhand. Both figures below are printed on every French payslip. */}
          <p className="text-sm text-muted-foreground">{t('settings.profile.netPayHint')}</p>
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
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
        </div>

        {/* The figure the savings rate divides by, shown so the chain is visible rather than
            implied. It is the value the *server* derived, so it only updates after a save --
            recomputing it here would be a second implementation of the same formula. */}
        {profile.monthlyNetIncome != null && (
          <p className="text-sm text-muted-foreground">
            {t('settings.profile.netIncomeDerived')}{' '}
            <CurrencyDisplay value={profile.monthlyNetIncome} className="text-foreground" />
          </p>
        )}
      </div>

      <ChoiceRow
        label={t('settings.profile.riskProfile')}
        options={RISK_PROFILES}
        value={riskProfile}
        onChange={setRiskProfile}
        render={risk => t(`settings.profile.risk.${risk}`)}
      />

      <div className="flex justify-end">
        <Button onClick={submit} disabled={saving}>
          {t('common.save')}
        </Button>
      </div>
    </div>
  )
}

export function ProfileSection() {
  const { t } = useTranslation()
  const { data: profile, isLoading } = useMemberProfile()
  // Owned here rather than in the form: saving invalidates ['me','profile'], the refetch changes
  // the remount key, and TanStack Query drops the callbacks passed to a mutate() whose caller
  // has unmounted. Same reason as AllocationTargetsModal.
  const save = useSaveMemberProfile()

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
