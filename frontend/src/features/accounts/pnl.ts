import type { AccountPoint } from '@/features/history/api'
import type { Account } from '@/types/api'

/**
 * Per-account cost basis and gain/loss at one point in history.
 *
 * <p>For everything the backend prices from holdings, the snapshot already carries both
 * figures and they agree by construction — an asset it could not price is dropped from the
 * value *and* from the basis (see {@code AccountService.valuation}). Those pass straight
 * through.
 *
 * <p>A property is the exception. It holds nothing, so `AccountService.valuation` falls back
 * to `invested = currentBalance` and its PnL is structurally 0 — yet its real basis (purchase
 * price plus every acquisition fee) is right there on the account as
 * `realEstate.costBasis`, and is what `RealEstateSummaryCard` and the property valuation chart
 * already measure against. These helpers substitute it so the accounts page reports the same
 * gain those two do.
 */

/** The property's basis, or null when it has none we can measure a gain against. */
function propertyCostBasis(account: Account): number | null {
  if (account.type !== 'REAL_ESTATE') return null
  const basis = account.realEstate?.costBasis
  // `== null` on purpose: Jackson's non_null inclusion omits the key, so a null arrives as
  // undefined. See docs/conventions/api-rest.md.
  return basis == null || basis <= 0 ? null : basis
}

/**
 * Cost basis to weigh {@code point} against — the denominator of a gain percentage.
 *
 * <p>A property described but never given a purchase price reports its own balance, which
 * makes its PnL 0 rather than its whole value. Leaving it out of the denominator while its
 * balance stayed in the numerator is exactly the partial-basis mismatch that once showed -85%
 * on an account that had not moved.
 */
export function accountInvestedAt(account: Account, point: AccountPoint): number {
  const basis = propertyCostBasis(account)
  if (account.type === 'REAL_ESTATE') return basis ?? point.total
  return point.invested
}

/** Gain/loss of {@code account} at {@code point}, measured against {@link accountInvestedAt}. */
export function accountPnlAt(account: Account, point: AccountPoint): number {
  if (account.type !== 'REAL_ESTATE') return point.pnl
  const basis = propertyCostBasis(account)
  return basis == null ? 0 : point.total - basis
}

/** Whether {@code account} has a gain/loss worth showing — i.e. a basis distinct from its value. */
export function hasMeasurableGain(account: Account): boolean {
  return propertyCostBasis(account) != null
}
