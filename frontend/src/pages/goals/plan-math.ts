import { parseAmount } from '@/lib/utils'
import type { GoalProgress } from '@/types/api'

/** One line of a plan's split while it is being edited: the amount stays as typed text. */
export interface AllocationDraft {
  ticker: string
  amount: string
}

/** parseAmount returns NaN for an empty or half-typed field; every total here wants 0. */
export function draftAmount(raw: string): number {
  const parsed = parseAmount(raw)
  return Number.isFinite(parsed) ? parsed : 0
}

export function allocatedTotal(allocations: AllocationDraft[]): number {
  return allocations.reduce((sum, a) => sum + draftAmount(a.amount), 0)
}

/** True when any checked holding still lacks a positive monthly amount. */
export function hasIncompleteAllocations(allocations: AllocationDraft[]): boolean {
  return allocations.some(a => draftAmount(a.amount) <= 0)
}

/** Splits `total` equally across checked holdings, placing any rounding cent on the last row. */
export function splitEqually(allocations: AllocationDraft[], total: number): AllocationDraft[] {
  const count = allocations.length
  if (count === 0 || !Number.isFinite(total) || total <= 0) return allocations
  const totalCents = Math.round(total * 100)
  const baseCents = Math.floor(totalCents / count)
  const remainderCents = totalCents - baseCents * count
  return allocations.map((a, idx) => {
    const cents = idx === count - 1 ? baseCents + remainderCents : baseCents
    const euros = cents / 100
    const formatted = Number.isInteger(euros) ? String(euros) : euros.toFixed(2)
    return { ...a, amount: formatted }
  })
}

/**
 * Whether a plan is paying in this month.
 *
 * A plan that starts next year or ended last one still belongs on the page — it is a record the
 * member keeps — but it contributes nothing to what they are putting aside today.
 */
export function isActiveThisMonth(goal: GoalProgress, today: string): boolean {
  if (goal.startDate != null && goal.startDate > today) return false
  if (goal.endDate != null && goal.endDate < today) return false
  return true
}

/**
 * What the member's running plans add up to per month.
 *
 * Deliberately *not* ownership-weighted, unlike `ProjectionService.monthlyInflowEur`: this figure
 * sits directly above the plan cards, which each print their raw monthly amount, and two numbers
 * that disagree on the same screen are worse than one that is approximate about a joint account.
 */
export function monthlyContributions(plans: GoalProgress[], today: string): number {
  return plans
    .filter(plan => isActiveThisMonth(plan, today))
    .reduce((sum, plan) => sum + (plan.monthlyAmount ?? 0), 0)
}
