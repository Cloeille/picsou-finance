/**
 * Brand logos that ship with the app.
 *
 * Enable Banking accounts get a real `logoUrl` from its institution catalog and never reach
 * this module (see `docs/features/bank-logos.md`). Every other connector — crypto exchanges,
 * Amundi, Trade Republic, Bourse Direct... — is integrated by hand against a single provider,
 * so there is no catalog to ask for a logo and the asset ships with the frontend instead.
 *
 * Assets live in `public/` rather than being imported from `src/assets/` like the app's own
 * logo: a missing file then degrades to the account's color circle — exactly what every
 * logo-less account already shows — instead of failing the build. `provider-logos.test.ts`
 * is what keeps an entry from silently pointing at nothing. SVG is preferred where the
 * brand publishes one; a raster asset is fine as long as it is square and roughly 256px+,
 * since the avatar renders it at 40px with `object-contain`.
 *
 * Leave a margin inside the asset. The avatar is a circle, which inscribes the square the
 * image is fitted to, so a mark drawn edge to edge loses its corners — and a wide one loses
 * its ends outright. Brand SVGs are usually cropped tight to the artwork, so the ones bundled
 * here carry a padded `viewBox` (compare `trade-republic.svg`'s to the drawing's own extent).
 */

/**
 * Logos derived from `Account.provider`, matched case-insensitively.
 *
 * Keys are the exact `provider` string the backend writes. For crypto exchanges that string is
 * `ExchangeType.name()` (`CryptoExchangeSyncService.resolveAccount`), hence `MERIA` rather than
 * `Meria`; for Amundi it is `AmundiSyncService.PROVIDER`, accent and all, for BoursoBank
 * `BoursoSyncService.PROVIDER`, for La Banque Postale `LbpSyncService.PROVIDER`, for Natixis
 * `NatixisSyncService.PROVIDER`; Trade Republic writes
 * its name inline in `TradeRepublicSyncService.upsertAccount`; both CORUM connectors (SCPI and
 * CORUM Life) write `ScpiProvider.CORUM.displayName()`. `provider-logos.test.ts` pins
 * every literal so a rename on either side fails loudly instead of silently dropping the logo.
 */
export const PROVIDER_LOGOS: Record<string, string> = {
  MERIA: '/exchanges/meria.svg',
  'AMUNDI ÉPARGNE SALARIALE': '/providers/amundi.png',
  BOURSOBANK: '/providers/boursobank.png',
  'LA BANQUE POSTALE': '/providers/la-banque-postale.svg',
  'TRADE REPUBLIC': '/providers/trade-republic.svg',
  'NATIXIS INTERÉPARGNE': '/providers/natixis-interepargne.svg',
  CORUM: '/providers/corum.svg',
}

/**
 * Logos for holdings no logo source knows, matched on the ticker's prefix.
 *
 * A CORUM Life fund line is a provider-only fund: it has no ISIN a market feed quotes, so the
 * backend stores no `logoUrl` for it and the holding would otherwise show its name's initials.
 * `CorumLifeWriter` mints its ticker as `CORUM-LIFE-<code>`, so the prefix identifies the
 * manager's own funds and the manager's mark stands in. A backend-resolved `logoUrl` always
 * wins over this map (see `HoldingLogo`).
 */
export const HOLDING_PREFIX_LOGOS: Record<string, string> = {
  'CORUM-LIFE-': '/providers/corum.svg',
  'NATIXIS-': '/providers/natixis-interepargne.svg',
}

/**
 * Bundled marks for common crypto assets, currencies and major European ETF tickers when the
 * backend does not return a `logoUrl` for them. Keys are the base ticker (before any exchange
 * suffix like `.PA` or `.AS`).
 */
export const HOLDING_TICKER_LOGOS: Record<string, string> = {
  BTC: '/holdings/btc.svg',
  'BTC-EUR': '/holdings/btc.svg',
  'BTC-USD': '/holdings/btc.svg',
  ETH: '/holdings/eth.svg',
  'ETH-EUR': '/holdings/eth.svg',
  'ETH-USD': '/holdings/eth.svg',
  SOL: '/holdings/sol.svg',
  'SOL-EUR': '/holdings/sol.svg',
  USDC: '/holdings/usdc.svg',
  USDT: '/holdings/usdt.svg',
  EUR: '/holdings/eur.svg',
  USD: '/holdings/usd.svg',
  GBP: '/holdings/gbp.svg',
  CHF: '/holdings/chf.svg',
  // iShares / BlackRock popular European ETFs
  WPEA: '/holdings/ishares.svg',
  IWDA: '/holdings/ishares.svg',
  SWDA: '/holdings/ishares.svg',
  CSPX: '/holdings/ishares.svg',
  SXR8: '/holdings/ishares.svg',
  EUNL: '/holdings/ishares.svg',
  EMIM: '/holdings/ishares.svg',
  IGLN: '/holdings/ishares.svg',
  // BNP Paribas Easy popular PEA ETFs
  ESE: '/holdings/bnp.svg',
  ETZ: '/holdings/bnp.svg',
  ECAR: '/holdings/bnp.svg',
  EBBB: '/holdings/bnp.svg',
  // Amundi popular PEA ETFs
  CW8: '/holdings/amundi.svg',
  EWLD: '/holdings/amundi.svg',
  DCAM: '/holdings/amundi.svg',
  PAEEM: '/holdings/amundi.svg',
  PANX: '/holdings/amundi.svg',
  PUST: '/holdings/amundi.svg',
  PE500: '/holdings/amundi.svg',
  PCEU: '/holdings/amundi.svg',
  // Vanguard popular European ETFs
  VWCE: '/holdings/vanguard.svg',
  VUSA: '/holdings/vanguard.svg',
  VWRP: '/holdings/vanguard.svg',
  VHYL: '/holdings/vanguard.svg',
}

const HOLDING_ISSUER_PATTERNS: Array<[RegExp, string]> = [
  [/\bishares\b|\bblackrock\b/i, '/holdings/ishares.svg'],
  [/\bbnp\b/i, '/holdings/bnp.svg'],
  [/\bamundi\b/i, '/holdings/amundi.svg'],
  [/\bvanguard\b/i, '/holdings/vanguard.svg'],
  [/\bbitcoin\b/i, '/holdings/btc.svg'],
  [/\bethereum\b/i, '/holdings/eth.svg'],
  [/\bsolana\b/i, '/holdings/sol.svg'],
]

/**
 * Logos an account points at explicitly, via `Account.logoKey`.
 *
 * The provider map above can't serve on-chain wallets: `WalletSyncService` writes the native
 * ticker into `provider` (BTC, SOL, ETH...), so there is no stable string to key on — and two
 * wallets with the same ticker still deserve different marks, since one may be a bare address
 * and the other a Ledger. That is a user's call, not a connector's, so the key is stored on the
 * account (`WalletSyncService.DEFAULT_LOGO_KEY` seeds `blockchain`, the form lets the user
 * change it) and resolved here. The backend treats the key as an opaque slug, so this map is
 * the only definition of which keys mean anything — an unknown one resolves to `null` and the
 * account falls back to its provider logo, then its color.
 */
export const LOGO_KEYS: Record<string, string> = {
  blockchain: '/wallets/blockchain.svg',
  ledger: '/wallets/ledger.svg',
}

/** The keys offered for an on-chain wallet account, in the order the picker shows them. */
export const WALLET_LOGO_CHOICES = [
  { key: 'blockchain', labelKey: 'accounts.logoBlockchain' },
  { key: 'ledger', labelKey: 'accounts.logoLedger' },
] as const

/** The bundled logo for a provider, or `null` when none ships with the app. */
export function providerLogoUrl(provider: string | null | undefined): string | null {
  if (!provider) return null
  return PROVIDER_LOGOS[provider.toUpperCase()] ?? null
}

/** The bundled logo an account explicitly points at, or `null` when it points at nothing known. */
export function logoKeyUrl(logoKey: string | null | undefined): string | null {
  if (!logoKey) return null
  return LOGO_KEYS[logoKey] ?? null
}

/** The bundled mark for a holding matched by prefix, known ticker, or issuer name, or `null`. */
export function bundledHoldingLogoUrl(
  ticker: string | null | undefined,
  name?: string | null,
): string | null {
  if (ticker) {
    const upper = ticker.toUpperCase().trim()
    for (const [prefix, url] of Object.entries(HOLDING_PREFIX_LOGOS)) {
      if (upper.startsWith(prefix)) return url
    }
    if (HOLDING_TICKER_LOGOS[upper]) return HOLDING_TICKER_LOGOS[upper]
    const base = upper.split('.')[0]
    if (base && HOLDING_TICKER_LOGOS[base]) return HOLDING_TICKER_LOGOS[base]
  }
  if (name) {
    for (const [pattern, url] of HOLDING_ISSUER_PATTERNS) {
      if (pattern.test(name)) return url
    }
  }
  return null
}
