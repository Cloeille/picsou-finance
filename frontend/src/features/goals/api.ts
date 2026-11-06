import { api } from '@/lib/api-client'
import type { GoalProgress, GoalRequest, GoalMonthEntry } from '@/types/api'

export const goalsApi = {
  list: () => api.get<GoalProgress[]>('/goals').then(r => r.data),
  get: (id: number) => api.get<GoalProgress>(`/goals/${id}`).then(r => r.data),
  create: (data: GoalRequest) => api.post<GoalProgress>('/goals', data).then(r => r.data),
  update: (id: number, data: GoalRequest) => api.put<GoalProgress>(`/goals/${id}`, data).then(r => r.data),
  delete: (id: number) => api.delete(`/goals/${id}`),
  getMonths: (id: number) => api.get<GoalMonthEntry[]>(`/goals/${id}/months`).then(r => r.data),
  extendHistory: (id: number) =>
    api.post<GoalProgress>(`/goals/${id}/history/extend`).then(r => r.data),
  extendHistoryByMonth: (id: number) =>
    api.post<GoalProgress>(`/goals/${id}/history/extend/month`).then(r => r.data),
  setMonthOverride: (id: number, ym: string, amount: number) =>
    api.put<GoalMonthEntry>(`/goals/${id}/months/${ym}`, { amount }).then(r => r.data),
  deleteMonthOverride: (id: number, ym: string) =>
    api.delete<GoalMonthEntry>(`/goals/${id}/months/${ym}`).then(r => r.data),
  setManualContribution: (id: number, ym: string, amount: number) =>
    api.put<GoalMonthEntry>(`/goals/${id}/months/${ym}/manual`, { amount }).then(r => r.data),
  deleteManualContribution: (id: number, ym: string) =>
    api.delete<GoalMonthEntry>(`/goals/${id}/months/${ym}/manual`).then(r => r.data),
}
