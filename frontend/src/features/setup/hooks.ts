import { useQuery, useMutation } from '@tanstack/react-query'
import { useInvalidatingMutation } from '@/hooks/use-invalidating-mutation'
import { setupApi } from './api'
import type {
  EnableBankingConfigRequest,
  EnableBankingImportRequest,
  SetupAdminRequest,
  SetupSecurityRequest,
} from './api'

export const SETUP_STATUS_KEY = ['setup', 'status'] as const

/**
 * Polled on every page-load of a setup route. Short stale time so a second
 * browser tab that just hit `POST /complete` is reflected in the first tab
 * before the user can re-submit.
 */
export function useSetupStatus(enabled = true) {
  return useQuery({
    queryKey: SETUP_STATUS_KEY,
    queryFn: setupApi.getStatus,
    enabled,
    staleTime: 5_000,
    retry: (failureCount, error) => {
      // Don't retry on 410 Gone (setup already complete) — the UI should
      // redirect immediately, not wait for 3 more 410 round-trips.
      const status = (error as { response?: { status?: number } })?.response?.status
      if (status === 410) return false
      return failureCount < 2
    },
  })
}

export function useSubmitAdmin() {
  return useInvalidatingMutation({
    mutationFn: (body: SetupAdminRequest) => setupApi.submitAdmin(body),
    invalidates: [SETUP_STATUS_KEY],
    awaitRefresh: true,
  })
}

export function useSubmitSecurity() {
  return useInvalidatingMutation({
    mutationFn: (body: SetupSecurityRequest) => setupApi.submitSecurity(body),
    invalidates: [SETUP_STATUS_KEY],
    awaitRefresh: true,
  })
}

export function useWriteEnableBankingConfig() {
  return useMutation({
    mutationFn: (body: EnableBankingConfigRequest) => setupApi.writeEnableBankingConfig(body),
  })
}

export function useGenerateEnableBankingKeyPair() {
  return useMutation({ mutationFn: () => setupApi.generateEnableBankingKeyPair() })
}

export function useImportEnableBankingPrivateKey() {
  return useMutation({
    mutationFn: (body: EnableBankingImportRequest) => setupApi.importEnableBankingPrivateKey(body),
  })
}

export function useTestEnableBanking() {
  return useInvalidatingMutation({
    mutationFn: () => setupApi.testEnableBanking(),
    invalidates: result => (result.ok ? [SETUP_STATUS_KEY] : []),
  })
}

export function useBoursoBankHealth() {
  return useInvalidatingMutation({
    mutationFn: () => setupApi.checkBoursoBankSidecar(),
    invalidates: result => (result.ok ? [SETUP_STATUS_KEY] : []),
  })
}

export function useGenerateCryptoKey() {
  return useInvalidatingMutation({
    mutationFn: () => setupApi.generateCryptoKey(),
    invalidates: [SETUP_STATUS_KEY],
    awaitRefresh: true,
  })
}

export function useAcknowledgeIntegration() {
  return useInvalidatingMutation({
    mutationFn: (key: 'traderepublic' | 'revolut' | 'finary' | 'boursedirect' | 'fortuneo') => setupApi.acknowledgeIntegration(key),
    invalidates: [SETUP_STATUS_KEY],
    awaitRefresh: true,
  })
}

export function useCompleteSetup() {
  return useInvalidatingMutation({
    mutationFn: () => setupApi.complete(),
    invalidates: [SETUP_STATUS_KEY],
    awaitRefresh: true,
  })
}
