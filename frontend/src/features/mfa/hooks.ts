import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { mfaApi } from './api'
import { useAuthStore } from '@/stores/auth-store'
import { resetClientState } from '@/lib/reset-client-state'

const MFA_KEYS = {
  status: ['mfa', 'status'] as const,
  sessions: ['mfa', 'sessions'] as const,
}

// ─── login + challenge ───────────────────────────────────────────────

/**
 * Submits credentials. On a successful non-MFA login this populates the auth
 * store; on `mfaRequired` it does NOT — the caller is expected to redirect to
 * /login/mfa and call `useVerifyMfa` to finish.
 */
export function useLoginWithRememberMe() {
  const login = useAuthStore(s => s.login)
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      username,
      password,
      rememberMe,
    }: {
      username: string
      password: string
      rememberMe: boolean
    }) => mfaApi.loginWithRememberMe(username, password, rememberMe),
    onSuccess: data => {
      if (!data.mfaRequired) {
        // A real session now exists. Wipe any previous user's cached data and
        // impersonation target BEFORE writing the new identity, so a shared
        // family browser never shows the prior login's figures.
        resetClientState(queryClient)
        login({
          username: data.username,
          role: data.role,
          memberId: data.memberId,
          displayName: data.displayName,
        })
      }
    },
  })
}

export function useVerifyMfa() {
  const login = useAuthStore(s => s.login)
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      code,
      isRecoveryCode,
      trustDevice,
    }: {
      code: string
      isRecoveryCode: boolean
      trustDevice: boolean
    }) => mfaApi.verifyMfa(code, isRecoveryCode, trustDevice),
    onSuccess: data => {
      // After /verify the server always returns the full user payload — there
      // is no second factor beyond this one, so mfaRequired:true here would be
      // a server bug. Discriminate to keep the type narrowing honest.
      if (!data.mfaRequired) {
        // MFA cleared → session established. Same boundary reset as the non-MFA
        // login branch: drop the previous user's cache and impersonation target
        // before writing this identity.
        resetClientState(queryClient)
        login({
          username: data.username,
          role: data.role,
          memberId: data.memberId,
          displayName: data.displayName,
        })
      }
    },
  })
}

// ─── status / enroll / disable / regenerate ──────────────────────────

export function useMfaStatus() {
  return useQuery({
    queryKey: MFA_KEYS.status,
    queryFn: () => mfaApi.getStatus(),
  })
}

export function useMfaEnrollInit() {
  return useMutation({
    mutationFn: ({ currentPassword }: { currentPassword: string }) =>
      mfaApi.enrollInit(currentPassword),
  })
}

export function useMfaEnrollVerify() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ code }: { code: string }) => mfaApi.enrollVerify(code),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: MFA_KEYS.status })
      // Enabling 2FA wipes existing persistent sessions server-side.
      qc.invalidateQueries({ queryKey: MFA_KEYS.sessions })
    },
  })
}

export function useMfaDisable() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({
      currentPassword,
      code,
      isRecoveryCode,
    }: {
      currentPassword: string
      code: string
      isRecoveryCode: boolean
    }) => mfaApi.disable(currentPassword, code, isRecoveryCode),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: MFA_KEYS.status })
      qc.invalidateQueries({ queryKey: MFA_KEYS.sessions })
    },
  })
}

export function useRegenerateRecoveryCodes() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ currentPassword, code }: { currentPassword: string; code: string }) =>
      mfaApi.regenerateRecoveryCodes(currentPassword, code),
    onSuccess: () => qc.invalidateQueries({ queryKey: MFA_KEYS.status }),
  })
}

// ─── persistent sessions ─────────────────────────────────────────────

export function useSessions() {
  return useQuery({
    queryKey: MFA_KEYS.sessions,
    queryFn: () => mfaApi.listSessions(),
  })
}

export function useRevokeSession() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => mfaApi.revokeSession(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: MFA_KEYS.sessions }),
  })
}

export function useRevokeAllSessionsExceptCurrent() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => mfaApi.revokeAllSessionsExceptCurrent(),
    onSuccess: () => qc.invalidateQueries({ queryKey: MFA_KEYS.sessions }),
  })
}

// ─── admin force-disable ─────────────────────────────────────────────

export function useAdminForceDisableMfa() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (memberId: number) => mfaApi.forceDisableMfa(memberId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['family', 'members'] }),
  })
}
