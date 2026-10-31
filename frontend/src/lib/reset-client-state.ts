import type { QueryClient } from '@tanstack/react-query'
import { useAppStore } from '@/stores/app-store'
import { useProfileStore } from '@/stores/profile-store'

/**
 * Wipes all per-user client state so nothing leaks across the auth boundary on a
 * shared family browser:
 *
 *  - a telemetry consent closed unanswered, so the next admin is asked again,
 *  - the persisted impersonation target (`activeMemberId`, kept in localStorage
 *    by the profile store), and
 *  - the React Query cache, whose data query keys are NOT scoped by user
 *    (`['dashboard', range]`, `['accounts']`, `['history', …]`, …).
 *
 * Without this a freshly logged-in user briefly sees the previous user's
 * balance/history (staleTime is 60s), and a left-over admin impersonation target
 * re-scopes the next person's requests to someone else's data.
 *
 * Call it on EVERY auth-boundary crossing: logout, and the exact moment a login
 * establishes a real session (the non-MFA branch of the password step, and MFA
 * verification). It is deliberately NOT called on the `mfaRequired` branch — no
 * session exists yet there, and the server severs any lingering foreign session
 * cookies at that point instead (see {@code AuthCookieWriter#clearSessionCookies}).
 */
export function resetClientState(queryClient: QueryClient) {
  useProfileStore.getState().reset()
  useAppStore.setState({ telemetryConsentDismissed: false })
  queryClient.clear()
}
