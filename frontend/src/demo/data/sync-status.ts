import type { ExchangeStatus, WalletStatus } from '@/types/api'

export const mockExchangeStatuses: ExchangeStatus[] = [
  {
    id: 1,
    exchangeType: 'BINANCE',
    status: 'CONNECTED',
    lastSyncedAt: '2025-03-15T08:00:00Z',
  },
  {
    id: 2,
    exchangeType: 'MERIA',
    status: 'CONNECTED',
    lastSyncedAt: '2025-03-15T08:00:00Z',
  },
]

export const mockWalletStatuses: WalletStatus[] = [
  {
    id: 1,
    chain: 'EVM',
    address: '0x742d35Cc6634C0532925a3b844Bc9e7595f2bD68',
    label: 'Ledger EVM',
    lastSyncedAt: '2025-03-15T08:00:00Z',
  },
]

export const mockRequisitions = [
  {
    id: 1,
    requisitionId: 'demo-requisition-1',
    institutionId: 'BNP_PARIBAS',
    institutionName: 'BNP Paribas',
    status: 'LINKED',
    authLink: null,
  },
]
