import { useMutation, useQueryClient } from '@tanstack/react-query'
import { homeBankApi } from './api'
import type { HomeBankImportRequest } from './types'

export function usePreviewHomeBank() {
  return useMutation({
    mutationFn: async (input: { file: File; password?: string; currency?: string }) => {
      try {
        return await homeBankApi.preview(input.file, input.password, input.currency)
      } finally {
        input.password = undefined
        input.currency = undefined
      }
    },
  })
}

export function useImportHomeBank() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: HomeBankImportRequest) => homeBankApi.execute(request),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['accounts'] })
      void queryClient.invalidateQueries({ queryKey: ['budget'] })
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      // Account transaction queries are keyed ['accounts', id, 'transactions']; invalidating
      // ['accounts'] above also refreshes every cached transaction ledger.
    },
  })
}
