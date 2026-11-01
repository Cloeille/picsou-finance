import { useMutation } from '@tanstack/react-query'
import { useInvalidatingMutation } from '@/hooks/use-invalidating-mutation'
import { actualBudgetApi } from './api'
import type { ActualImportRequest } from './types'

/**
 * Every cache an import can change: accounts and balances (['accounts'] also covers each
 * account's transaction ledger), categories, budget figures, the dashboard and its intraday
 * net worth, the net-worth history and P&L rebuilt from snapshots, the analysis built on
 * balances and spending, goal progress (as on account deletion), and the savings suggestions
 * that list candidate accounts.
 */
const ACTUAL_IMPORT_INVALIDATIONS = [
  ['accounts'], ['categories'], ['budget'], ['dashboard'], ['net-worth-intraday'], ['history'], ['pnl'],
  ['analysis'], ['goals'], ['savings'],
] as const

export function usePreviewActualBudget() {
  return useMutation({
    mutationFn: (file: File) => actualBudgetApi.preview(file),
  })
}

export function usePlanActualImport() {
  return useMutation({
    mutationFn: (request: ActualImportRequest) => actualBudgetApi.plan(request),
  })
}

export function useImportActualBudget() {
  return useInvalidatingMutation({
    mutationFn: (request: ActualImportRequest) => actualBudgetApi.execute(request),
    invalidates: ACTUAL_IMPORT_INVALIDATIONS,
  })
}
