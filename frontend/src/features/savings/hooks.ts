import { useQuery } from '@tanstack/react-query'
import { useInvalidatingMutation } from '@/hooks/use-invalidating-mutation'
import { savingsApi } from './api'
import type { SavingsConfigRequest } from '@/types/api'
import { QUERY_STALE_TIMES } from '@/lib/constants'

export function useSavingsSuggestions() {
  return useQuery({
    queryKey: ['savings', 'suggestions'],
    queryFn: savingsApi.suggestions,
    staleTime: QUERY_STALE_TIMES.accounts,
  })
}

export function useSetSavingsConfig() {
  return useInvalidatingMutation({
    mutationFn: ({ accountId, data }: { accountId: number; data: SavingsConfigRequest }) =>
      savingsApi.setConfig(accountId, data),
    invalidates: (_data, variables) => [
      ['accounts'],
      ['accounts', variables.accountId],
      ['savings', 'suggestions'],
      ['savings', 'interest', variables.accountId],
    ],
  })
}

export function useDeleteSavingsConfig() {
  return useInvalidatingMutation({
    mutationFn: (accountId: number) => savingsApi.deleteConfig(accountId),
    invalidates: (_data, accountId) => [
      ['accounts'],
      ['accounts', accountId],
      ['savings', 'suggestions'],
      ['savings', 'interest', accountId],
    ],
  })
}

export function useSavingsInterest(accountId: number, enabled = true) {
  return useQuery({
    queryKey: ['savings', 'interest', accountId],
    queryFn: () => savingsApi.getInterest(accountId),
    staleTime: QUERY_STALE_TIMES.accountDetail,
    enabled: enabled && Number.isFinite(accountId),
  })
}
