import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { budgetApi } from './api'
import type {
  BudgetRequest,
  BudgetSettingsRequest,
  CashflowPeriod,
  CategorizationRuleRequest,
  CategoryRequest,
  CategorizeRequest,
  RecurringSeriesRequest,
  RecurringStatus,
  RulePreviewRequest,
} from '@/types/api'
import { QUERY_STALE_TIMES } from '@/lib/constants'

/**
 * Query keys are all rooted at `['budget', …]` so a single `invalidateQueries({ queryKey:
 * ['budget'] })` refreshes every derived view at once. We use that broad sweep after any
 * mutation that changes the underlying transactions/categories (categorizing, editing an
 * envelope, moving the payday cycle), since cashflow/allocation/budgets are all aggregations
 * over the same data. CRUD that only touches a leaf list invalidates just that leaf.
 */
const ROOT = ['budget'] as const

// ─── Categories ──────────────────────────────────────────────────────────────

export function useCategories() {
  return useQuery({
    queryKey: ['budget', 'categories'],
    queryFn: budgetApi.listCategories,
    staleTime: QUERY_STALE_TIMES.budget,
  })
}

export function useCreateCategory() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (data: CategoryRequest) => budgetApi.createCategory(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['budget', 'categories'] }),
  })
}

export function useUpdateCategory() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: CategoryRequest }) =>
      budgetApi.updateCategory(id, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ROOT }),
  })
}

export function useArchiveCategory() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => budgetApi.archiveCategory(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ROOT }),
  })
}

export function useUnarchiveCategory() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => budgetApi.unarchiveCategory(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['budget', 'categories'] }),
  })
}

// ─── Categorization rules ────────────────────────────────────────────────────

export function useRules() {
  return useQuery({
    queryKey: ['budget', 'rules'],
    queryFn: budgetApi.listRules,
    staleTime: QUERY_STALE_TIMES.budget,
  })
}

export function useCreateRule() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (data: CategorizationRuleRequest) => budgetApi.createRule(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['budget', 'rules'] }),
  })
}

export function useUpdateRule() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: CategorizationRuleRequest }) =>
      budgetApi.updateRule(id, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['budget', 'rules'] }),
  })
}

export function useDeleteRule() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => budgetApi.deleteRule(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['budget', 'rules'] }),
  })
}

export function usePreviewRule() {
  return useMutation({
    mutationFn: (data: RulePreviewRequest) => budgetApi.previewRule(data),
  })
}

export function useRecategorize() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => budgetApi.recategorize(),
    onSuccess: () => qc.invalidateQueries({ queryKey: ROOT }),
  })
}

// ─── To-categorize inbox ─────────────────────────────────────────────────────

export function useUncategorized() {
  return useQuery({
    queryKey: ['budget', 'uncategorized'],
    queryFn: budgetApi.listUncategorized,
    staleTime: QUERY_STALE_TIMES.budget,
  })
}

export function useCategorize() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: CategorizeRequest }) =>
      budgetApi.categorize(id, data),
    // Categorizing a transaction moves money into envelopes/cashflow and may learn a rule.
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ROOT })
      qc.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

/** Poll the async AI categorization job; auto-refetches every 2 s while running. */
export function useCategorizeAiStatus(enabled: boolean) {
  return useQuery({
    queryKey: ['budget', 'ai-job'],
    queryFn: budgetApi.getCategorizeAiStatus,
    enabled,
    refetchInterval: (q) => (q.state.data?.running ? 2000 : false),
  })
}

/** Start an async AI categorization job and seed the query cache with the initial status. */
export function useStartCategorizeAi() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => budgetApi.startCategorizeAi(),
    onSuccess: (s) => { qc.setQueryData(['budget', 'ai-job'], s) },
  })
}

// ─── Envelopes ───────────────────────────────────────────────────────────────

export function useBudgets() {
  return useQuery({
    queryKey: ['budget', 'budgets'],
    queryFn: budgetApi.listBudgets,
    staleTime: QUERY_STALE_TIMES.budget,
  })
}

export function useCreateBudget() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (data: BudgetRequest) => budgetApi.createBudget(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['budget', 'budgets'] }),
  })
}

export function useUpdateBudget() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: BudgetRequest }) =>
      budgetApi.updateBudget(id, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['budget', 'budgets'] }),
  })
}

export function useDeleteBudget() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => budgetApi.deleteBudget(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['budget', 'budgets'] }),
  })
}

// ─── Settings (payday cycle) ─────────────────────────────────────────────────

export function useBudgetSettings() {
  return useQuery({
    queryKey: ['budget', 'settings'],
    queryFn: budgetApi.getSettings,
    staleTime: QUERY_STALE_TIMES.budget,
  })
}

export function useUpdateBudgetSettings() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (data: BudgetSettingsRequest) => budgetApi.updateSettings(data),
    // The cycle boundary changes every spent/income figure → refresh the whole module.
    onSuccess: () => qc.invalidateQueries({ queryKey: ROOT }),
  })
}

/**
 * Returns a builder that maps a brand id to its opt-in logo proxy URL, or `null` when logos
 * are disabled or the id is missing. Call this once at the top of a list component, then apply
 * the returned function per row — that keeps it out of loops (no rules-of-hooks violation) and
 * reads the (cached) settings only once. The URL is same-origin, so `<img>` sends the auth cookie.
 */
export function useMerchantLogoUrl(): (brandId: number | null | undefined) => string | null {
  const { data: settings } = useBudgetSettings()
  const enabled = settings?.logoFetchEnabled ?? false
  return useCallback(
    (brandId) => (enabled && brandId != null ? `/api/merchants/${brandId}/logo` : null),
    [enabled],
  )
}

// ─── Cashflow & allocation ───────────────────────────────────────────────────

export function useCashflow(period: CashflowPeriod, anchor?: string) {
  return useQuery({
    queryKey: ['budget', 'cashflow', period, anchor ?? 'now'],
    queryFn: () => budgetApi.getCashflow(period, anchor),
    staleTime: QUERY_STALE_TIMES.budget,
    placeholderData: keepPreviousData,
  })
}

export function useAllocation(period: CashflowPeriod, anchor?: string) {
  return useQuery({
    queryKey: ['budget', 'allocation', period, anchor ?? 'now'],
    queryFn: () => budgetApi.getAllocation(period, anchor),
    staleTime: QUERY_STALE_TIMES.budget,
    placeholderData: keepPreviousData,
  })
}

export function useCashflowFlow(period: CashflowPeriod, anchor?: string) {
  return useQuery({
    queryKey: ['budget', 'flow', period, anchor ?? 'now'],
    queryFn: () => budgetApi.getCashflowFlow(period, anchor),
    staleTime: QUERY_STALE_TIMES.budget,
    placeholderData: keepPreviousData,
  })
}

// ─── Spending breakdown & drill ──────────────────────────────────────────────

export function useSpendingByCategory(period: CashflowPeriod, anchor?: string) {
  return useQuery({
    queryKey: ['budget', 'spending', period, anchor ?? 'now'],
    queryFn: () => budgetApi.getSpendingByCategory(period, anchor),
    staleTime: QUERY_STALE_TIMES.budget,
    placeholderData: keepPreviousData,
  })
}

export function useCategoryDetail(categoryId: number, period: CashflowPeriod, anchor?: string) {
  return useQuery({
    queryKey: ['budget', 'spending', 'category', categoryId, period, anchor ?? 'now'],
    queryFn: () => budgetApi.getCategoryDetail(categoryId, period, anchor),
    staleTime: QUERY_STALE_TIMES.budget,
    placeholderData: keepPreviousData,
  })
}

// ─── Recurring series ────────────────────────────────────────────────────────

export function useRecurring(status?: RecurringStatus) {
  return useQuery({
    queryKey: ['budget', 'recurring', status ?? 'all'],
    queryFn: () => budgetApi.listRecurring(status),
    staleTime: QUERY_STALE_TIMES.budget,
  })
}

export function useRecurringCalendar(horizonDays = 60) {
  return useQuery({
    queryKey: ['budget', 'calendar', horizonDays],
    queryFn: () => budgetApi.getCalendar(horizonDays),
    staleTime: QUERY_STALE_TIMES.budget,
  })
}

function useRecurringMutation<TArgs>(fn: (args: TArgs) => Promise<unknown>) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['budget', 'recurring'] })
      qc.invalidateQueries({ queryKey: ['budget', 'calendar'] })
    },
  })
}

export function useCreateRecurring() {
  return useRecurringMutation((data: RecurringSeriesRequest) => budgetApi.createRecurring(data))
}

export function useUpdateRecurring() {
  return useRecurringMutation(({ id, data }: { id: number; data: RecurringSeriesRequest }) =>
    budgetApi.updateRecurring(id, data))
}

export function useConfirmRecurring() {
  return useRecurringMutation((id: number) => budgetApi.confirmRecurring(id))
}

export function useIgnoreRecurring() {
  return useRecurringMutation((id: number) => budgetApi.ignoreRecurring(id))
}

export function useDeleteRecurring() {
  return useRecurringMutation((id: number) => budgetApi.deleteRecurring(id))
}

export function useDetectRecurring() {
  const qc = useQueryClient()
  return useMutation({
    // No variables: detection scans existing transactions and upserts SUGGESTED series.
    mutationFn: () => budgetApi.detectRecurring(),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['budget', 'recurring'] })
      qc.invalidateQueries({ queryKey: ['budget', 'calendar'] })
      qc.invalidateQueries({ queryKey: ['budget', 'activity'] })
    },
  })
}

/** The "what changed" activity feed (silent auto-confirms + price steps), newest first. */
export function useRecurringActivity() {
  return useQuery({
    queryKey: ['budget', 'activity'],
    queryFn: budgetApi.getRecurringActivity,
    staleTime: QUERY_STALE_TIMES.budget,
  })
}

/** Reverse a feed entry (acknowledge a price step or reject a silent auto-confirm). */
export function useUndoRecurring() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => budgetApi.undoRecurring(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['budget', 'recurring'] })
      qc.invalidateQueries({ queryKey: ['budget', 'calendar'] })
      qc.invalidateQueries({ queryKey: ['budget', 'activity'] })
    },
  })
}

export function useTransactions(params: {
  from: string
  to: string
  accountId?: number
  categoryId?: number
}) {
  return useQuery({
    queryKey: ['budget', 'transactions', params],
    queryFn: () => budgetApi.searchTransactions(params.from, params.to, params.accountId, params.categoryId),
    staleTime: QUERY_STALE_TIMES.budget,
  })
}
