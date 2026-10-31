import '@testing-library/jest-dom'
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import type { MemberProfile } from '@/types/api'

const state = vi.hoisted(() => ({ profile: null as unknown, mutate: vi.fn() }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))
vi.mock('@/features/profile/hooks', () => ({
  useMemberProfile: () => ({ data: state.profile, isLoading: false }),
  useSaveMemberProfile: () => ({ mutate: state.mutate, isPending: false }),
}))
vi.mock('@/components/shared/CurrencyDisplay', () => ({
  CurrencyDisplay: ({ value }: { value: number }) => <span>{value}</span>,
}))

// jsdom lacks matchMedia, which DateInput probes for the touch/native date picker.
beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false, media: query, onchange: null,
      addEventListener: () => {}, removeEventListener: () => {},
      addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false,
    }),
  })
})

const { ProfileSection } = await import('./ProfileSection')

const filled: MemberProfile = {
  birthDate: '1990-05-14',
  age: 36,
  marginalTaxRate: 30,
  householdStatus: 'COUPLE',
  taxHouseholdParts: 2.5,
  dependents: 0,
  annualGrossIncome: 48000,
  monthlyNetBeforeTax: 2750.5,
  withholdingTaxRate: 7.3,
  monthlyNetIncome: 2549.71,
  monthlySavingsCapacity: 500,
  targetRetirementAge: 64,
  riskProfile: 'BALANCED',
} as MemberProfile

const empty = Object.fromEntries(Object.keys(filled).map((key) => [key, null])) as unknown as MemberProfile

afterEach(() => {
  document.documentElement.lang = ''
  state.mutate.mockReset()
})

function saveIn(lang: string, profile: MemberProfile) {
  document.documentElement.lang = lang
  state.profile = profile
  render(<ProfileSection />)
  fireEvent.click(screen.getByRole('button', { name: 'common.save' }))
  expect(state.mutate).toHaveBeenCalledTimes(1)
  return state.mutate.mock.calls[0][0]
}

// The fields seed in the member's notation ("2,5" in French) and must parse back to the same
// number: a seed of "2,5" read as 25, or "2.750,5" read as NaN, would silently rewrite the profile.
describe.each([
  ['fr', '2,5', '2750,5', '7,3'],
  ['de', '2,5', '2750,5', '7,3'],
  ['es', '2,5', '2750,5', '7,3'],
  ['en', '2.5', '2750.5', '7.3'],
])('ProfileSection in %s', (lang, parts, net, withholding) => {
  it('seeds the amounts in the locale notation, ungrouped', () => {
    document.documentElement.lang = lang
    state.profile = filled
    render(<ProfileSection />)

    expect(screen.getByLabelText('settings.profile.taxHouseholdParts')).toHaveValue(parts)
    expect(screen.getByLabelText('settings.profile.monthlyNetBeforeTax')).toHaveValue(net)
    expect(screen.getByLabelText('settings.profile.withholdingTaxRate')).toHaveValue(withholding)
    expect(screen.getByLabelText('settings.profile.annualGrossIncome')).toHaveValue('48000')
  })

  it('saves the numbers it was given, unchanged', () => {
    expect(saveIn(lang, filled)).toEqual({
      birthDate: '1990-05-14',
      marginalTaxRate: 30,
      householdStatus: 'COUPLE',
      taxHouseholdParts: 2.5,
      dependents: 0,
      annualGrossIncome: 48000,
      monthlyNetBeforeTax: 2750.5,
      withholdingTaxRate: 7.3,
      monthlySavingsCapacity: 500,
      targetRetirementAge: 64,
      riskProfile: 'BALANCED',
    })
  })

  it('saves unstated fields as null, not zero', () => {
    expect(saveIn(lang, empty)).toEqual({
      birthDate: null,
      marginalTaxRate: null,
      householdStatus: null,
      taxHouseholdParts: null,
      dependents: null,
      annualGrossIncome: null,
      monthlyNetBeforeTax: null,
      withholdingTaxRate: null,
      monthlySavingsCapacity: null,
      targetRetirementAge: null,
      riskProfile: null,
    })
  })
})
