import { api } from '@/lib/api-client'
import type { Account, SavingsConfigRequest, SavingsInterestProjection, SavingsSuggestion } from '@/types/api'

export const savingsApi = {
  suggestions: (): Promise<SavingsSuggestion[]> =>
    api.get<SavingsSuggestion[]>('/savings/suggestions').then(r => r.data),

  setConfig: (accountId: number, data: SavingsConfigRequest): Promise<Account> =>
    api.put<Account>(`/accounts/${accountId}/savings-config`, data).then(r => r.data),

  deleteConfig: (accountId: number): Promise<void> =>
    api.delete(`/accounts/${accountId}/savings-config`).then(() => undefined),

  getInterest: (accountId: number): Promise<SavingsInterestProjection> =>
    api.get<SavingsInterestProjection>(`/accounts/${accountId}/savings-interest`).then(r => r.data),
}
