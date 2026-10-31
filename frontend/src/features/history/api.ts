import { api } from '@/lib/api-client'

export interface AccountPoint {
  total: number
  invested: number | null
  pnl: number | null
}

export interface NetWorthPoint {
  date: string
  total: number
  invested: number | null
  pnl: number | null
  accounts?: Record<string, AccountPoint>
}

export interface PnlData {
  total: number
  invested: number | null
  pnl: number | null
  pnlPercent: number | null
}

export const historyApi = {
  getHistory: (accountIds: number[], months = 12, split = false) =>
    api.get<NetWorthPoint[]>('/history', {
      params: { accountIds: accountIds.join(','), months, split },
    }).then(r => r.data),

  getPnl: (accountIds: number[]) =>
    api.get<PnlData>('/history/pnl', {
      params: { accountIds: accountIds.join(',') },
    }).then(r => r.data),
}
