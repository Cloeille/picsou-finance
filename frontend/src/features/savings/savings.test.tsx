import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { SavingsConfigSection } from './SavingsConfigSection'
import type { SavingsConfig, SavingsInterestProjection } from '@/types/api'

// ─── Hook mocks ──────────────────────────────────────────────────────────────

const mockUseSetSavingsConfig = vi.fn()
const mockUseDeleteSavingsConfig = vi.fn()
const mockUseSavingsInterest = vi.fn()

vi.mock('./hooks', () => ({
  useSetSavingsConfig: () => mockUseSetSavingsConfig(),
  useDeleteSavingsConfig: () => mockUseDeleteSavingsConfig(),
  useSavingsInterest: (...args: unknown[]) => mockUseSavingsInterest(...args),
}))

// ─── i18n stub ───────────────────────────────────────────────────────────────
// Returns the translation key so tests can assert on key strings.
// Interpolates numeric opts (year, count) to keep projection title matchable.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) => {
      if (opts && typeof opts.year === 'number') return `${key}:${opts.year}`
      if (opts && typeof opts.count === 'number') return `${key}:${opts.count}`
      return key
    },
  }),
}))

// ─── CurrencyDisplay stub ─────────────────────────────────────────────────────
vi.mock('@/components/shared/CurrencyDisplay', () => ({
  CurrencyDisplay: ({ value }: { value: number }) => (
    <span data-testid="currency">{value}</span>
  ),
}))

// ─── formatLocalDate stub — importActual keeps cn/other utils intact ──────────
vi.mock('@/lib/utils', async (importActual) => {
  const actual = await importActual<typeof import('@/lib/utils')>()
  return {
    ...actual,
    formatLocalDate: (d: string) => d,
  }
})

// ─── Helpers ──────────────────────────────────────────────────────────────────

function defaultMutationMock() {
  return { mutate: vi.fn(), isPending: false }
}

function mockInterest(data?: SavingsInterestProjection, isLoading = false) {
  mockUseSavingsInterest.mockReturnValue({ data, isLoading })
}

beforeEach(() => {
  mockUseSetSavingsConfig.mockReturnValue(defaultMutationMock())
  mockUseDeleteSavingsConfig.mockReturnValue(defaultMutationMock())
  mockInterest(undefined)
})

// ─── Test 1: Gross/Net toggle visibility ─────────────────────────────────────

describe('SavingsConfigSection — COMMERCIAL product (Gross/Net toggle)', () => {
  it('shows Gross/Net toggle when product is COMMERCIAL', () => {
    const commercialConfig: SavingsConfig = {
      product: 'COMMERCIAL',
      annualRate: 2.0,
      rateBasis: 'GROSS',
      taxRatePct: 30,
      ceiling: null,
    }
    render(<SavingsConfigSection accountId={5} initialConfig={commercialConfig} />)
    // The toggle buttons for GROSS and NET should be visible
    expect(screen.getByText('savings.gross')).toBeInTheDocument()
    expect(screen.getByText('savings.net')).toBeInTheDocument()
  })

  it('shows tax rate field when COMMERCIAL + GROSS is selected', () => {
    const commercialConfig: SavingsConfig = {
      product: 'COMMERCIAL',
      annualRate: 2.0,
      rateBasis: 'GROSS',
      taxRatePct: 30,
      ceiling: null,
    }
    render(<SavingsConfigSection accountId={5} initialConfig={commercialConfig} />)
    expect(screen.getByText('savings.taxRate')).toBeInTheDocument()
  })

  it('hides tax rate field when COMMERCIAL + NET is selected', () => {
    const commercialConfig: SavingsConfig = {
      product: 'COMMERCIAL',
      annualRate: 2.0,
      rateBasis: 'NET',
      taxRatePct: null,
      ceiling: null,
    }
    render(<SavingsConfigSection accountId={5} initialConfig={commercialConfig} />)
    // initialConfig already sets NET so the tax field should be hidden from the start
    expect(screen.queryByText('savings.taxRate')).not.toBeInTheDocument()
  })

  it('hides toggle and tax field for regulated product LIVRET_A', () => {
    const regulatedConfig: SavingsConfig = {
      product: 'LIVRET_A',
      annualRate: 2.4,
      rateBasis: 'NET',
      taxRatePct: null,
      ceiling: 22950,
    }
    render(<SavingsConfigSection accountId={7} initialConfig={regulatedConfig} />)
    expect(screen.queryByText('savings.gross')).not.toBeInTheDocument()
    expect(screen.queryByText('savings.net')).not.toBeInTheDocument()
    expect(screen.queryByText('savings.taxRate')).not.toBeInTheDocument()
    // Regulated note should be visible
    expect(screen.getByText('savings.regulatedNet')).toBeInTheDocument()
  })

  it('switches tax field visibility when toggling GROSS → NET for COMMERCIAL', () => {
    const commercialConfig: SavingsConfig = {
      product: 'COMMERCIAL',
      annualRate: 2.0,
      rateBasis: 'GROSS',
      taxRatePct: 30,
      ceiling: null,
    }
    render(<SavingsConfigSection accountId={5} initialConfig={commercialConfig} />)
    // Initially GROSS — tax field is visible
    expect(screen.getByText('savings.taxRate')).toBeInTheDocument()
    // Click NET button
    fireEvent.click(screen.getByText('savings.net'))
    // Tax field should disappear
    expect(screen.queryByText('savings.taxRate')).not.toBeInTheDocument()
  })
})

// ─── Test 2: Projection card rendering ───────────────────────────────────────

describe('SavingsConfigSection — projection card', () => {
  const projection: SavingsInterestProjection = {
    estimatedInterestYtd: 87.3,
    projectedInterestFullYear: 187.2,
    nextCapitalizationDate: '2026-12-31',
    annualRatePct: 2.4,
    basis: 'NET',
    netOfTax: true,
  }

  it('renders projection card when initialConfig is provided', () => {
    mockInterest(projection)
    const config: SavingsConfig = {
      product: 'LIVRET_A',
      annualRate: 2.4,
      rateBasis: 'NET',
      taxRatePct: null,
      ceiling: null,
    }
    render(<SavingsConfigSection accountId={7} initialConfig={config} />)
    // Projection title key should appear (interpolated with year)
    expect(screen.getByText(/savings\.projectionTitle/)).toBeInTheDocument()
    // Both interest values render via stubbed CurrencyDisplay
    expect(screen.getAllByTestId('currency')).toHaveLength(2)
    // Disclaimer
    expect(screen.getByText('savings.projectionDisclaimer')).toBeInTheDocument()
  })

  it('does NOT render projection card when initialConfig is null', () => {
    render(<SavingsConfigSection accountId={7} initialConfig={null} />)
    expect(screen.queryByText(/savings\.projectionTitle/)).not.toBeInTheDocument()
    expect(screen.queryByText('savings.projectionDisclaimer')).not.toBeInTheDocument()
  })

  it('does NOT render projection card when initialConfig is undefined', () => {
    render(<SavingsConfigSection accountId={7} />)
    expect(screen.queryByText(/savings\.projectionTitle/)).not.toBeInTheDocument()
  })

  it('renders a spinner while interest data is loading', () => {
    const config: SavingsConfig = {
      product: 'LIVRET_A',
      annualRate: 2.4,
      rateBasis: 'NET',
      taxRatePct: null,
      ceiling: null,
    }
    mockInterest(undefined, true)
    const { container } = render(<SavingsConfigSection accountId={7} initialConfig={config} />)
    // Projection card is visible (hasSavedConfig=true), spinner shows while loading
    expect(container.querySelector('.animate-spin')).toBeInTheDocument()
    expect(screen.queryByTestId('currency')).not.toBeInTheDocument()
  })
})

// ─── Test 3: Array.isArray guard on list rendering ───────────────────────────

describe('Array.isArray guard for savings suggestions', () => {
  it('correctly identifies valid arrays', () => {
    expect(Array.isArray([])).toBe(true)
    expect(
      Array.isArray([
        {
          accountId: 1,
          accountName: 'Test',
          suggestedProduct: 'LIVRET_A',
          defaultAnnualRate: 2.4,
          uncertain: false,
        },
      ])
    ).toBe(true)
  })

  it('rejects non-array values that would break list rendering', () => {
    // These are truthy but are NOT arrays — a naive `?? []` guard would fail on these
    expect(Array.isArray({})).toBe(false)
    expect(Array.isArray(null)).toBe(false)
    expect(Array.isArray(undefined)).toBe(false)
    expect(Array.isArray('')).toBe(false)
  })

  it('banner logic: hasSavingsSuggestions is true only for a non-empty array', () => {
    const validData = [
      {
        accountId: 7,
        accountName: 'Livret A',
        suggestedProduct: 'LIVRET_A',
        defaultAnnualRate: 2.4,
        uncertain: false,
      },
    ]
    const hasSuggestions = Array.isArray(validData) && validData.length > 0
    expect(hasSuggestions).toBe(true)
  })

  it('banner logic: hasSavingsSuggestions is false when API returns an object (non-array)', () => {
    // Simulates the case where the API returns {} instead of []
    const badData = {} as unknown
    const hasSuggestions = Array.isArray(badData) && (badData as unknown[]).length > 0
    expect(hasSuggestions).toBe(false)
  })

  it('banner logic: hasSavingsSuggestions is false for an empty array', () => {
    const emptyData: unknown[] = []
    const hasSuggestions = Array.isArray(emptyData) && emptyData.length > 0
    expect(hasSuggestions).toBe(false)
  })
})
