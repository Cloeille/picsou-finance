import { useEffect } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query'
import { QUERY_STALE_TIMES } from '@/lib/constants'
import { trackFeature } from '@/lib/telemetry'
import { useInvalidatingMutation } from '@/hooks/use-invalidating-mutation'
import {
  bankSyncApi,
  trApi,
  cryptoExchangeApi,
  cryptoWalletApi,
  finaryApi,
  amexApi,
  revolutApi,
  degiroApi,
  ibkrApi,
  simplefinApi,
} from './api'
import { ACCOUNT_VIEWS, syncKeys } from './keys'
import { sidecarProviders } from './providers'
import type {
  PanelProviderId,
  ProviderCompleteResult,
  ProviderCompleteVars,
  ProviderIdWith,
  ProviderInitiateResult,
  ProviderInitiateVars,
  ProviderStatus,
  SidecarProviderEntry,
  SidecarProviderId,
} from './providers'
import type {
  ExchangeType,
  ChainType,
  FinaryAccountMapping,
  FinaryImportRequest,
  SidecarAuthInitResponse,
} from '@/types/api'

export { syncKeys }

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
  return useInvalidatingMutation({
    mutationFn: ({ institutionId, institutionName }: { institutionId: string; institutionName: string }) =>
      bankSyncApi.initiate(institutionId, institutionName),
    onSuccess: () => {
      trackFeature('sync_triggered')
    },
    invalidates: [syncKeys.banks(), ...ACCOUNT_VIEWS],
  })
}

export function useCompleteBankSync() {
  return useInvalidatingMutation({
    // `state` is the OAuth nonce echoed on the redirect — dropping it would
    // route the backend into the legacy latest-CREATED guess.
    mutationFn: ({ code, state }: { code: string; state?: string | null }) => bankSyncApi.complete(code, state),
    invalidates: [syncKeys.banks(), ...ACCOUNT_VIEWS],
  })
}

export function useRetryBankSync() {
  return useInvalidatingMutation({
    mutationFn: (id: number) => bankSyncApi.retry(id),
    invalidates: [syncKeys.banks(), ...ACCOUNT_VIEWS],
  })
}

/**
 * Re-initiates the OAuth flow for a dead requisition. Navigating to the
 * returned authLink is the caller's concern (same as the initiate flow).
 */
export function useReconnectBankSync() {
  return useInvalidatingMutation({
    mutationFn: (id: number) => bankSyncApi.reconnect(id),
    invalidates: [syncKeys.banks()],
  })
}

export function useDeleteBankConnection() {
  return useInvalidatingMutation({
    mutationFn: (id: number) => bankSyncApi.deleteConnection(id),
    invalidates: [syncKeys.banks(), ...ACCOUNT_VIEWS],
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
  return useInvalidatingMutation({
    mutationFn: ({ processId, tan }: { processId: string; tan: string }) => trApi.completeAuth(processId, tan),
    invalidates: [syncKeys.tr(), ...ACCOUNT_VIEWS],
  })
}

export function useSyncTradeRepublic() {
  return useInvalidatingMutation({
    mutationFn: () => trApi.sync(),
    invalidates: [syncKeys.tr(), ...ACCOUNT_VIEWS],
  })
}

export function useImportTrCsv() {
  return useInvalidatingMutation({
    mutationFn: (file: File) => trApi.importCsv(file),
    invalidates: [syncKeys.tr(), ...ACCOUNT_VIEWS],
  })
}

export function useClearTrSession() {
  return useInvalidatingMutation({
    mutationFn: () => trApi.clearSession(),
    invalidates: [syncKeys.tr(), ...ACCOUNT_VIEWS],
  })
}

// ---------------------------------------------------------------------------
// Browser-sidecar providers (see `providers.ts`)
// ---------------------------------------------------------------------------

const entryOf = (id: SidecarProviderId): SidecarProviderEntry => sidecarProviders[id]

/**
 * Status poll shared by the sidecar providers: their backend only queues the
 * import, so an in-flight sync is polled every 1.5s (30s otherwise) and a
 * completed one refreshes the caches the imported accounts feed.
 */
export function useProviderStatus<I extends SidecarProviderId>(id: I): UseQueryResult<ProviderStatus<I>> {
  const { queryKey, getStatus, refreshOnSuccess } = entryOf(id)
  const queryClient = useQueryClient()
  const query = useQuery({
    queryKey,
    queryFn: getStatus,
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
    for (const key of refreshOnSuccess) queryClient.invalidateQueries({ queryKey: key })
  }, [completedAt, queryClient, refreshOnSuccess, succeeded])

  return query as UseQueryResult<ProviderStatus<I>>
}

/** Login request. The mutation settles as soon as the call answers, then the status refetches. */
export function useProviderInitiate<I extends ProviderIdWith<'initiate'>>(
  id: I,
): UseMutationResult<ProviderInitiateResult<I>, Error, ProviderInitiateVars<I>> {
  const { queryKey, initiate, settleMutations, noSecondFactor } = entryOf(id)
  const mutate = useInvalidatingMutation({
    mutationFn: (vars: unknown) => (initiate as (vars: unknown) => Promise<unknown>)(vars),
    ...(settleMutations
      ? { when: 'settled' as const, invalidates: [queryKey], awaitRefresh: true }
      : { writesTo: noSecondFactor ? queryKey : undefined, invalidates: [queryKey] }),
  })
  return mutate as UseMutationResult<ProviderInitiateResult<I>, Error, ProviderInitiateVars<I>>
}

/** Second factor: the answer is the session status, seeded into the cache. */
export function useProviderComplete<I extends SidecarProviderId>(
  id: I,
): UseMutationResult<ProviderCompleteResult<I>, Error, ProviderCompleteVars<I>> {
  const { queryKey, complete, settleMutations, singleAttemptComplete, noSecondFactor } = entryOf(id)
  const mutate = useInvalidatingMutation({
    mutationFn: (vars: unknown) => (complete as (vars: unknown) => Promise<unknown>)(vars),
    ...(singleAttemptComplete ? { retry: false } : {}),
    ...(settleMutations
      ? { when: 'settled' as const, invalidates: [queryKey], awaitRefresh: true }
      : noSecondFactor || singleAttemptComplete
        ? { invalidates: noSecondFactor ? [] : [queryKey] }
        : { writesTo: queryKey, invalidates: [queryKey] }),
  })
  return mutate as UseMutationResult<ProviderCompleteResult<I>, Error, ProviderCompleteVars<I>>
}

export function useProviderSync<I extends SidecarProviderId>(id: I): UseMutationResult<ProviderStatus<I>, Error, void> {
  const { queryKey, sync, settleMutations } = entryOf(id)
  const mutate = useInvalidatingMutation({
    mutationFn: () => sync(),
    ...(settleMutations
      ? { when: 'settled' as const, invalidates: [queryKey], awaitRefresh: true }
      : { writesTo: queryKey, invalidates: [queryKey] }),
  })
  return mutate as UseMutationResult<ProviderStatus<I>, Error, void>
}

export function useProviderClear(id: SidecarProviderId): UseMutationResult<unknown, Error, void> {
  const { queryKey, clear, refreshOnClear = ACCOUNT_VIEWS, settleMutations } = entryOf(id)
  return useInvalidatingMutation({
    mutationFn: () => clear(),
    ...(settleMutations
      ? { when: 'settled' as const, invalidates: [queryKey, ...refreshOnClear], awaitRefresh: true }
      : { invalidates: [queryKey, ...refreshOnClear] }),
  })
}

/**
 * Logs in again from the credentials stored at the last login, answering like
 * `useProviderInitiate`. A provider with no such login rejects: callers gate on
 * the status' `canReconnect`.
 */
export function useProviderReconnect(id: SidecarProviderId): UseMutationResult<SidecarAuthInitResponse, Error, void> {
  const { queryKey, reconnect } = entryOf(id)
  return useInvalidatingMutation({
    mutationFn: reconnect ?? (() => Promise.reject(new Error('This provider cannot reconnect from stored credentials'))),
    invalidates: [queryKey],
  })
}

/** Every session hook of one provider the shared panel renders, in a single call. */
export function useProviderSession<I extends PanelProviderId>(id: I) {
  return {
    status: useProviderStatus(id),
    initiate: useProviderInitiate(id),
    reconnect: useProviderReconnect(id),
    complete: useProviderComplete(id),
    sync: useProviderSync(id),
    clear: useProviderClear(id),
  }
}


// ---------------------------------------------------------------------------
// Revolut (on-demand phone+passcode sync)
// ---------------------------------------------------------------------------

export function useRevolutStatus() {
  return useQuery({
    queryKey: syncKeys.revolut(),
    queryFn: revolutApi.getSessionStatus,
    staleTime: 30_000,
    refetchInterval: 60_000,
  })
}

export function useSyncProgress(provider: 'revolut' | 'tr', enabled: boolean) {
  return useQuery({
    queryKey: ['sync', provider, 'progress'],
    queryFn: provider === 'revolut' ? revolutApi.getSyncProgress : trApi.getSyncProgress,
    enabled,
    refetchInterval: (q) => (q.state.data?.running ? 1500 : false),
  })
}

export function useStartRevolutSync() {
  return useInvalidatingMutation({
    mutationFn: (body: { phoneNumber?: string; passcode?: string }) => revolutApi.startSync(body),
    writesTo: ['sync', 'revolut', 'progress'],
  })
}

export function useConfirmRevolutSync() {
  return useInvalidatingMutation({
    mutationFn: ({
      selectedExternalIds,
      remember,
      voluntary,
    }: {
      selectedExternalIds: string[]
      remember: boolean
      voluntary: boolean
    }) => revolutApi.confirmSync(selectedExternalIds, remember, voluntary),
    invalidates: [syncKeys.revolut(), ...ACCOUNT_VIEWS],
  })
}

export function useForgetRevolut() {
  return useInvalidatingMutation({
    mutationFn: () => revolutApi.clearSession(),
    invalidates: [syncKeys.revolut(), ...ACCOUNT_VIEWS],
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
  return useInvalidatingMutation({
    mutationFn: ({ processId, code }: { processId: string; code: string }) =>
      degiroApi.completeAuth(processId, code),
    invalidates: [syncKeys.degiro(), ...ACCOUNT_VIEWS],
  })
}

export function useSyncDegiro() {
  const queryClient = useQueryClient()
  return useInvalidatingMutation({
    mutationFn: () => degiroApi.sync(),
    invalidates: [syncKeys.degiro(), ...ACCOUNT_VIEWS],
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
  return useInvalidatingMutation({
    mutationFn: () => degiroApi.clearSession(),
    invalidates: [syncKeys.degiro()],
  })
}

// ---------------------------------------------------------------------------
// American Express
// ---------------------------------------------------------------------------

export function useRecoverAmexHistory() {
  return useInvalidatingMutation({
    mutationFn: () => amexApi.recoverHistory(),
    when: 'settled',
    invalidates: [sidecarProviders.amex.queryKey, ...ACCOUNT_VIEWS, ['history']],
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
  return useInvalidatingMutation({
    mutationFn: ({ token, queryId }: { token: string; queryId: string }) => ibkrApi.connect(token, queryId),
    invalidates: [syncKeys.ibkr()],
    awaitRefresh: true,
  })
}

export function useSyncIbkr() {
  return useInvalidatingMutation({
    mutationFn: ibkrApi.sync,
    invalidates: [syncKeys.ibkr(), ...ACCOUNT_VIEWS],
  })
}

export function useDisconnectIbkr() {
  return useInvalidatingMutation({
    mutationFn: ibkrApi.disconnect,
    invalidates: [syncKeys.ibkr()],
    awaitRefresh: true,
  })
}

// ---------------------------------------------------------------------------
// SimpleFIN
// ---------------------------------------------------------------------------

export function useSimplefinStatus() {
  return useQuery({
    queryKey: syncKeys.simplefin(),
    queryFn: simplefinApi.getStatus,
    staleTime: QUERY_STALE_TIMES.sync,
  })
}

export function useConnectSimplefin() {
  return useInvalidatingMutation({
    mutationFn: (token: string) => simplefinApi.connect(token),
    invalidates: [syncKeys.simplefin()],
    awaitRefresh: true,
  })
}

export function useSyncSimplefin() {
  return useInvalidatingMutation({
    mutationFn: simplefinApi.sync,
    invalidates: [syncKeys.simplefin(), ...ACCOUNT_VIEWS],
  })
}

export function useDisconnectSimplefin() {
  return useInvalidatingMutation({
    mutationFn: simplefinApi.disconnect,
    invalidates: [syncKeys.simplefin(), ['accounts']],
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
  return useInvalidatingMutation({
    mutationFn: ({ type, apiKey, apiSecret }: { type: ExchangeType; apiKey: string; apiSecret?: string }) =>
      cryptoExchangeApi.add(type, apiKey, apiSecret),
    invalidates: [syncKeys.exchanges(), ...ACCOUNT_VIEWS],
  })
}

export function useSyncCryptoExchange() {
  return useInvalidatingMutation({
    mutationFn: (id: number) => cryptoExchangeApi.sync(id),
    invalidates: [syncKeys.exchanges(), ...ACCOUNT_VIEWS],
  })
}

export function useRemoveCryptoExchange() {
  return useInvalidatingMutation({
    mutationFn: (id: number) => cryptoExchangeApi.remove(id),
    invalidates: [syncKeys.exchanges(), ...ACCOUNT_VIEWS],
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
  return useInvalidatingMutation({
    mutationFn: ({ chain, address, label }: { chain: ChainType; address: string; label?: string }) =>
      cryptoWalletApi.add(chain, address, label),
    invalidates: [syncKeys.wallets(), ...ACCOUNT_VIEWS],
  })
}

export function useSyncCryptoWallet() {
  return useInvalidatingMutation({
    mutationFn: (id: number) => cryptoWalletApi.sync(id),
    invalidates: [syncKeys.wallets(), ...ACCOUNT_VIEWS],
  })
}

export function useRemoveCryptoWallet() {
  return useInvalidatingMutation({
    mutationFn: (id: number) => cryptoWalletApi.remove(id),
    invalidates: [syncKeys.wallets(), ...ACCOUNT_VIEWS],
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
  return useInvalidatingMutation({
    mutationFn: ({ email, password }: { email: string; password: string }) => finaryApi.login(email, password),
    invalidates: [syncKeys.finary()],
  })
}

export function useCheckFinaryTotp() {
  return useMutation({
    mutationFn: finaryApi.checkTotp,
  })
}

export function useFinaryDeleteSession() {
  return useInvalidatingMutation({
    mutationFn: () => finaryApi.deleteSession(),
    invalidates: [syncKeys.finary(), ...ACCOUNT_VIEWS],
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
  return useInvalidatingMutation({
    mutationFn: (request: FinaryImportRequest) => finaryApi.import(request),
    invalidates: ACCOUNT_VIEWS,
  })
}

export function useExecuteFinaryApiSync() {
  return useInvalidatingMutation({
    mutationFn: ({ syncToken, mappings }: { syncToken: string; mappings: FinaryAccountMapping[] }) =>
      finaryApi.executeApiSync(syncToken, mappings),
    invalidates: ACCOUNT_VIEWS,
  })
}

export function useFinaryAutoSync() {
  return useInvalidatingMutation({
    mutationFn: () => finaryApi.autoSync(),
    invalidates: data => (data.status === 'OK' ? [...ACCOUNT_VIEWS, syncKeys.finary()] : []),
  })
}
