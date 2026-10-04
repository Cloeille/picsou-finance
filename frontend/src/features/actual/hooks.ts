import { useMutation, useQueryClient } from '@tanstack/react-query'
import { actualBudgetApi } from './api'
import type { ActualImportRequest } from './types'

export function usePreviewActualBudget() {
  return useMutation({
    mutationFn: (file: File) => actualBudgetApi.preview(file),
  })
}

export function useImportActualBudget() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: ActualImportRequest) => actualBudgetApi.execute(request),
    onSuccess: () => {
      // ['accounts'] also covers every account's ['accounts', id, 'transactions'] ledger.
      void queryClient.invalidateQueries({ queryKey: ['accounts'] })
      void queryClient.invalidateQueries({ queryKey: ['budget'] })
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}
