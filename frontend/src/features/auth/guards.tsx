import { useEffect } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth-store'
import { useAppStore } from '@/stores/app-store'
import { LoadingSkeleton } from '@/components/shared/LoadingSkeleton'
import { useSessionProbe } from './hooks'

export function RequireAuth({ children }: { children: React.ReactNode }) {
  const isAuthenticated = useAuthStore(s => s.isAuthenticated)
  const login = useAuthStore(s => s.login)
  const demoMode = useAppStore(s => s.demoMode)

  const probe = useSessionProbe(!demoMode && !isAuthenticated)

  useEffect(() => {
    if (probe.isSuccess) login(probe.data)
  }, [probe.isSuccess, probe.data, login])

  if (demoMode || isAuthenticated) return <>{children}</>
  if (probe.isPending || probe.isSuccess) return <LoadingSkeleton />

  return <Navigate to="/login" replace />
}

export function PublicOnly({
  children,
  probe = true,
}: {
  children: React.ReactNode
  /**
   * Probe the cookie-backed session before showing a public-only page. On by
   * default so opening `/login` after a tab/browser restart rehydrates a
   * restorable "Remember Me" session and redirects into the app, instead of
   * showing the login form. Set `false` on the mid-login MFA challenge page,
   * where there is no session to restore yet. Shares the same query key as
   * `RequireAuth`, so a single probe is reused across guards.
   */
  probe?: boolean
}) {
  const isAuthenticated = useAuthStore(s => s.isAuthenticated)
  const login = useAuthStore(s => s.login)
  const demoMode = useAppStore(s => s.demoMode)

  const shouldProbe = probe && !demoMode && !isAuthenticated
  const sessionProbe = useSessionProbe(shouldProbe)

  useEffect(() => {
    if (sessionProbe.isSuccess) login(sessionProbe.data)
  }, [sessionProbe.isSuccess, sessionProbe.data, login])

  if (demoMode || isAuthenticated) return <Navigate to="/" replace />
  // While the probe resolves (only when actually enabled), hold the form back so
  // a restorable session redirects into the app rather than flashing /login.
  if (shouldProbe && (sessionProbe.isPending || sessionProbe.isSuccess)) return <LoadingSkeleton />

  return <>{children}</>
}

export function RequireAdmin({ children }: { children: React.ReactNode }) {
  const user = useAuthStore(s => s.user)
  if (!user) return <Navigate to="/login" replace />
  if (user.role !== 'ADMIN') return <Navigate to="/error/403" replace />
  return <>{children}</>
}
