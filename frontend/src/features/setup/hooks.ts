import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
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
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: SetupAdminRequest) => setupApi.submitAdmin(body),
    onSuccess: () => qc.invalidateQueries({ queryKey: SETUP_STATUS_KEY }),
  })
}

export function useSubmitSecurity() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: SetupSecurityRequest) => setupApi.submitSecurity(body),
    onSuccess: () => qc.invalidateQueries({ queryKey: SETUP_STATUS_KEY }),
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
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => setupApi.testEnableBanking(),
    onSuccess: (result) => {
      if (result.ok) qc.invalidateQueries({ queryKey: SETUP_STATUS_KEY })
    },
  })
}

export function useBoursoBankHealth() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => setupApi.checkBoursoBankSidecar(),
    onSuccess: (result) => {
      if (result.ok) qc.invalidateQueries({ queryKey: SETUP_STATUS_KEY })
    },
  })
}

export function useGenerateCryptoKey() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => setupApi.generateCryptoKey(),
    onSuccess: () => qc.invalidateQueries({ queryKey: SETUP_STATUS_KEY }),
  })
}

export function useAcknowledgeIntegration() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (key: 'traderepublic' | 'revolut' | 'finary' | 'boursedirect' | 'fortuneo') => setupApi.acknowledgeIntegration(key),
    onSuccess: () => qc.invalidateQueries({ queryKey: SETUP_STATUS_KEY }),
  })
}

export function useCompleteSetup() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => setupApi.complete(),
    onSuccess: () => qc.invalidateQueries({ queryKey: SETUP_STATUS_KEY }),
  })
}
