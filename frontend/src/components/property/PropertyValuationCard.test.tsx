import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { PropertyValuationCard } from './PropertyValuationCard'
import type { PropertyValuation, RealEstateMetadata } from '@/types/api'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'fr', resolvedLanguage: 'fr' },
  }),
}))

const mutate = vi.fn()
vi.mock('@/features/accounts/hooks', () => ({
  useRefreshValuation: () => ({
    mutate,
    isPending: false,
    isError: false,
    error: null,
  }),
}))

function metadata(overrides: Partial<RealEstateMetadata> = {}): RealEstateMetadata {
  return {
    purchasePrice: 300000, purchaseDate: null, agencyFees: null, notaryFees: null,
    worksCost: null, costBasis: 300000, propertyType: 'HOUSE', propertyKind: 'HOUSE',
    category: null,
    description: null, address: null, postalCode: null, city: null, country: 'FR',
    inseeCode: '33063', latitude: null, longitude: null, geocodeScore: null,
    geocodedAt: null, surfaceArea: 100, landArea: null, constructionYear: null,
    rooms: null, bedrooms: null, bathrooms: null, floorNumber: null, floorsTotal: null,
    hasElevator: null, garageCount: 0, parkingCount: 0, hasGarden: false,
    hasTerrace: false, hasBalcony: false, energyClass: null,
    valuationMode: 'ESTIMATED', lastValuedAt: null, rentalIncome: 0,
    ...overrides,
  }
}

function result(overrides: Partial<PropertyValuation> = {}): PropertyValuation {
  return {
    status: 'OK', mode: 'ESTIMATED', appliedToBalance: true,
    estimatedValue: 420000, lowValue: 380000, highValue: 470000,
    pricePerSqm: 4200, sampleSize: 1048, confidence: 'HIGH', sourceYear: 2025,
    provider: 'CEREMA_DV3F', scale: 'communes', valuedAt: '2026-08-01',
    reindexRatio: null, adjustments: [],
    ...overrides,
  }
}

/** Drives the refresh button and feeds a canned outcome back through the mutation callback. */
async function refreshWith(outcome: PropertyValuation) {
  mutate.mockImplementation((_id: number, opts: { onSuccess: (r: PropertyValuation) => void }) => {
    opts.onSuccess(outcome)
  })
  fireEvent.click(screen.getByRole('button', { name: /property.valuation.refresh/ }))
}

describe('PropertyValuationCard', () => {
  beforeEach(() => {
    mutate.mockReset()
  })

  it('shows the estimate and its confidence once refreshed', async () => {
    render(<PropertyValuationCard accountId={8} metadata={metadata()} currentValue={412000} />)

    await refreshWith(result())

    await waitFor(() => {
      expect(screen.getByText('property.valuation.confidence.HIGH')).toBeInTheDocument()
    })
    expect(screen.getByText('property.valuation.estimate')).toBeInTheDocument()
  })

  it('explains an uncovered area instead of showing a number', async () => {
    render(<PropertyValuationCard accountId={8} metadata={metadata()} currentValue={412000} />)

    await refreshWith(result({ status: 'UNSUPPORTED_AREA', estimatedValue: null }))

    // Alsace-Moselle and Mayotte have no DVF data at all. A plausible-looking figure there
    // would be worse than none, so the card must say why rather than render an estimate.
    await waitFor(() => {
      expect(screen.getByText('property.valuation.status.UNSUPPORTED_AREA')).toBeInTheDocument()
    })
    expect(screen.getByText('property.valuation.status.UNSUPPORTED_AREA_hint')).toBeInTheDocument()
    expect(screen.queryByText('property.valuation.estimate')).not.toBeInTheDocument()
  })

  it('reports a missing living area rather than failing silently', async () => {
    render(<PropertyValuationCard accountId={8} metadata={metadata({ surfaceArea: null })} currentValue={0} />)

    await refreshWith(result({ status: 'INCOMPLETE_DATA', estimatedValue: null }))

    await waitFor(() => {
      expect(screen.getByText('property.valuation.status.INCOMPLETE_DATA')).toBeInTheDocument()
    })
  })

  it('flags manual mode and says the estimate was not applied', async () => {
    render(
      <PropertyValuationCard
        accountId={8}
        metadata={metadata({ valuationMode: 'MANUAL' })}
        currentValue={412000}
      />,
    )

    expect(screen.getByText('property.valuation.manualMode')).toBeInTheDocument()

    await refreshWith(result({ mode: 'MANUAL', appliedToBalance: false }))

    // The estimate is still computed and shown, so the user can compare -- it just did not
    // touch the balance they locked.
    await waitFor(() => {
      expect(screen.getByText('property.valuation.manualNotApplied')).toBeInTheDocument()
    })
  })

  it('discloses the applied coefficients on demand', async () => {
    render(<PropertyValuationCard accountId={8} metadata={metadata()} currentValue={412000} />)

    await refreshWith(result({
      adjustments: [
        { code: 'GARDEN', factor: 0.02, sqm: null, amount: 8080 },
        { code: 'GARAGE', factor: null, sqm: 12, amount: 50400 },
      ],
    }))

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /property.valuation.method/ })).toBeInTheDocument()
    })
    fireEvent.click(screen.getByRole('button', { name: /property.valuation.method/ }))

    // These are declared heuristics, not a fitted model, so the disclaimer is part of the
    // contract rather than decoration.
    expect(screen.getByText('property.valuation.methodDisclaimer')).toBeInTheDocument()
    expect(screen.getByText('property.adjustments.GARDEN')).toBeInTheDocument()
    expect(screen.getByText('+2.0 %')).toBeInTheDocument()
    expect(screen.getByText('+12 m²')).toBeInTheDocument()
  })
})
