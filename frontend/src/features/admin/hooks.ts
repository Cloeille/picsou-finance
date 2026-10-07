import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { adminApi } from './api'
import { telemetryKeys } from '@/features/telemetry/hooks'
import type { AdminSecuritySettings, AdminEnableBankingCredentials, AdminAiRequest } from './api'

export const adminKeys = {
  all: ['admin'] as const,
  settings: () => [...adminKeys.all, 'settings'] as const,
}

export function useAdminSettings(enabled = true) {
  return useQuery({
    queryKey: adminKeys.settings(),
    queryFn: adminApi.getSettings,
    enabled,
    staleTime: 60_000,
  })
}

export function useUpdateSecurity() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: AdminSecuritySettings) => adminApi.updateSecurity(body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminKeys.settings() }),
  })
}

export function useUpdateEnableBanking() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: AdminEnableBankingCredentials) => adminApi.updateEnableBanking(body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminKeys.settings() }),
  })
}

export function useGenerateEnableBankingKeyPair() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => adminApi.generateEnableBankingKeyPair(),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminKeys.settings() }),
  })
}

export function useImportEnableBankingPrivateKey() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (privatePem: string) => adminApi.importEnableBankingPrivateKey(privatePem),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminKeys.settings() }),
  })
}

export function useReloadCorsFromEnv() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => adminApi.reloadCorsFromEnv(),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminKeys.settings() }),
  })
}

export function useToggleIntegration() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ key, enabled }: { key: string; enabled: boolean }) =>
      adminApi.toggleIntegration(key, enabled),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminKeys.settings() }),
  })
}

export function useUpdateAi() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: AdminAiRequest) => adminApi.updateAi(body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminKeys.settings() }),
  })
}

/** Consent is instance-wide: refresh both the admin view and the config the SDK reads. */
export function useUpdateTelemetry() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (enabled: boolean) => adminApi.updateTelemetry(enabled),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: adminKeys.settings() }),
        queryClient.invalidateQueries({ queryKey: telemetryKeys.config() }),
      ]),
  })
}

export function useTestAi() {
  return useMutation({ mutationFn: (body: AdminAiRequest) => adminApi.testAi(body) })
}

export function useAiCalls(limit: number, offset: number) {
  return useQuery({
    queryKey: [...adminKeys.all, 'ai-calls', limit, offset],
    queryFn: () => adminApi.listAiCalls(limit, offset),
    staleTime: 10_000,
  })
}

export function useEbCallLog(enabled: boolean) {
  return useQuery({
    queryKey: [...adminKeys.all, 'eb-call-log'],
    queryFn: adminApi.getEbCallLog,
    enabled,
    staleTime: 0,
  })
}

export function useClearEbCallLog() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: adminApi.clearEbCallLog,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [...adminKeys.all, 'eb-call-log'] }),
  })
}
