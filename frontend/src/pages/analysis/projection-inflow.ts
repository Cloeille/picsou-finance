/**
 * Pure helpers for the projection's detected monthly inflow suggestion and goal prefill links.
 */

const MIN_SUGGESTION_GAP_EUR = 50
const MIN_SUGGESTION_RATIO = 1.5

/**
 * Show the detected-inflow suggestion only when no recurring investment plan is configured,
 * or when the observed pace is meaningfully higher than the configured monthly total.
 */
export function shouldSuggestDetectedInflow(
  monthlyInflowEur: number,
  detectedMonthlyInflowEur: number | null | undefined,
  detectedWindowMonths: number | null | undefined,
): boolean {
  if (
    detectedMonthlyInflowEur == null ||
    detectedMonthlyInflowEur <= 0 ||
    detectedWindowMonths == null ||
    detectedWindowMonths <= 0
  ) {
    return false
  }
  if (monthlyInflowEur <= 0) {
    return true
  }
  return (
    detectedMonthlyInflowEur - monthlyInflowEur >= MIN_SUGGESTION_GAP_EUR &&
    detectedMonthlyInflowEur >= monthlyInflowEur * MIN_SUGGESTION_RATIO
  )
}

export function buildRecurringGoalUrl(options?: {
  amount?: number | null
  accountId?: number | null
}): string {
  const params = new URLSearchParams({ create: 'recurring' })
  if (options?.amount != null && Number.isFinite(options.amount) && options.amount > 0) {
    params.set('amount', String(Math.round(options.amount)))
  }
  if (
    options?.accountId != null &&
    Number.isInteger(options.accountId) &&
    options.accountId > 0
  ) {
    params.set('accountId', String(options.accountId))
  }
  return `/goals?${params.toString()}`
}

export interface RecurringGoalPrefill {
  open: boolean
  monthlyAmount: string
  accountIds: number[]
}

export function parseRecurringGoalPrefill(params: URLSearchParams): RecurringGoalPrefill {
  if (params.get('create') !== 'recurring') {
    return { open: false, monthlyAmount: '', accountIds: [] }
  }
  const rawAmount = params.get('amount')
  const parsedAmount = rawAmount != null ? Number(rawAmount) : NaN
  const monthlyAmount =
    Number.isFinite(parsedAmount) && parsedAmount > 0 ? String(Math.round(parsedAmount)) : ''

  const rawAccountId = params.get('accountId')
  const parsedAccountId = rawAccountId != null ? Number(rawAccountId) : NaN
  const accountIds =
    Number.isInteger(parsedAccountId) && parsedAccountId > 0 ? [parsedAccountId] : []

  return { open: true, monthlyAmount, accountIds }
}
