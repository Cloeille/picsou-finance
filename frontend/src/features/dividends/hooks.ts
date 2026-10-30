import { useQuery } from '@tanstack/react-query'
import { dividendsApi } from './api'
import { QUERY_STALE_TIMES } from '@/lib/constants'

/** What an account page asks for. The budget card still passes a bare horizon. */
export interface UpcomingDividendsQuery {
  horizonDays?: number
  accountId?: number
}

/**
 * Upcoming capital income. Keyed apart from `['budget']` so a budget mutation does not
 * refetch a forecast the backend already refreshes on its own schedule. The stale time
 * matches the rest of the budget page: the numbers are estimates, not a live tape.
 *
 * A number keeps the budget card's call (`useUpcomingDividends(60)`) and its cache key
 * unchanged. An options object is the account page: `accountId` joins the key so two
 * comptes-titres do not share a forecast, and it is forwarded only when set.
 */
export function useUpcomingDividends(horizonDaysOrOptions: number | UpcomingDividendsQuery = 60) {
  const horizonDays = typeof horizonDaysOrOptions === 'number'
    ? horizonDaysOrOptions
    : (horizonDaysOrOptions.horizonDays ?? 60)
  const accountId = typeof horizonDaysOrOptions === 'number'
    ? undefined
    : horizonDaysOrOptions.accountId

  return useQuery({
    queryKey: accountId == null
      ? ['dividends', 'upcoming', horizonDays]
      : ['dividends', 'upcoming', horizonDays, accountId],
    queryFn: () => accountId == null
      ? dividendsApi.upcoming(horizonDays)
      : dividendsApi.upcoming(horizonDays, accountId),
    staleTime: QUERY_STALE_TIMES.budget,
  })
}
