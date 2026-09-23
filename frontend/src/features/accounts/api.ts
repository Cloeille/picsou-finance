import { api } from '@/lib/api-client'
import type { Account, AccountDeletionImpact, AccountRequest, BalanceSnapshot, DebtRequest, DebtInfo, HoldingResponse, LoanScheduleResponse, Ownership, OwnershipRequest, PropertyValuation, PropertyValuationHistoryEntry, RealEstateMetadataRequest, RealEstateMetadata, RealEstateSummary, RealizedPnlResponse, ScpiPosition, ScpiPositionRequest, SecurityInsight, Transaction, TransactionImportPreviewResponse, TransactionImportRequest, TransactionImportResultResponse, TransactionRequest, ExchangePositionResponse } from '@/types/api'

export const accountsApi = {
  list: () => api.get<Account[]>('/accounts').then(r => r.data),
  get: (id: number) => api.get<Account>(`/accounts/${id}`).then(r => r.data),
  create: (data: AccountRequest) => api.post<Account>('/accounts', data).then(r => r.data),
  update: (id: number, data: AccountRequest) => api.put<Account>(`/accounts/${id}`, data).then(r => r.data),
  delete: (id: number) => api.delete(`/accounts/${id}`),
  deletionImpact: (id: number) =>
    api.get<AccountDeletionImpact>(`/accounts/${id}/deletion-impact`).then(r => r.data),
  history: (id: number, from?: string, to?: string) =>
    api.get<BalanceSnapshot[]>(`/accounts/${id}/history`, { params: { from, to } }).then(r => r.data),
  holdings: (id: number) =>
    api.get<HoldingResponse[]>(`/accounts/${id}/holdings`).then(r => r.data),

  // Empty for every account without a per-product breakdown (i.e. everything but a crypto
  // exchange), in which case the caller keeps the flat holdings table.
  positions: (id: number) =>
    api.get<ExchangePositionResponse[]>(`/accounts/${id}/positions`).then(r => r.data),
  transactions: (id: number) =>
    api.get<Transaction[]>(`/accounts/${id}/transactions`).then(r => r.data),
  prices: (tickers: string[]) =>
    api.get<Record<string, number>>('/prices', { params: { tickers: tickers.join(',') } }).then(r => r.data),
  priceHistory: (ticker: string, months: number = 12) =>
    api.get<Array<{ date: string; priceEur: number }>>(`/prices/${ticker}/history`, { params: { months } }).then(r => r.data),
  priceIntraday: (ticker: string) =>
    api.get<Array<{ timestamp: string; priceEur: number }>>(`/prices/${ticker}/intraday`).then(r => r.data),
  securityInsight: (ticker: string, name?: string | null) =>
    api.get<SecurityInsight>(`/securities/${encodeURIComponent(ticker)}/insight`, {
      params: name ? { name } : undefined,
    }).then(r => r.data),
  addSnapshot: (id: number, balance: number, date: string) =>
    api.post<BalanceSnapshot>(`/accounts/${id}/history`, { balance, date }).then(r => r.data),
  updateRealEstateMetadata: (id: number, data: RealEstateMetadataRequest) =>
    api.put<RealEstateMetadata>(`/accounts/${id}/real-estate`, data).then(r => r.data),
  updateScpiPosition: (id: number, data: ScpiPositionRequest) =>
    api.put<ScpiPosition>(`/accounts/${id}/scpi`, data).then(r => r.data),
  updateDebtMetadata: (id: number, data: DebtRequest) =>
    api.put<DebtInfo>(`/accounts/${id}/debt`, data).then(r => r.data),
  loanSummary: (id: number) =>
    api.get<LoanScheduleResponse>(`/accounts/${id}/loan-summary`).then(r => r.data),
  addTransaction: (id: number, data: TransactionRequest) =>
    api.post<Transaction>(`/accounts/${id}/transactions`, data).then(r => r.data),
  deleteTransaction: (accountId: number, txId: number) =>
    api.delete(`/accounts/${accountId}/transactions/${txId}`),
  updateTransaction: (accountId: number, txId: number, data: TransactionRequest) =>
    api.put<Transaction>(`/accounts/${accountId}/transactions/${txId}`, data).then(r => r.data),
  updateHolding: (accountId: number, ticker: string, data: { quantity: number; averageBuyIn?: number }) =>
    api.put<HoldingResponse>(`/accounts/${accountId}/holdings/${ticker}`, data).then(r => r.data),
  deleteHolding: (accountId: number, ticker: string) =>
    api.delete(`/accounts/${accountId}/holdings/${ticker}`),
  importPreview: (id: number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return api.post<TransactionImportPreviewResponse>(
      `/accounts/${id}/transactions/import/preview`, form,
      { headers: { 'Content-Type': 'multipart/form-data' } },
    ).then(r => r.data)
  },
  importExecute: (id: number, data: TransactionImportRequest) =>
    api.post<TransactionImportResultResponse>(`/accounts/${id}/transactions/import`, data).then(r => r.data),
  realizedPnl: (id: number) =>
    api.get<RealizedPnlResponse>(`/accounts/${id}/realized-pnl`).then(r => r.data),
  /**
   * Always resolves: a non-OK `status` in the body explains why no figure could be produced
   * (uncovered area, missing living area) and is information to render, not an error.
   */
  refreshValuation: (id: number) =>
    api.post<PropertyValuation>(`/accounts/${id}/valuation/refresh`).then(r => r.data),
  ownership: (id: number) =>
    api.get<Ownership>(`/accounts/${id}/ownership`).then(r => r.data),
  updateOwnership: (id: number, data: OwnershipRequest) =>
    api.put<Ownership>(`/accounts/${id}/ownership`, data).then(r => r.data),
}

export const realEstateApi = {
  summary: () => api.get<RealEstateSummary>('/real-estate/summary').then(r => r.data),
  valuations: (accountId: number) =>
    api.get<PropertyValuationHistoryEntry[]>(`/real-estate/${accountId}/valuations`).then(r => r.data),
}
