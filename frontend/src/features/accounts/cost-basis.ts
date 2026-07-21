/**
 * Cost-basis helpers shared by the holding editor.
 *
 * A holding's cost basis can be expressed two equivalent ways given its
 * quantity: the average buy-in price per unit, or the total amount invested
 * (`averageBuyIn × quantity`). Imported wallets initialise the average buy-in
 * to the market price at import time, so users typically want to correct it by
 * entering the total they actually invested — which is why the editor offers
 * both and keeps them in sync.
 */

/** Total invested for an average buy-in and quantity, or null if not derivable. */
export function totalFromAvg(averageBuyIn: number | null, quantity: number | null): number | null {
  if (averageBuyIn == null || !Number.isFinite(averageBuyIn) || quantity == null || !(quantity > 0)) return null
  return averageBuyIn * quantity
}

/** Average buy-in for a total invested and quantity, or null if not derivable. */
export function avgFromTotal(totalInvested: number | null, quantity: number | null): number | null {
  if (totalInvested == null || !Number.isFinite(totalInvested) || quantity == null || !(quantity > 0)) return null
  return totalInvested / quantity
}
