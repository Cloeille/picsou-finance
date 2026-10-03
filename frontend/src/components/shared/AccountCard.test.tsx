import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, waitFor } from '@testing-library/react'
import { AccountCard } from './AccountCard'
import { StubImage } from '@/test/stubImage'
import type { Account, RealEstateMetadata } from '@/types/api'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'en', resolvedLanguage: 'en' },
  }),
}))

beforeEach(() => {
  vi.stubGlobal('Image', StubImage)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

const baseAccount: Account = {
  id: 1,
  name: 'Compte Courant BNP',
  type: 'CHECKING',
  provider: 'BNP Paribas',
  currency: 'EUR',
  currentBalance: 1000,
  currentBalanceEur: 1000,
  lastSyncedAt: null,
  isManual: false,
  color: '#6366f1',
  ticker: null,
  logoUrl: null,
  logoKey: null,
  createdAt: '2024-01-01T00:00:00Z',
  hidden: false,
}

/** A described, valued property -- the shape every real-estate assertion below varies from. */
function realEstateAccount(
  overrides: Partial<Account> = {},
  metadata: Partial<RealEstateMetadata> = {},
): Account {
  return {
    ...baseAccount,
    id: 8,
    name: 'Résidence principale',
    type: 'REAL_ESTATE',
    provider: null,
    isManual: true,
    color: '#a855f7',
    currentBalance: 412000,
    currentBalanceEur: 412000,
    realEstate: {
      purchasePrice: 320000, purchaseDate: null, agencyFees: null, notaryFees: null,
      worksCost: null, costBasis: 368800, propertyType: 'HOUSE', propertyKind: 'HOUSE',
      category: 'PRIMARY_RESIDENCE', description: null, address: null, postalCode: null,
      city: 'Bordeaux', country: 'FR', inseeCode: '33063', latitude: null, longitude: null,
      geocodeScore: null, geocodedAt: null, surfaceArea: 95, landArea: null,
      constructionYear: null, rooms: null, bedrooms: null, bathrooms: null,
      floorNumber: null, floorsTotal: null, hasElevator: null, garageCount: 0,
      parkingCount: 0, hasGarden: false, hasTerrace: false, hasBalcony: false,
      energyClass: null, valuationMode: 'ESTIMATED', lastValuedAt: '2026-01-10',
      rentalIncome: 0,
      ...metadata,
    },
    ...overrides,
  }
}

describe('AccountCard', () => {
  it('renders a colored circle when the account has no logo', () => {
    const { container } = render(<AccountCard account={baseAccount} />)
    expect(container.querySelector('img')).not.toBeInTheDocument()
    const dot = container.querySelector('[style*="background-color"]')
    expect(dot).toHaveStyle({ backgroundColor: '#6366f1' })
  })

  it('renders the bank logo image when logoUrl loads successfully', async () => {
    const account = { ...baseAccount, logoUrl: 'https://logos.example/bnp.png' }
    const { container } = render(<AccountCard account={account} />)

    await waitFor(() => {
      const img = container.querySelector('img') as HTMLImageElement
      expect(img).toHaveAttribute('src', 'https://logos.example/bnp.png')
    })
  })

  it.each([
    ['MERIA', '/exchanges/meria.svg'],
    ['Amundi Épargne Salariale', '/providers/amundi.png'],
  ])('renders the bundled logo for %s, which the connector gives no logoUrl for', async (provider, asset) => {
    const account = { ...baseAccount, provider, logoUrl: null }
    const { container } = render(<AccountCard account={account} />)

    await waitFor(() => {
      const img = container.querySelector('img') as HTMLImageElement
      expect(img).toHaveAttribute('src', asset)
    })
  })

  it('prefers the connector-supplied logoUrl over a bundled one', async () => {
    const account = { ...baseAccount, provider: 'MERIA', logoUrl: 'https://logos.example/bnp.png' }
    const { container } = render(<AccountCard account={account} />)

    await waitFor(() => {
      const img = container.querySelector('img') as HTMLImageElement
      expect(img).toHaveAttribute('src', 'https://logos.example/bnp.png')
    })
  })

  // A wallet's provider is its native ticker (BTC, SOL...), so nothing in PROVIDER_LOGOS can
  // match it -- the key stored on the account is the only thing that gives it a logo.
  it.each([
    ['blockchain', '/wallets/blockchain.svg'],
    ['ledger', '/wallets/ledger.svg'],
  ])('renders the %s asset for a wallet carrying that logoKey', async (logoKey, asset) => {
    const account = { ...baseAccount, type: 'CRYPTO' as const, provider: 'BTC', logoKey }
    const { container } = render(<AccountCard account={account} />)

    await waitFor(() => {
      const img = container.querySelector('img') as HTMLImageElement
      expect(img).toHaveAttribute('src', asset)
    })
  })

  it("prefers the account's own logoKey over both a connector logo and a bundled provider one", async () => {
    const account = {
      ...baseAccount,
      provider: 'MERIA',
      logoUrl: 'https://logos.example/bnp.png',
      logoKey: 'ledger',
    }
    const { container } = render(<AccountCard account={account} />)

    await waitFor(() => {
      const img = container.querySelector('img') as HTMLImageElement
      expect(img).toHaveAttribute('src', '/wallets/ledger.svg')
    })
  })

  it('ignores a logoKey this build has no asset for, falling through to the provider logo', async () => {
    const account = { ...baseAccount, provider: 'MERIA', logoKey: 'trezor' }
    const { container } = render(<AccountCard account={account} />)

    await waitFor(() => {
      const img = container.querySelector('img') as HTMLImageElement
      expect(img).toHaveAttribute('src', '/exchanges/meria.svg')
    })
  })

  it('falls back to the colored circle if the logo image fails to load', async () => {
    const account = { ...baseAccount, logoUrl: 'https://logos.example/broken.png' }
    const { container } = render(<AccountCard account={account} />)

    await waitFor(() => {
      expect(container.querySelector('img')).not.toBeInTheDocument()
      const dot = container.querySelector('[style*="background-color"]')
      expect(dot).toHaveStyle({ backgroundColor: '#6366f1' })
    })
  })

  describe('real estate', () => {
    it('marks the property with its kind glyph instead of a color circle', () => {
      const { container } = render(<AccountCard account={realEstateAccount()} />)

      expect(container.querySelector('.lucide-house')).toBeInTheDocument()
      expect(container.querySelector('[style*="background-color"]')).not.toBeInTheDocument()
    })

    it.each([
      ['APARTMENT', 'lucide-building-2'],
      ['BUILDING', 'lucide-building'],
      ['LAND', 'lucide-land-plot'],
      ['PARKING', 'lucide-square-parking'],
      ['COMMERCIAL', 'lucide-store'],
    ] as const)('gives %s its own glyph', (propertyKind, iconClass) => {
      const { container } = render(<AccountCard account={realEstateAccount({}, { propertyKind })} />)
      expect(container.querySelector(`.${iconClass}`)).toBeInTheDocument()
    })

    it('falls back to the color circle when the kind is unknown', () => {
      // property_type is free text predating PropertyKind -- an old row the backend parser
      // does not recognise leaves propertyKind null, and there is no glyph to pick.
      const account = realEstateAccount({}, { propertyType: 'chalet', propertyKind: null })
      const { container } = render(<AccountCard account={account} />)

      expect(container.querySelector('svg')).not.toBeInTheDocument()
      expect(container.querySelector('[style*="background-color"]')).toHaveStyle({
        backgroundColor: '#a855f7',
      })
    })

    it('stands the kind and city in for the provider line a manual account has not got', () => {
      const { getByText } = render(<AccountCard account={realEstateAccount()} />)
      expect(getByText('property.kind.HOUSE · Bordeaux')).toBeInTheDocument()
    })

    it('drops whichever half of the subtitle is missing', () => {
      const { getByText } = render(
        <AccountCard account={realEstateAccount({}, { city: null })} />,
      )
      expect(getByText('property.kind.HOUSE')).toBeInTheDocument()
    })

    it('shows the valuation date where a synced account shows its sync date', () => {
      const { container } = render(<AccountCard account={realEstateAccount()} />)
      expect(container.textContent).toContain('accounts.lastValuation')
      expect(container.textContent).not.toContain('accounts.lastSync')
    })

    it('leaves the unrealized gain to the detail page, so the card matches every other one', () => {
      const { container } = render(<AccountCard account={realEstateAccount()} />)

      // 412 000 - 368 800 = 43 200, whatever the locale's group separator.
      expect(container.textContent).not.toMatch(/43\D?200/)
      expect(container.querySelector('.text-emerald-500')).not.toBeInTheDocument()
    })
  })

  describe('stale sync badge', () => {
    it('flags a non-manual account not synced for more than 48h', async () => {
      const threeDaysAgo = new Date(Date.now() - 3 * 24 * 60 * 60 * 1000).toISOString()
      const account = { ...baseAccount, lastSyncedAt: threeDaysAgo }
      const { findByText } = render(<AccountCard account={account} />)

      expect(await findByText('accounts.syncStale')).toBeInTheDocument()
    })

    it('shows the normal last-sync line for a recently synced account', async () => {
      const oneHourAgo = new Date(Date.now() - 60 * 60 * 1000).toISOString()
      const account = { ...baseAccount, lastSyncedAt: oneHourAgo }
      const { queryByText, findByText } = render(<AccountCard account={account} />)

      expect(await findByText(/accounts\.lastSync/)).toBeInTheDocument()
      expect(queryByText('accounts.syncStale')).not.toBeInTheDocument()
    })

    it('never flags a manual account', () => {
      const threeDaysAgo = new Date(Date.now() - 3 * 24 * 60 * 60 * 1000).toISOString()
      const account = { ...baseAccount, isManual: true, lastSyncedAt: threeDaysAgo }
      const { queryByText } = render(<AccountCard account={account} />)

      expect(queryByText('accounts.syncStale')).not.toBeInTheDocument()
    })
  })

  /**
   * The badge used to be binary -- amber past 48h, muted grey otherwise -- which said nothing
   * about whether a figure was an hour or a month old. The two scales are deliberately
   * different: bank syncs run daily, property valuations monthly.
   */
  describe('freshness colours', () => {
    const HOUR = 60 * 60 * 1000
    const DAY = 24 * HOUR
    const isoAgo = (ms: number) => new Date(Date.now() - ms).toISOString()
    const dayAgo = (days: number) => new Date(Date.now() - days * DAY).toISOString().slice(0, 10)

    it.each([
      ['green under a day', 2 * HOUR, 'text-emerald-600'],
      ['yellow in the second day', 30 * HOUR, 'text-yellow-600'],
      ['orange past two days', 3 * DAY, 'text-orange-600'],
      ['red past a week', 10 * DAY, 'text-red-600'],
    ])('grades a sync date %s', async (_label, age, expectedClass) => {
      const account = { ...baseAccount, lastSyncedAt: isoAgo(age) }
      const { findByText } = render(<AccountCard account={account} />)

      const line = await findByText(/accounts\.(lastSync|syncStale)/)
      expect(line.className).toContain(expectedClass)
    })

    it.each([
      ['green in the first month', 10, 'text-emerald-600'],
      ['yellow past 35 days', 45, 'text-yellow-600'],
      ['red past 90 days', 100, 'text-red-600'],
    ])('grades a valuation date %s on the monthly scale', async (_label, days, expectedClass) => {
      const account = realEstateAccount({}, { lastValuedAt: dayAgo(days) })
      const { findByText } = render(<AccountCard account={account} />)

      const line = await findByText(/accounts\.lastValuation/)
      expect(line.className).toContain(expectedClass)
    })

    /** 45 days would be red on the sync scale; on the monthly one it is merely ageing. */
    it('does not warn about a valuation a monthly job has simply not revisited', async () => {
      const account = realEstateAccount({}, { lastValuedAt: dayAgo(45) })
      const { queryByText, findByText } = render(<AccountCard account={account} />)

      expect(await findByText(/accounts\.lastValuation/)).toBeInTheDocument()
      expect(queryByText('accounts.syncStale')).not.toBeInTheDocument()
    })

    /** Colour is informative for a manual balance; "reconnect the provider" is not. */
    it('colours a stale manual account without the warning', async () => {
      const account = { ...baseAccount, isManual: true, lastSyncedAt: isoAgo(10 * DAY) }
      const { queryByText, findByText } = render(<AccountCard account={account} />)

      const line = await findByText(/accounts\.lastSync/)
      expect(line.className).toContain('text-red-600')
      expect(queryByText('accounts.syncStale')).not.toBeInTheDocument()
    })
  })
})
