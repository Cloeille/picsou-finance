import { useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { accountsApi, realEstateApi } from './api'
import type { AccountRequest, Account, DebtRequest, HoldingResponse, OwnershipRequest, RealEstateMetadataRequest, TransactionImportRequest, TransactionRequest } from '@/types/api'
import { HOLDING_ACCOUNT_TYPES, QUERY_STALE_TIMES } from '@/lib/constants'

export interface HoldingWithAccount extends HoldingResponse {
  accountName: string
  accountId: number
  accountType: Account['type']
}

export interface PortfolioLine {
  id: string
  name: string
  ticker: string | null
  quantity: number
  /**
   * Needed to classify the line: the write is authorised by the account it was reached through.
   * Null on the aggregated cash row, which spans several accounts and belongs to no one of them.
   */
  accountId: number | null
  accountName: string
  accountType: Account['type']
  accountColor: string
  valueEur: number
  costBasisEur: number | null
  averageBuyIn: number | null
  quoteCurrency: string | null
  pnlEur: number | null
  pnlPercent: number | null
  priceUpdatedAt: string | null
}


// Single source of truth: recompute the (value, cost, pnl, pct) trio from a live price.
// Keeps all four derived numbers consistent with the same price snapshot.
function recomputeWithLivePrice(
  input: { quantity: number; costBasisEur: number | null },
  livePrice: number,
) {
  const costBasisEur = input.costBasisEur
  const currentValueEur = input.quantity * livePrice
  const pnlEur = costBasisEur != null ? currentValueEur - costBasisEur : null
  // Math.abs: a short position has a negative cost basis — dividing by it would flip
  // the sign and show a winning short as a loss. Mirrors the backend formula.
  const pnlPercent = costBasisEur != null && costBasisEur !== 0
    ? (pnlEur! / Math.abs(costBasisEur)) * 100
    : null
  return { currentValueEur, costBasisEur, pnlEur, pnlPercent }
}

export function usePortfolio() {
  return useQuery({
    queryKey: ['accounts', 'portfolio'],
    queryFn: async (): Promise<PortfolioLine[]> => {
      const accounts = await accountsApi.list()
      const lines: PortfolioLine[] = []

      // Accounts with holdings — expand each holding as a line
      const holdingAccounts = accounts.filter(a => HOLDING_ACCOUNT_TYPES.includes(a.type))
      // No per-account catch: dropping a failed account's holdings silently understated
      // every total built from these lines (portfolio value, allocation, P&L) with nothing
      // on screen saying so. A failure now rejects the query and the surfaces render their
      // error state instead of a plausible-looking wrong number.
      const holdingResults = await Promise.all(
        holdingAccounts.map(async (account): Promise<PortfolioLine[]> => {
          const holdings = await accountsApi.holdings(account.id)
          return holdings.map(h => ({
            id: `${account.id}-${h.ticker}`,
            name: h.name ?? h.ticker,
            ticker: h.ticker,
            quantity: h.quantity,
            accountId: account.id,
            accountName: account.name,
            accountType: account.type,
            accountColor: account.color,
            valueEur: h.currentValueEur ?? 0,
            costBasisEur: h.costBasisEur,
            averageBuyIn: h.averageBuyIn,
            quoteCurrency: h.quoteCurrency ?? null,
            pnlEur: h.pnlEur,
            pnlPercent: h.pnlPercent,
            priceUpdatedAt: h.priceUpdatedAt,
          }))
        }),
      )
      lines.push(...holdingResults.flat())

      // Fetch live prices for all tickers
      const allTickers = [...new Set(lines.map(l => l.ticker).filter((t): t is string => t != null && t !== 'EUR'))]
      let livePrices: Record<string, number> = {}
      if (allTickers.length > 0) {
        try {
          livePrices = await accountsApi.prices(allTickers)
        } catch { /* keep backend prices */ }
      }

      const now = new Date().toISOString()
      const enriched = lines.map(l => {
        if (!l.ticker || l.ticker === 'EUR') return l
        const livePrice = livePrices[l.ticker]
        if (livePrice == null) return l // keep backend priceUpdatedAt
        const recomputed = recomputeWithLivePrice(
          { quantity: l.quantity, costBasisEur: l.costBasisEur },
          livePrice,
        )
        return {
          ...l,
          valueEur: recomputed.currentValueEur,
          costBasisEur: recomputed.costBasisEur,
          pnlEur: recomputed.pnlEur,
          pnlPercent: recomputed.pnlPercent,
          priceUpdatedAt: now,
        }
      })

      // Cash accounts — aggregate into a single "Euros" line (exclude LOAN accounts)
      const cashAccounts = accounts.filter(a => !HOLDING_ACCOUNT_TYPES.includes(a.type) && a.type !== 'LOAN')
      if (cashAccounts.length > 0) {
        enriched.push({
          id: 'cash-aggregated',
          name: 'Euros',
          ticker: 'EUR',
          quantity: 0,
          accountId: null,
          accountName: cashAccounts.map(a => a.name).join(', '),
          accountType: cashAccounts[0].type,
          accountColor: '#22c55e',
          valueEur: cashAccounts.reduce((sum, a) => sum + a.currentBalanceEur, 0),
          costBasisEur: null,
          averageBuyIn: null,
          quoteCurrency: 'EUR',
          pnlEur: null,
          pnlPercent: null,
          priceUpdatedAt: null,
        })
      }

      return enriched
    },
    staleTime: QUERY_STALE_TIMES.accountDetail,
    // Keep live prices actually live in an open tab (refetchOnWindowFocus is
    // globally off). PriceFreshnessDot's 3 min "live" threshold deliberately
    // sits above this 2 min interval (+ latency) so the dot doesn't flicker.
    refetchInterval: QUERY_STALE_TIMES.accountDetail,
  })
}

export function useAccounts() {
  return useQuery({
    queryKey: ['accounts'],
    queryFn: accountsApi.list,
    staleTime: QUERY_STALE_TIMES.accounts,
  })
}

export interface AccountTree {
  walletGroups: Array<{ wallet: Account; pockets: Account[] }>
  standaloneAccounts: Account[]
  nonPocketAccounts: Account[]
}

/**
 * Groups a flat account list into wallet -> pockets, matching Account.parentAccountId.
 * A pocket whose parent isn't present in `accounts` (e.g. the wallet was soft-deleted)
 * falls back into nonPocketAccounts/standaloneAccounts instead of silently vanishing.
 */
export function useAccountTree(accounts: Account[] | undefined): AccountTree {
  return useMemo(() => {
    const accountIds = new Set((accounts ?? []).map((a) => a.id))

    const pocketsByParent = new Map<number, Account[]>()
    for (const a of (accounts ?? [])) {
      if (a.parentAccountId != null && accountIds.has(a.parentAccountId)) {
        if (!pocketsByParent.has(a.parentAccountId)) pocketsByParent.set(a.parentAccountId, [])
        pocketsByParent.get(a.parentAccountId)!.push(a)
      }
    }

    const nonPocketAccounts = (accounts ?? []).filter(
      (a) => a.parentAccountId == null || !accountIds.has(a.parentAccountId),
    )

    const walletGroups = nonPocketAccounts
      .filter((a) => pocketsByParent.has(a.id))
      .map((wallet) => ({ wallet, pockets: pocketsByParent.get(wallet.id)! }))

    const standaloneAccounts = nonPocketAccounts.filter((a) => !pocketsByParent.has(a.id))

    return { walletGroups, standaloneAccounts, nonPocketAccounts }
  }, [accounts])
}

export function useAccount(id: number) {
  return useQuery({
    queryKey: ['accounts', id],
    queryFn: () => accountsApi.get(id),
    staleTime: QUERY_STALE_TIMES.accountDetail,
    enabled: !!id,
  })
}

// (useAccountHoldings was removed: it was unused and shared the query key
// ['accounts', id, 'holdings'] with useHoldingsWithLivePrices while running a
// different queryFn — a cache-collision trap.)

export function useAccountPositions(id: number) {
  return useQuery({
    queryKey: ['accounts', id, 'positions'],
    queryFn: () => accountsApi.positions(id),
  })
}

export function useHoldingsWithLivePrices(id: number) {
  return useQuery({
    queryKey: ['accounts', id, 'holdings'],
    queryFn: async (): Promise<HoldingResponse[]> => {
      const holdings = await accountsApi.holdings(id)
      if (holdings.length === 0) return holdings

      const tickers = [...new Set(holdings.map(h => h.ticker))]
      try {
        const livePrices = await accountsApi.prices(tickers)
        const now = new Date().toISOString()
        return holdings.map(h => {
          const livePrice = livePrices[h.ticker]
          if (livePrice == null) return h // keep backend priceUpdatedAt
          const recomputed = recomputeWithLivePrice(
            { quantity: h.quantity, costBasisEur: h.costBasisEur },
            livePrice,
          )
          return {
            ...h,
            currentPrice: livePrice,
            quoteCurrency: 'EUR',
            ...recomputed,
            priceUpdatedAt: now,
          }
        })
      } catch {
        return holdings // keep backend priceUpdatedAt
      }
    },
    staleTime: QUERY_STALE_TIMES.accountDetail,
    // Same rationale as usePortfolio: the "live" dot must not outlive the data.
    refetchInterval: QUERY_STALE_TIMES.accountDetail,
    enabled: !!id,
  })
}

export function useAccountTransactions(id: number) {
  return useQuery({
    queryKey: ['accounts', id, 'transactions'],
    queryFn: () => accountsApi.transactions(id),
    staleTime: QUERY_STALE_TIMES.accountDetail,
    enabled: !!id,
  })
}

export function useAccountHistory(id: number, from?: string) {
  return useQuery({
    queryKey: ['accounts', id, 'history', from],
    queryFn: () => accountsApi.history(id, from),
    staleTime: QUERY_STALE_TIMES.accountDetail,
    enabled: !!id,
  })
}

export function useCreateAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (data: AccountRequest) => accountsApi.create(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useUpdateAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: AccountRequest }) => accountsApi.update(id, data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      queryClient.invalidateQueries({ queryKey: ['accounts', variables.id] })
    },
  })
}

/**
 * What the confirmation dialog needs to warn about before a deletion: whether the connection
 * feeding this account goes with it, and its name. Fetched on demand (when a dialog opens with
 * an id) rather than folded into the account list, which would cost one query per account for
 * a value only ever read at that moment.
 */
export function useAccountDeletionImpact(accountId: number | null) {
  return useQuery({
    queryKey: ['accounts', accountId, 'deletion-impact'],
    queryFn: () => accountsApi.deletionImpact(accountId!),
    enabled: accountId !== null,
  })
}

export function useDeleteAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => accountsApi.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      queryClient.invalidateQueries({ queryKey: ['goals'] })
    },
  })
}

export function useAddSnapshot() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, balance, date }: { id: number; balance: number; date: string }) =>
      accountsApi.addSnapshot(id, balance, date),
    onSuccess: (_, { id }) => {
      queryClient.invalidateQueries({ queryKey: ['accounts', id, 'history'] })
      queryClient.invalidateQueries({ queryKey: ['accounts', id] })
    },
  })
}

export function useUpdateRealEstateMetadata() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: RealEstateMetadataRequest }) =>
      accountsApi.updateRealEstateMetadata(id, data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['accounts', variables.id] })
      queryClient.invalidateQueries({ queryKey: ['real-estate'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useUpdateDebtMetadata() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: DebtRequest }) =>
      accountsApi.updateDebtMetadata(id, data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ['accounts', variables.id] })
      queryClient.invalidateQueries({ queryKey: ['loan-summary', variables.id] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

/** Whole-portfolio property roll-up: gross value, mortgage debt and net equity. */
export function useRealEstateSummary(enabled: boolean = true) {
  return useQuery({
    queryKey: ['real-estate', 'summary'],
    queryFn: () => realEstateApi.summary(),
    staleTime: QUERY_STALE_TIMES.realEstate,
    enabled,
  })
}

export function usePropertyValuations(accountId: number, enabled: boolean = true) {
  return useQuery({
    queryKey: ['real-estate', accountId, 'valuations'],
    queryFn: () => realEstateApi.valuations(accountId),
    staleTime: QUERY_STALE_TIMES.realEstate,
    enabled: enabled && Number.isFinite(accountId),
  })
}

/**
 * Triggers a fresh estimate.
 *
 * <p>Invalidates the dashboard too: in ESTIMATED mode this writes the account balance, so
 * net worth moves with it.
 */
export function useRefreshValuation() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => accountsApi.refreshValuation(id),
    onSuccess: (_data, id) => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['real-estate'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      queryClient.invalidateQueries({ queryKey: ['real-estate', id, 'valuations'] })
    },
  })
}

export function useOwnership(id: number, enabled: boolean = true) {
  return useQuery({
    queryKey: ['accounts', id, 'ownership'],
    queryFn: () => accountsApi.ownership(id),
    staleTime: QUERY_STALE_TIMES.accountDetail,
    enabled: enabled && Number.isFinite(id),
  })
}

/** Changing a split changes every total the member sees, so this invalidates broadly. */
export function useUpdateOwnership() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: OwnershipRequest }) =>
      accountsApi.updateOwnership(id, data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['accounts', variables.id, 'ownership'] })
      queryClient.invalidateQueries({ queryKey: ['real-estate'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      queryClient.invalidateQueries({ queryKey: ['history'] })
    },
  })
}

export function useLoanSummary(id: number, enabled: boolean = true) {
  return useQuery({
    queryKey: ['loan-summary', id],
    queryFn: () => accountsApi.loanSummary(id),
    staleTime: QUERY_STALE_TIMES.accountDetail,
    enabled: enabled && Number.isFinite(id),
  })
}

export function useImportTRTransactions(accountId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => accountsApi.importTRTransactions(accountId, file),
    onSuccess: () => {
      // The CSV import creates transactions on multiple TR accounts (Cash, PEA, Titres),
      // so we need to invalidate all account-level queries — not just the triggering account.
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useAddTransaction(accountId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (data: TransactionRequest) => accountsApi.addTransaction(accountId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['accounts', accountId, 'transactions'] })
      queryClient.invalidateQueries({ queryKey: ['accounts', accountId, 'history'] })
      queryClient.invalidateQueries({ queryKey: ['accounts', accountId] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useDeleteTransaction(accountId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (txId: number) => accountsApi.deleteTransaction(accountId, txId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['accounts', accountId, 'transactions'] })
      queryClient.invalidateQueries({ queryKey: ['accounts', accountId, 'history'] })
      queryClient.invalidateQueries({ queryKey: ['accounts', accountId] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useUpdateTransaction(accountId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ txId, data }: { txId: number; data: TransactionRequest }) =>
      accountsApi.updateTransaction(accountId, txId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['accounts', accountId, 'transactions'] })
      queryClient.invalidateQueries({ queryKey: ['accounts', accountId, 'holdings'] })
      queryClient.invalidateQueries({ queryKey: ['accounts', accountId, 'history'] })
      queryClient.invalidateQueries({ queryKey: ['accounts', accountId] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useUpdateHolding(accountId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ ticker, data }: { ticker: string; data: { quantity: number; averageBuyIn?: number } }) =>
      accountsApi.updateHolding(accountId, ticker, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['accounts', accountId, 'holdings'] })
      queryClient.invalidateQueries({ queryKey: ['accounts', accountId] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useDeleteHolding(accountId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (ticker: string) => accountsApi.deleteHolding(accountId, ticker),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['accounts', accountId, 'holdings'] })
      queryClient.invalidateQueries({ queryKey: ['accounts', accountId] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

// ---------------------------------------------------------------------------
// CSV transaction import + realized P&L
// ---------------------------------------------------------------------------

export function usePreviewImport(accountId: number) {
  return useMutation({
    mutationFn: (file: File) => accountsApi.importPreview(accountId, file),
  })
}

export function useExecuteImport(accountId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (data: TransactionImportRequest) => accountsApi.importExecute(accountId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['accounts', accountId, 'transactions'] })
      queryClient.invalidateQueries({ queryKey: ['accounts', accountId, 'holdings'] })
      queryClient.invalidateQueries({ queryKey: ['accounts', accountId, 'history'] })
      queryClient.invalidateQueries({ queryKey: ['accounts', accountId, 'realized-pnl'] })
      queryClient.invalidateQueries({ queryKey: ['accounts', accountId] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useRealizedPnl(accountId: number, enabled = true) {
  return useQuery({
    queryKey: ['accounts', accountId, 'realized-pnl'],
    queryFn: () => accountsApi.realizedPnl(accountId),
    staleTime: QUERY_STALE_TIMES.accountDetail,
    enabled: enabled && !!accountId,
  })
}

// ---------------------------------------------------------------------------
// Price history
// ---------------------------------------------------------------------------

export type PricePoint = { date: string; priceEur: number }

/**
 * Asset type + ETF composition for a ticker. Cached aggressively — composition
 * changes at most daily and the backend caches it for days too.
 */
export function useSecurityInsight(ticker: string | null, name: string | null, enabled = true) {
  return useQuery({
    queryKey: ['security-insight', ticker],
    queryFn: () => accountsApi.securityInsight(ticker!, name),
    enabled: enabled && !!ticker,
    staleTime: 24 * 60 * 60 * 1000,
  })
}

export function usePriceHistory(ticker: string | null, months: number, range: string) {
  const is24H = range === '24H'
  return useQuery({
    queryKey: ['price-history', ticker, is24H ? 'intraday' : months],
    queryFn: async (): Promise<PricePoint[]> => {
      if (is24H) {
        const data = await accountsApi.priceIntraday(ticker!)
        return data.map(p => ({ date: p.timestamp, priceEur: p.priceEur }))
      }
      return accountsApi.priceHistory(ticker!, months)
    },
    enabled: !!ticker,
    staleTime: 2 * 60 * 1000,
  })
}

export function useAllAccounts() {
  return useQuery({
    queryKey: ['accounts', 'all'],
    queryFn: accountsApi.listAll,
    staleTime: QUERY_STALE_TIMES.accounts,
  })
}

export function useToggleAccountVisibility() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, hidden }: { id: number; hidden: boolean }) => accountsApi.setVisibility(id, hidden),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      queryClient.invalidateQueries({ queryKey: ['history'] })
    },
  })
}
