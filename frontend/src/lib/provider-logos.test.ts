import { describe, it, expect } from 'vitest'
import { LOGO_KEYS, PROVIDER_LOGOS, WALLET_LOGO_CHOICES, logoKeyUrl, providerLogoUrl } from './provider-logos'

/**
 * Vite expands this glob against the filesystem at transform time, so the keys are exactly
 * the image assets under `public/`. The loaders are never called — matching the paths is
 * the whole point, and it keeps the app's `node`-free tsconfig (`types: ["vite/client"]`)
 * as-is instead of pulling `node:fs` into a test that lives under `src/`.
 */
const bundledAssets = new Set(
  Object.keys(import.meta.glob('../../public/**/*.{svg,png}')).map((p) =>
    p.replace('../../public', ''),
  ),
)

describe('PROVIDER_LOGOS', () => {
  // A mapped-but-missing asset is invisible at runtime: Radix's Avatar just falls back to the
  // account's color circle, which is exactly what the card showed before the mapping existed.
  // This is the only thing that turns that silence into a failure.
  it.each(Object.entries(PROVIDER_LOGOS))('ships the asset mapped for %s', (provider, path) => {
    expect(path.startsWith('/'), `${provider}: path must be root-relative`).toBe(true)
    expect(bundledAssets, `missing frontend/public${path}`).toContain(path)
  })
})

describe('LOGO_KEYS', () => {
  it.each(Object.entries(LOGO_KEYS))('ships the asset mapped for %s', (key, path) => {
    expect(path.startsWith('/'), `${key}: path must be root-relative`).toBe(true)
    expect(bundledAssets, `missing frontend/public${path}`).toContain(path)
  })

  // The picker renders LOGO_KEYS[key] directly, so a choice pointing at a key that isn't in
  // the map would render a broken <img> rather than degrade to anything.
  it('offers wallets only keys the map resolves', () => {
    for (const { key } of WALLET_LOGO_CHOICES) {
      expect(LOGO_KEYS[key], `${key} is offered but unmapped`).toBeDefined()
    }
  })
})

describe('logoKeyUrl', () => {
  // 'blockchain' is WalletSyncService.DEFAULT_LOGO_KEY, written on every new wallet account
  // and backfilled by V75 — renaming it on either side drops every wallet's logo.
  it('resolves the key the wallet connector writes', () => {
    expect(logoKeyUrl('blockchain')).toBe('/wallets/blockchain.svg')
  })

  it('resolves the key the picker sets for a Ledger', () => {
    expect(logoKeyUrl('ledger')).toBe('/wallets/ledger.svg')
  })

  // The backend validates the key as a slug, not against this map, so an account can carry
  // one this build has never heard of (a downgrade, a hand-edited row). It falls back rather
  // than rendering a 404.
  it('returns null for an unknown or missing key', () => {
    expect(logoKeyUrl('trezor')).toBeNull()
    expect(logoKeyUrl('BLOCKCHAIN')).toBeNull()
    expect(logoKeyUrl(null)).toBeNull()
    expect(logoKeyUrl('')).toBeNull()
  })
})

describe('providerLogoUrl', () => {
  it('resolves the exchange name the backend writes as provider', () => {
    expect(providerLogoUrl('MERIA')).toBe('/exchanges/meria.svg')
  })

  // Copied verbatim from AmundiSyncService.PROVIDER. If either side is renamed — or the
  // accent drifts between Unicode normalisations — this fails instead of quietly showing
  // the color circle again.
  it('resolves the provider string the Amundi connector writes', () => {
    expect(providerLogoUrl('Amundi Épargne Salariale')).toBe('/providers/amundi.png')
  })

  // Copied verbatim from BoursoSyncService.PROVIDER. Every BoursoBank account —
  // current account, livret and PEA alike — carries it, so a rename on either
  // side would drop the logo from all of them at once.
  it('resolves the provider string the BoursoBank connector writes', () => {
    expect(providerLogoUrl('BoursoBank')).toBe('/providers/boursobank.png')
  })

  // Copied verbatim from the literal TradeRepublicSyncService.upsertAccount() writes.
  it('resolves the provider string the Trade Republic connector writes', () => {
    expect(providerLogoUrl('Trade Republic')).toBe('/providers/trade-republic.svg')
  })

  it('resolves the exchange name the backend writes as provider', () => {
    expect(providerLogoUrl('MERIA')).toBe('/exchanges/meria.svg')
  })

  it('matches case-insensitively', () => {
    expect(providerLogoUrl('Meria')).toBe('/exchanges/meria.svg')
    expect(providerLogoUrl('AMUNDI ÉPARGNE SALARIALE')).toBe('/providers/amundi.png')
    // The demo fixtures spell it 'BoursoBank'; the map keys on the upper-cased form.
    expect(providerLogoUrl('boursobank')).toBe('/providers/boursobank.png')
    expect(providerLogoUrl('TRADE REPUBLIC')).toBe('/providers/trade-republic.svg')
    expect(providerLogoUrl('Meria')).toBe('/exchanges/meria.svg')
  })

  it('returns null for a provider with no bundled logo', () => {
    expect(providerLogoUrl('Bourse Direct')).toBeNull()
    expect(providerLogoUrl('BNP Paribas')).toBeNull()
    expect(providerLogoUrl(null)).toBeNull()
    expect(providerLogoUrl('')).toBeNull()
  })
})
