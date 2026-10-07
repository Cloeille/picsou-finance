import type { MemberProfile } from '@/types/api'

/** What reaches the account, mirroring MemberProfileService. Null unless both are stated. */
export function netIncome(
  monthlyNetBeforeTax: number | null,
  withholdingTaxRate: number | null,
): number | null {
  if (monthlyNetBeforeTax == null || withholdingTaxRate == null) return null
  return Math.round(monthlyNetBeforeTax * (1 - withholdingTaxRate / 100) * 100) / 100
}

/** Years completed, mirroring what MemberProfileService derives from the stored date. */
export function ageFromBirthDate(birthDate: string): number {
  const born = new Date(`${birthDate}T00:00:00`)
  const now = new Date()
  let age = now.getFullYear() - born.getFullYear()
  const monthDelta = now.getMonth() - born.getMonth()
  if (monthDelta < 0 || (monthDelta === 0 && now.getDate() < born.getDate())) age -= 1
  return age
}

const BIRTH_DATE = '1990-06-14'
const ANNUAL_GROSS_INCOME = 48000
const MONTHLY_NET_BEFORE_TAX = 2950
const WITHHOLDING_TAX_RATE = 7.3

/**
 * A filled-in profile, so the demo shows the savings rate on the Goals page rather than the
 * "state your income" prompt — the prompt is reachable by clearing the field.
 */
export const mockMemberProfile: MemberProfile = {
  birthDate: BIRTH_DATE,
  age: ageFromBirthDate(BIRTH_DATE),
  marginalTaxRate: 30,
  householdStatus: 'COUPLE',
  taxHouseholdParts: 2.5,
  dependents: 1,
  annualGrossIncome: ANNUAL_GROSS_INCOME,
  monthlyNetBeforeTax: MONTHLY_NET_BEFORE_TAX,
  withholdingTaxRate: WITHHOLDING_TAX_RATE,
  monthlyNetIncome: netIncome(MONTHLY_NET_BEFORE_TAX, WITHHOLDING_TAX_RATE),
  monthlySavingsCapacity: 900,
  targetRetirementAge: 62,
  riskProfile: 'DYNAMIC',
}
