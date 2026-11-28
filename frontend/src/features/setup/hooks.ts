import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useInvalidatingMutation } from '@/hooks/use-invalidating-mutation'
import { useAppStore } from '@/stores/app-store'
import { useAuthStore } from '@/stores/auth-store'
import { setupApi } from './api'
import { waitForArchiveImport, type ArchiveImportOutcome } from './archive-import'
import type {
  ArchiveImportAccepted,
  ArchiveImportStatus,
  DesktopQuickStartRequest,
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
    queryFn: async () => {
      const status = await setupApi.getStatus()
      useAppStore
        .getState()
        .setDesktopStatus(
          Boolean(status.desktopMode),
          Boolean(status.desktopLockEnabled),
          Boolean(status.legacyPostgresDetected),
        )
      return status
    },
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
    mutationFn: (key: 'bnp' | 'traderepublic' | 'revolut' | 'finary' | 'boursedirect' | 'fortuneo' | 'lbp') => setupApi.acknowledgeIntegration(key),
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

export function useDesktopQuickStart() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: DesktopQuickStartRequest) => setupApi.desktopQuickStart(body),
    onSuccess: async (user) => {
      useAuthStore.getState().login(user)
      useAppStore.getState().setDesktopStatus(true, false)
      await queryClient.invalidateQueries({ queryKey: SETUP_STATUS_KEY })
    },
  })
}

export type ArchiveImportPhase =
  | { name: 'idle' }
  | { name: 'uploading'; progress: number }
  | { name: 'restarting'; reachable: boolean }
  | { name: 'finished'; outcome: ArchiveImportOutcome }

/**
 * The archive import: `start` uploads with progress and resolves once the server accepted the
 * archive, then the phase follows the restart until the new server reports the outcome. An
 * upload the server refuses leaves the phase `idle` and rejects `start`, so the form stays open
 * for a retry. The setup status cache is left alone: refreshing it would unmount the wizard
 * (setup is complete once imported) before the outcome was shown.
 */
export function useArchiveImport() {
  const [phase, setPhaseState] = useState<ArchiveImportPhase>({ name: 'idle' })
  const phaseRef = useRef<ArchiveImportPhase>(phase)
  const abort = useRef<AbortController | null>(null)

  const setPhase = useCallback((next: ArchiveImportPhase) => {
    phaseRef.current = next
    setPhaseState(next)
  }, [])

  useEffect(() => () => abort.current?.abort(), [])

  const wait = useCallback(
    async (accepted?: ArchiveImportAccepted) => {
      abort.current?.abort()
      const controller = new AbortController()
      abort.current = controller
      setPhase({ name: 'restarting', reachable: true })
      const outcome = await waitForArchiveImport({
        getStatus: () => setupApi.getStatus(),
        accepted,
        signal: controller.signal,
        onReachable: reachable => {
          if (!controller.signal.aborted) setPhase({ name: 'restarting', reachable })
        },
      })
      if (!outcome || controller.signal.aborted) return
      setPhase({ name: 'finished', outcome })
    },
    [setPhase],
  )

  const start = useCallback(
    async (file: File, passphrase: string) => {
      abort.current?.abort()
      const controller = new AbortController()
      abort.current = controller
      setPhase({ name: 'uploading', progress: 0 })
      try {
        const accepted = await setupApi.importArchive(
          file,
          passphrase,
          progress => {
            if (!controller.signal.aborted) setPhase({ name: 'uploading', progress })
          },
          controller.signal,
        )
        // Not awaited: the caller is done with its form once the server accepted the archive.
        void wait(accepted)
      } catch (err) {
        if (!controller.signal.aborted) setPhase({ name: 'idle' })
        throw err
      }
    },
    [wait, setPhase],
  )

  /**
   * Picks up an import this page did not start: the server reports it on a reload during the
   * restart (`pending`) or after a start that refused it (`failed`). Does nothing once the
   * dialog is already following one.
   */
  const adopt = useCallback(
    (reported: ArchiveImportStatus) => {
      if (phaseRef.current.name !== 'idle') return
      if (reported.state === 'pending') void wait()
      else if (reported.state === 'failed') {
        setPhase({ name: 'finished', outcome: { state: 'failed', code: reported.code } })
      }
    },
    [wait, setPhase],
  )

  const reset = useCallback(() => {
    abort.current?.abort()
    setPhase({ name: 'idle' })
  }, [setPhase])

  return { phase, start, resume: wait, adopt, reset }
}
