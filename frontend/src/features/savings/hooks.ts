import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
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
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ accountId, data }: { accountId: number; data: SavingsConfigRequest }) =>
      savingsApi.setConfig(accountId, data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['accounts', variables.accountId] })
      queryClient.invalidateQueries({ queryKey: ['savings', 'suggestions'] })
      queryClient.invalidateQueries({ queryKey: ['savings', 'interest', variables.accountId] })
    },
  })
}

export function useDeleteSavingsConfig() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (accountId: number) => savingsApi.deleteConfig(accountId),
    onSuccess: (_data, accountId) => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['accounts', accountId] })
      queryClient.invalidateQueries({ queryKey: ['savings', 'suggestions'] })
      queryClient.invalidateQueries({ queryKey: ['savings', 'interest', accountId] })
    },
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
