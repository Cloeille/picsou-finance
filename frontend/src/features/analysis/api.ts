import { api } from '@/lib/api-client'
import type {
  AllocationTargets,
  AllocationTargetsRequest,
  Diversification,
  EssentialExpenseEstimate,
  HoldingClassificationRequest,
  HoldingClassificationResponse,
  HoldingClassificationView,
  Projection,
  SecurityProfileRefresh,
  WealthPyramid,
} from '@/types/api'

export const analysisApi = {
  pyramid: () => api.get<WealthPyramid>('/analysis/pyramid').then(r => r.data),

  diversification: () =>
    api.get<Diversification>('/analysis/diversification').then(r => r.data),

  projection: (years: number) =>
    api.get<Projection>('/analysis/projection', { params: { years } }).then(r => r.data),

  targets: () => api.get<AllocationTargets>('/analysis/allocation-targets').then(r => r.data),

  saveTargets: (body: AllocationTargetsRequest) =>
    api.put<AllocationTargets>('/analysis/allocation-targets', body).then(r => r.data),

  expenseEstimate: () =>
    api.get<EssentialExpenseEstimate>('/analysis/essential-expenses/estimate').then(r => r.data),

  refreshSecurityProfiles: () =>
    api.post<SecurityProfileRefresh>('/analysis/security-profiles/refresh').then(r => r.data),

  // Classification lives under the account: it is the ownership of the account the ticker was
  // reached through that authorises the override, even though the row is keyed on the ticker.
  holdingClassification: (accountId: number, ticker: string) =>
    api
      .get<HoldingClassificationView>(
        `/accounts/${accountId}/holdings/${encodeURIComponent(ticker)}/classification`,
      )
      .then(r => r.data),

  classifyHolding: (accountId: number, ticker: string, body: HoldingClassificationRequest) =>
    api
      .put<HoldingClassificationResponse>(
        `/accounts/${accountId}/holdings/${encodeURIComponent(ticker)}/classification`,
        body,
      )
      .then(r => r.data),
}
