import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { authApi } from './api'
import { useAuthStore } from '@/stores/auth-store'
import { resetClientState } from '@/lib/reset-client-state'
import { SESSION_PROBE_GC_TIME } from '@/lib/constants'

export const authKeys = {
  sessionProbe: ['session-probe'] as const,
}

// The interactive login path lives in features/mfa/hooks.ts
// (`useLoginWithRememberMe` / `useVerifyMfa`) because login is MFA-aware. This
// file only owns logout; both crossings funnel through `resetClientState` so no
// per-user cache or impersonation target survives the auth boundary.
export function useLogout() {
  const logout = useAuthStore(s => s.logout)
  const queryClient = useQueryClient()
  const { t } = useTranslation()
  return useMutation({
    mutationFn: () => authApi.logout(),
    // onSuccess only (not onSettled): if the server call fails, the session cookies
    // are still valid server-side, so the client must NOT pretend to be logged out --
    // RequireAuth's session-probe would otherwise silently resurrect it moments later.
    onSuccess: () => {
      logout()
      resetClientState(queryClient)
    },
    // The click leaves local state intact on failure (by design, above), so surface
    // the error -- otherwise the button just silently re-enables and the user can't
    // tell logout didn't happen. Every call site (SettingsPage, AppSidebar) inherits
    // this feedback for free.
    onError: () => {
      toast.error(t('settings.logoutError'))
    },
  })
}

/**
 * Probes the cookie-backed session via `POST /auth/refresh`. `isAuthenticated` mirrors
 * sessionStorage, which is wiped on every tab/browser close -- unrelated to the HttpOnly
 * access/refresh/persistent_token cookies, which can keep the session alive for up to 90
 * days (Remember Me). Rather than trust the stale sessionStorage flag and bounce straight
 * to /login, RequireAuth probes the cookie-backed session once; a valid refresh_token or
 * persistent_token (re-minted by PersistentTokenAuthFilter) rehydrates the store instead
 * of forcing a fresh login.
 */
export function useSessionProbe(enabled: boolean) {
  return useQuery({
    queryKey: authKeys.sessionProbe,
    queryFn: () => authApi.refresh(),
    enabled,
    retry: false,
    staleTime: Infinity,
    gcTime: SESSION_PROBE_GC_TIME,
  })
}
