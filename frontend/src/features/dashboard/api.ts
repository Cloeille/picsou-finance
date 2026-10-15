import { api } from '@/lib/api-client'
import type { DashboardData } from '@/types/api'

export interface IntradayPoint {
  timestamp: string
  total: number
  invested?: number
  /** Absent on backend intraday points; synthetic series (holding modal) may set it. */
  pnl?: number
}

export interface PnlData {
  total: number
  invested: number | null
  pnl: number | null
  pnlPercent: number | null
  valueAtFrom: number | null
  rangePnl: number | null
  rangePnlPercent: number | null
}

export const dashboardApi = {
  get: (range?: string) =>
    api.get<DashboardData>('/dashboard', { params: range ? { range } : {} }).then(r => r.data),

  getIntraday: (accountIds: number[]) =>
    api.get<IntradayPoint[]>('/history/net-worth/intraday', {
      params: { accountIds: accountIds.join(',') },
    }).then(r => r.data),

  getPnl: (accountIds: number[], from?: string) =>
    api.get<PnlData>('/history/pnl', {
      params: { accountIds: accountIds.join(','), ...(from ? { from } : {}) },
    }).then(r => r.data),
}
