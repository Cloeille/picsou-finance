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
 * `Meria`; for Amundi it is `AmundiSyncService.PROVIDER`, accent and all, and for BoursoBank
 * `BoursoSyncService.PROVIDER`; Trade Republic writes
 * its name inline in `TradeRepublicSyncService.upsertAccount`. `provider-logos.test.ts` pins
 * every literal so a rename on either side fails loudly instead of silently dropping the logo.
 */
export const PROVIDER_LOGOS: Record<string, string> = {
  MERIA: '/exchanges/meria.svg',
  'AMUNDI ÉPARGNE SALARIALE': '/providers/amundi.png',
  BOURSOBANK: '/providers/boursobank.png',
  'TRADE REPUBLIC': '/providers/trade-republic.svg',
}

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
