import { useMutation, useQueryClient } from '@tanstack/react-query'
import { actualBudgetApi } from './api'
import type { ActualImportRequest } from './types'

/**
 * Every cache an import can change: accounts and balances (['accounts'] also covers each
 * account's transaction ledger), categories, budget figures, the dashboard, the net-worth
 * history rebuilt from snapshots, and the analysis built on balances and spending.
 */
const ACTUAL_IMPORT_INVALIDATIONS = [
  ['accounts'], ['categories'], ['budget'], ['dashboard'], ['history'], ['analysis'],
] as const

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
      for (const queryKey of ACTUAL_IMPORT_INVALIDATIONS) {
        void queryClient.invalidateQueries({ queryKey })
      }
    },
  })
}
