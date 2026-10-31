import { api } from '@/lib/api-client'
import type {
  AiJobStatus,
  AllocationResponse,
  Budget,
  BudgetRequest,
  BudgetSettings,
  BudgetSettingsRequest,
  CashflowFlowResponse,
  CashflowPeriod,
  CashflowResponse,
  Category,
  CategorizationRule,
  CategorizationRuleRequest,
  CategoryRequest,
  CategorizeRequest,
  RecurringActivity,
  RecurringOccurrence,
  RecurringSeries,
  RecurringSeriesRequest,
  RecurringStatus,
  RulePreviewRequest,
  RulePreviewResponse,
  SpendingByCategoryResponse,
  SpendingDetailResponse,
  Transaction,
  UncategorizedTransaction,
} from '@/types/api'

/**
 * One flat namespace for the whole Budget module. The endpoints mirror the backend
 * controllers verbatim (the Budget, Category, Cashflow and Recurring controllers in
 * com.picsou.controller). Aggregations (cashflow, allocation) are read-only; everything
 * else has a Request twin.
 */
export const budgetApi = {
  // ─── Categories ───────────────────────────────────────────────────────────
  listCategories: () => api.get<Category[]>('/categories').then(r => r.data),
  createCategory: (data: CategoryRequest) =>
    api.post<Category>('/categories', data).then(r => r.data),
  updateCategory: (id: number, data: CategoryRequest) =>
    api.put<Category>(`/categories/${id}`, data).then(r => r.data),
  archiveCategory: (id: number) => api.delete(`/categories/${id}`),
  unarchiveCategory: (id: number) =>
    api.post<Category>(`/categories/${id}/unarchive`).then(r => r.data),

  // ─── Categorization rules ─────────────────────────────────────────────────
  listRules: () => api.get<CategorizationRule[]>('/categorization-rules').then(r => r.data),
  createRule: (data: CategorizationRuleRequest) =>
    api.post<CategorizationRule>('/categorization-rules', data).then(r => r.data),
  updateRule: (id: number, data: CategorizationRuleRequest) =>
    api.put<CategorizationRule>(`/categorization-rules/${id}`, data).then(r => r.data),
  deleteRule: (id: number) => api.delete(`/categorization-rules/${id}`),
  recategorize: () =>
    api.post<{ categorized: number }>('/categorization-rules/recategorize').then(r => r.data),
  previewRule: (data: RulePreviewRequest) =>
    api.post<RulePreviewResponse>('/categorization-rules/preview', data).then(r => r.data),

  // ─── Cross-account transaction search ────────────────────────────────────
  searchTransactions: (from: string, to: string, accountId?: number, categoryId?: number) => {
    const params: Record<string, string | number> = { from, to }
    if (accountId != null) params.accountId = accountId
    if (categoryId != null) params.categoryId = categoryId
    return api.get<Transaction[]>('/transactions', { params }).then(r => r.data)
  },

  // ─── To-categorize inbox ──────────────────────────────────────────────────
  listUncategorized: () =>
    api.get<UncategorizedTransaction[]>('/transactions/uncategorized').then(r => r.data),
  categorize: (id: number, data: CategorizeRequest) =>
    api.put(`/transactions/${id}/category`, data),
  /** Start an async AI categorization job (202 + AiJobStatus). */
  startCategorizeAi: () =>
    api.post<AiJobStatus>('/transactions/categorize-ai').then(r => r.data),
  /** Poll the async AI categorization job status. */
  getCategorizeAiStatus: () =>
    api.get<AiJobStatus>('/transactions/categorize-ai/status').then(r => r.data),

  // ─── Envelopes ────────────────────────────────────────────────────────────
  listBudgets: () => api.get<Budget[]>('/budgets').then(r => r.data),
  createBudget: (data: BudgetRequest) => api.post<Budget>('/budgets', data).then(r => r.data),
  updateBudget: (id: number, data: BudgetRequest) =>
    api.put<Budget>(`/budgets/${id}`, data).then(r => r.data),
  deleteBudget: (id: number) => api.delete(`/budgets/${id}`),

  // ─── Settings (payday cycle) ──────────────────────────────────────────────
  getSettings: () => api.get<BudgetSettings>('/budget/settings').then(r => r.data),
  updateSettings: (data: BudgetSettingsRequest) =>
    api.put<BudgetSettings>('/budget/settings', data).then(r => r.data),

  // ─── Cashflow & allocation (read-only aggregations) ───────────────────────
  getCashflow: (period: CashflowPeriod, anchor?: string) =>
    api.get<CashflowResponse>('/cashflow', { params: { period, ...(anchor ? { anchor } : {}) } }).then(r => r.data),
  getCashflowFlow: (period: CashflowPeriod, anchor?: string) =>
    api.get<CashflowFlowResponse>('/cashflow/flow', { params: { period, ...(anchor ? { anchor } : {}) } }).then(r => r.data),
  getAllocation: (period: CashflowPeriod, anchor?: string) =>
    api.get<AllocationResponse>('/allocation', { params: { period, ...(anchor ? { anchor } : {}) } }).then(r => r.data),

  // ─── Spending breakdown & drill ───────────────────────────────────────────
  getSpendingByCategory: (period: CashflowPeriod, anchor?: string) =>
    api.get<SpendingByCategoryResponse>('/spending/by-category', { params: { period, ...(anchor ? { anchor } : {}) } })
      .then(r => r.data),
  getCategoryDetail: (categoryId: number, period: CashflowPeriod, anchor?: string) =>
    api.get<SpendingDetailResponse>(`/spending/category/${categoryId}`, { params: { period, ...(anchor ? { anchor } : {}) } })
      .then(r => r.data),

  // ─── Recurring series ─────────────────────────────────────────────────────
  listRecurring: (status?: RecurringStatus) =>
    api.get<RecurringSeries[]>('/recurring', { params: status ? { status } : undefined })
      .then(r => r.data),
  getCalendar: (horizonDays = 60) =>
    api.get<RecurringOccurrence[]>('/recurring/calendar', { params: { horizonDays } })
      .then(r => r.data),
  createRecurring: (data: RecurringSeriesRequest) =>
    api.post<RecurringSeries>('/recurring', data).then(r => r.data),
  updateRecurring: (id: number, data: RecurringSeriesRequest) =>
    api.put<RecurringSeries>(`/recurring/${id}`, data).then(r => r.data),
  confirmRecurring: (id: number) =>
    api.post<RecurringSeries>(`/recurring/${id}/confirm`).then(r => r.data),
  ignoreRecurring: (id: number) =>
    api.post<RecurringSeries>(`/recurring/${id}/ignore`).then(r => r.data),
  deleteRecurring: (id: number) => api.delete(`/recurring/${id}`),
  detectRecurring: () =>
    api.post<{ detected: number }>('/recurring/detect').then(r => r.data),
  // "What changed" feed (auto-confirms + price steps, member-scoped) and its context-aware undo.
  getRecurringActivity: () =>
    api.get<RecurringActivity[]>('/recurring/activity').then(r => r.data),
  undoRecurring: (id: number) =>
    api.post<RecurringSeries>(`/recurring/${id}/undo`).then(r => r.data),
}
