import { useEffect } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { QUERY_STALE_TIMES } from '@/lib/constants'
import type { QueryKey } from '@tanstack/react-query'
import {
  bankSyncApi,
  trApi,
  cryptoExchangeApi,
  cryptoWalletApi,
  finaryApi,
  boursoApi,
  bourseDirectApi,
  degiroApi,
  amundiApi,
  ibkrApi,
} from './api'
import type {
  ExchangeType,
  ChainType,
  FinaryAccountMapping,
  FinaryImportRequest,
} from '@/types/api'

// ---------------------------------------------------------------------------
// Query keys
// ---------------------------------------------------------------------------

export const syncKeys = {
  all: ['sync'] as const,
  banks: () => [...syncKeys.all, 'banks'] as const,
  institutions: (q: string, country: string) => [...syncKeys.all, 'institutions', q, country] as const,
  countries: () => [...syncKeys.all, 'countries'] as const,
  tr: () => [...syncKeys.all, 'tr'] as const,
  bourso: () => [...syncKeys.all, 'bourso'] as const,
  bourseDirect: () => [...syncKeys.all, 'bourse-direct'] as const,
  degiro: () => [...syncKeys.all, 'degiro'] as const,
  amundi: () => [...syncKeys.all, 'amundi'] as const,
  ibkr: () => [...syncKeys.all, 'ibkr'] as const,
  exchanges: () => [...syncKeys.all, 'exchanges'] as const,
  wallets: () => [...syncKeys.all, 'wallets'] as const,
  finary: () => [...syncKeys.all, 'finary'] as const,
}

// ---------------------------------------------------------------------------
// Browser-sidecar session status (Bourse Direct, Amundi)
// ---------------------------------------------------------------------------

/**
 * Status poll shared by the sidecar providers: their backend only queues the
 * import, so an in-flight sync is polled every 1.5s (30s otherwise) and a
 * completed one refreshes the caches the imported accounts feed.
 */
function useSidecarSessionStatus<
  T extends { syncStatus: string; lastSyncCompletedAt: string | null },
>(queryKey: QueryKey, queryFn: () => Promise<T>) {
  const queryClient = useQueryClient()
  const query = useQuery({
    queryKey,
    queryFn,
    staleTime: 0,
    refetchInterval: currentQuery => {
      const state = currentQuery.state.data?.syncStatus
      return state === 'QUEUED' || state === 'RUNNING' ? 1_500 : 30_000
    },
  })
  const completedAt = query.data?.lastSyncCompletedAt
  const succeeded = query.data?.syncStatus === 'SUCCESS'

  useEffect(() => {
    if (!succeeded || !completedAt) return
    queryClient.invalidateQueries({ queryKey: ['accounts'] })
    queryClient.invalidateQueries({ queryKey: ['dashboard'] })
  }, [completedAt, queryClient, succeeded])

  return query
}

// ---------------------------------------------------------------------------
// Bank Sync (Enable Banking)
// ---------------------------------------------------------------------------

export function useBankSyncStatus() {
  return useQuery({
    queryKey: syncKeys.banks(),
    queryFn: bankSyncApi.getStatus,
    staleTime: 30_000,
    refetchInterval: 30_000,
  })
}

export function useSearchInstitutions(query: string, country: string) {
  return useQuery({
    queryKey: syncKeys.institutions(query, country),
    queryFn: () => bankSyncApi.searchInstitutions(query, country),
    enabled: query.length >= 2,
  })
}

/** Countries the active bank-sync provider covers, for the country picker. staleTime mirrors the backend's own 6h cache TTL. */
export function useBankCountries() {
  return useQuery({
    queryKey: syncKeys.countries(),
    queryFn: bankSyncApi.listCountries,
    staleTime: 6 * 60 * 60 * 1000,
  })
}

export function useInitiateBankSync() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      institutionId,
      institutionName,
    }: {
      institutionId: string
      institutionName: string
    }) => bankSyncApi.initiate(institutionId, institutionName),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.banks() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useCompleteBankSync() {
  const queryClient = useQueryClient()
  return useMutation({
    // `state` is the OAuth nonce echoed on the redirect — dropping it would
    // route the backend into the legacy latest-CREATED guess.
    mutationFn: ({ code, state }: { code: string; state?: string | null }) =>
      bankSyncApi.complete(code, state),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.banks() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useRetryBankSync() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => bankSyncApi.retry(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.banks() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

/**
 * Re-initiates the OAuth flow for a dead requisition. Navigating to the
 * returned authLink is the caller's concern (same as the initiate flow).
 */
export function useReconnectBankSync() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => bankSyncApi.reconnect(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.banks() })
    },
  })
}

export function useDeleteBankConnection() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => bankSyncApi.deleteConnection(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.banks() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

// ---------------------------------------------------------------------------
// Trade Republic
// ---------------------------------------------------------------------------

export function useTrSessionStatus() {
  return useQuery({
    queryKey: syncKeys.tr(),
    queryFn: trApi.getSessionStatus,
    staleTime: 30_000,
    refetchInterval: 60_000,
  })
}

export function useInitiateTrAuth() {
  return useMutation({
    mutationFn: ({ phoneNumber, pin }: { phoneNumber: string; pin: string }) =>
      trApi.initiateAuth(phoneNumber, pin),
  })
}

export function useCompleteTrAuth() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ processId, tan }: { processId: string; tan: string }) =>
      trApi.completeAuth(processId, tan),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.tr() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useSyncTradeRepublic() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => trApi.sync(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.tr() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useImportTrCsv() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => trApi.importCsv(file),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.tr() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useClearTrSession() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => trApi.clearSession(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.tr() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

// ---------------------------------------------------------------------------
// BoursoBank
// ---------------------------------------------------------------------------

export function useBoursoSessionStatus() {
  const queryClient = useQueryClient()
  const query = useQuery({
    queryKey: syncKeys.bourso(),
    queryFn: boursoApi.getStatus,
    staleTime: 0,
    refetchInterval: currentQuery => {
      const state = currentQuery.state.data?.syncStatus
      return state === 'QUEUED' || state === 'RUNNING' ? 1_500 : 30_000
    },
  })
  const completedAt = query.data?.lastSyncCompletedAt
  const succeeded = query.data?.syncStatus === 'SUCCESS'

  useEffect(() => {
    if (!succeeded || !completedAt) return
    queryClient.invalidateQueries({ queryKey: ['accounts'] })
    queryClient.invalidateQueries({ queryKey: ['dashboard'] })
  }, [completedAt, queryClient, succeeded])

  return query
}

export function useInitiateBoursoAuth() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ customerId, password }: { customerId: string; password: string }) =>
      boursoApi.initiateAuth(customerId, password),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.bourso() })
    },
  })
}

export function useCompleteBoursoAuth() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ processId }: { processId: string }) => boursoApi.completeAuth(processId),
    onSuccess: status => {
      queryClient.setQueryData(syncKeys.bourso(), status)
      queryClient.invalidateQueries({ queryKey: syncKeys.bourso() })
    },
  })
}

export function useSyncBourso() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: boursoApi.sync,
    onSuccess: status => {
      queryClient.setQueryData(syncKeys.bourso(), status)
      queryClient.invalidateQueries({ queryKey: syncKeys.bourso() })
    },
  })
}

export function useClearBoursoSession() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: boursoApi.clearSession,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.bourso() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

// ---------------------------------------------------------------------------
// DEGIRO
// ---------------------------------------------------------------------------

export function useDegiroSessionStatus() {
  return useQuery({
    queryKey: syncKeys.degiro(),
    queryFn: degiroApi.getStatus,
    staleTime: 30_000,
  })
}

export function useInitiateDegiroAuth() {
  return useMutation({
    mutationFn: ({ username, password }: { username: string; password: string }) =>
      degiroApi.initiateAuth(username, password),
  })
}

export function useCompleteDegiroAuth() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ processId, code }: { processId: string; code: string }) =>
      degiroApi.completeAuth(processId, code),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.degiro() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useSyncDegiro() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => degiroApi.sync(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.degiro() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
    // A sync that meets an expired session flips the stored status to
    // REAUTH_REQUIRED server-side. Without invalidating on failure too, the
    // cached status stays "active" until it goes stale and the UI keeps
    // offering a Sync button that can only fail again.
    onError: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.degiro() })
    },
  })
}

export function useClearDegiroSession() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => degiroApi.clearSession(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.degiro() })
    },
  })
}

// ---------------------------------------------------------------------------
// Bourse Direct
// ---------------------------------------------------------------------------

export function useBourseDirectStatus() {
  return useSidecarSessionStatus(syncKeys.bourseDirect(), bourseDirectApi.getStatus)
}

export function useInitiateBourseDirectAuth() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ login, password }: { login: string; password: string }) =>
      bourseDirectApi.initiateAuth(login, password),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.bourseDirect() })
    },
  })
}

export function useCompleteBourseDirectAuth() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ processId, code }: { processId: string; code?: string }) =>
      bourseDirectApi.completeAuth(processId, code),
    onSuccess: status => {
      queryClient.setQueryData(syncKeys.bourseDirect(), status)
      queryClient.invalidateQueries({ queryKey: syncKeys.bourseDirect() })
    },
  })
}

export function useSyncBourseDirect() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: bourseDirectApi.sync,
    onSuccess: status => {
      queryClient.setQueryData(syncKeys.bourseDirect(), status)
      queryClient.invalidateQueries({ queryKey: syncKeys.bourseDirect() })
    },
  })
}

export function useClearBourseDirectSession() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: bourseDirectApi.clearSession,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.bourseDirect() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

// ---------------------------------------------------------------------------
// Amundi Épargne Salariale
// ---------------------------------------------------------------------------

export function useAmundiStatus() {
  return useSidecarSessionStatus(syncKeys.amundi(), amundiApi.getStatus)
}

export function useInitiateAmundiAuth() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ login, password }: { login: string; password: string }) =>
      amundiApi.initiateAuth(login, password),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.amundi() })
    },
  })
}

export function useCompleteAmundiAuth() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ processId, code }: { processId: string; code?: string }) =>
      amundiApi.completeAuth(processId, code),
    onSuccess: status => {
      queryClient.setQueryData(syncKeys.amundi(), status)
      queryClient.invalidateQueries({ queryKey: syncKeys.amundi() })
    },
  })
}

export function useSyncAmundi() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: amundiApi.sync,
    onSuccess: status => {
      queryClient.setQueryData(syncKeys.amundi(), status)
      queryClient.invalidateQueries({ queryKey: syncKeys.amundi() })
    },
  })
}

export function useClearAmundiSession() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: amundiApi.clearSession,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.amundi() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

// ---------------------------------------------------------------------------
// Interactive Brokers
// ---------------------------------------------------------------------------

/**
 * Shared with the "Sync all" modal, which is why this lives here rather than inline in
 * IbkrTab: two components polling IBKR under different query keys would show two different
 * connection states in the same session.
 */
export function useIbkrStatus() {
  return useQuery({
    queryKey: syncKeys.ibkr(),
    queryFn: ibkrApi.getStatus,
    staleTime: QUERY_STALE_TIMES.sync,
  })
}

export function useConnectIbkr() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ token, queryId }: { token: string; queryId: string }) =>
      ibkrApi.connect(token, queryId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: syncKeys.ibkr() }),
  })
}

export function useSyncIbkr() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ibkrApi.sync,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.ibkr() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useDisconnectIbkr() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ibkrApi.disconnect,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: syncKeys.ibkr() }),
  })
}

// ---------------------------------------------------------------------------
// Crypto Exchanges
// ---------------------------------------------------------------------------

export function useCryptoExchangeStatuses() {
  return useQuery({
    queryKey: syncKeys.exchanges(),
    queryFn: cryptoExchangeApi.getStatuses,
    staleTime: 30_000,
    refetchInterval: 60_000,
  })
}

export function useAddCryptoExchange() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ type, apiKey, apiSecret }: { type: ExchangeType; apiKey: string; apiSecret?: string }) =>
      cryptoExchangeApi.add(type, apiKey, apiSecret),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.exchanges() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useSyncCryptoExchange() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => cryptoExchangeApi.sync(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.exchanges() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useRemoveCryptoExchange() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => cryptoExchangeApi.remove(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.exchanges() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

// ---------------------------------------------------------------------------
// Crypto Wallets
// ---------------------------------------------------------------------------

export function useCryptoWallets() {
  return useQuery({
    queryKey: syncKeys.wallets(),
    queryFn: cryptoWalletApi.list,
    staleTime: 30_000,
    refetchInterval: 60_000,
  })
}

export function useAddCryptoWallet() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ chain, address, label }: { chain: ChainType; address: string; label?: string }) =>
      cryptoWalletApi.add(chain, address, label),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.wallets() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useSyncCryptoWallet() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => cryptoWalletApi.sync(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.wallets() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useRemoveCryptoWallet() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => cryptoWalletApi.remove(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.wallets() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

// ---------------------------------------------------------------------------
// Finary
// ---------------------------------------------------------------------------

export function useFinaryConnectionStatus() {
  return useQuery({
    queryKey: syncKeys.finary(),
    queryFn: finaryApi.getStatus,
    staleTime: 30_000,
    refetchInterval: 60_000,
  })
}

export function useFinaryLogin() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ email, password }: { email: string; password: string }) =>
      finaryApi.login(email, password),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.finary() })
    },
  })
}

export function useCheckFinaryTotp() {
  return useMutation({
    mutationFn: finaryApi.checkTotp,
  })
}

export function useFinaryDeleteSession() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => finaryApi.deleteSession(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.finary() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function usePreviewFinaryFile() {
  return useMutation({
    mutationFn: (file: File) => finaryApi.previewFile(file),
  })
}

export function usePreviewFinaryApi() {
  return useMutation({
    mutationFn: (totp?: string) => finaryApi.previewApi(totp),
  })
}

export function useImportFinary() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: FinaryImportRequest) => finaryApi.import(request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useExecuteFinaryApiSync() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ syncToken, mappings }: { syncToken: string; mappings: FinaryAccountMapping[] }) =>
      finaryApi.executeApiSync(syncToken, mappings),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useFinaryAutoSync() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => finaryApi.autoSync(),
    onSuccess: (data) => {
      if (data.status === 'OK') {
        queryClient.invalidateQueries({ queryKey: ['accounts'] })
        queryClient.invalidateQueries({ queryKey: ['dashboard'] })
        queryClient.invalidateQueries({ queryKey: syncKeys.finary() })
      }
    },
  })
}
