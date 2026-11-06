import { useQuery } from '@tanstack/react-query'
import { historyApi } from './api'
import { QUERY_STALE_TIMES } from '@/lib/constants'

export function useHistory(accountIds: number[], months = 12, split = false) {
  return useQuery({
    queryKey: ['history', accountIds, months, split],
    queryFn: () => historyApi.getHistory(accountIds, months, split),
    staleTime: QUERY_STALE_TIMES.dashboard,
    enabled: accountIds.length > 0,
  })
}
