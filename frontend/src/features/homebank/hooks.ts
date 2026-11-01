import { useMutation } from '@tanstack/react-query'
import { useInvalidatingMutation } from '@/hooks/use-invalidating-mutation'
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
  return useInvalidatingMutation({
    mutationFn: (request: HomeBankImportRequest) => homeBankApi.execute(request),
    // Account transaction queries are keyed ['accounts', id, 'transactions']; invalidating
    // ['accounts'] also refreshes every cached transaction ledger.
    invalidates: [['accounts'], ['budget'], ['dashboard']],
  })
}
