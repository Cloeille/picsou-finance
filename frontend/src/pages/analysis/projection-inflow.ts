/**
 * Pure helpers for the projection's detected monthly inflow suggestion and goal prefill links.
 */

const MIN_SUGGESTION_GAP_EUR = 50
const MIN_SUGGESTION_RATIO = 1.5
export const MAX_PREFILL_MONTHLY_AMOUNT_EUR = 1_000_000

const POSITIVE_INT_PATTERN = /^\d+$/

function sanitizeMonthlyAmount(value: number | null | undefined): number | null {
  if (value == null || !Number.isFinite(value) || value <= 0) {
    return null
  }
  const rounded = Math.round(value)
  if (rounded < 1 || rounded > MAX_PREFILL_MONTHLY_AMOUNT_EUR) {
    return null
  }
  return rounded
}

function sanitizeAccountId(value: number | null | undefined): number | null {
  if (value == null || !Number.isSafeInteger(value) || value <= 0) {
    return null
  }
  return value
}

/**
 * Show the detected-inflow suggestion only when no recurring investment plan is configured,
 * or when the observed pace is meaningfully higher than the configured monthly total.
 */
export function shouldSuggestDetectedInflow(
  monthlyInflowEur: number,
  detectedMonthlyInflowEur: number | null | undefined,
  detectedActiveMonths: number | null | undefined,
): boolean {
  if (
    detectedMonthlyInflowEur == null ||
    detectedMonthlyInflowEur <= 0 ||
    detectedActiveMonths == null ||
    detectedActiveMonths <= 0
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
  const amount = sanitizeMonthlyAmount(options?.amount)
  if (amount != null) {
    params.set('amount', String(amount))
  }
  const accountId = sanitizeAccountId(options?.accountId)
  if (accountId != null) {
    params.set('accountId', String(accountId))
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
  const rawAmount = params.get('amount')?.trim() ?? ''
  const parsedAmount = rawAmount !== '' ? Number(rawAmount) : NaN
  const sanitizedAmount = sanitizeMonthlyAmount(parsedAmount)
  const monthlyAmount = sanitizedAmount != null ? String(sanitizedAmount) : ''

  const rawAccountId = params.get('accountId')?.trim() ?? ''
  const parsedAccountId = POSITIVE_INT_PATTERN.test(rawAccountId) ? Number(rawAccountId) : NaN
  const sanitizedAccountId = sanitizeAccountId(parsedAccountId)
  const accountIds = sanitizedAccountId != null ? [sanitizedAccountId] : []

  return { open: true, monthlyAmount, accountIds }
}
