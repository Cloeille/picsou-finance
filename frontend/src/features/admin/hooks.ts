import { useQuery, useMutation } from '@tanstack/react-query'
import { useInvalidatingMutation } from '@/hooks/use-invalidating-mutation'
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
  return useInvalidatingMutation({
    mutationFn: (body: AdminSecuritySettings) => adminApi.updateSecurity(body),
    invalidates: [adminKeys.settings()],
    awaitRefresh: true,
  })
}

export function useUpdateEnableBanking() {
  return useInvalidatingMutation({
    mutationFn: (body: AdminEnableBankingCredentials) => adminApi.updateEnableBanking(body),
    invalidates: [adminKeys.settings()],
    awaitRefresh: true,
  })
}

export function useGenerateEnableBankingKeyPair() {
  return useInvalidatingMutation({
    mutationFn: () => adminApi.generateEnableBankingKeyPair(),
    invalidates: [adminKeys.settings()],
    awaitRefresh: true,
  })
}

export function useImportEnableBankingPrivateKey() {
  return useInvalidatingMutation({
    mutationFn: (privatePem: string) => adminApi.importEnableBankingPrivateKey(privatePem),
    invalidates: [adminKeys.settings()],
    awaitRefresh: true,
  })
}

export function useReloadCorsFromEnv() {
  return useInvalidatingMutation({
    mutationFn: () => adminApi.reloadCorsFromEnv(),
    invalidates: [adminKeys.settings()],
    awaitRefresh: true,
  })
}

export function useToggleIntegration() {
  return useInvalidatingMutation({
    mutationFn: ({ key, enabled }: { key: string; enabled: boolean }) =>
      adminApi.toggleIntegration(key, enabled),
    invalidates: [adminKeys.settings()],
    awaitRefresh: true,
  })
}

export function useUpdateAi() {
  return useInvalidatingMutation({
    mutationFn: (body: AdminAiRequest) => adminApi.updateAi(body),
    invalidates: [adminKeys.settings()],
    awaitRefresh: true,
  })
}

/** Consent is instance-wide: refresh both the admin view and the config the SDK reads. */
export function useUpdateTelemetry() {
  return useInvalidatingMutation({
    mutationFn: (enabled: boolean) => adminApi.updateTelemetry(enabled),
    invalidates: [adminKeys.settings(), telemetryKeys.config()],
    awaitRefresh: true,
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

export function useInstanceStatus(enabled = true) {
  return useQuery({
    queryKey: [...adminKeys.all, 'instance-status'],
    queryFn: adminApi.getInstanceStatus,
    enabled,
    staleTime: 5 * 60 * 1000,
  })
}

export function useClearEbCallLog() {
  return useInvalidatingMutation({
    mutationFn: adminApi.clearEbCallLog,
    invalidates: [[...adminKeys.all, 'eb-call-log']],
    awaitRefresh: true,
  })
}
