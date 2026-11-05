import { useEffect, useRef, useState } from 'react'
import { Navigate, useLocation, useSearchParams } from 'react-router'
import { useAuthStore } from '@/stores/auth-store'
import { useAppStore } from '@/stores/app-store'
import { RouteSpinner } from '@/components/shared/RouteFallback'
import { getErrorCode } from '@/lib/errors'
import { markSsoRevalidate } from '@/lib/sso-revalidate'
import { isOAuthAuthorizeRedirect, safeRedirect } from '@/lib/utils'
import { authApi } from './api'
import { useSessionProbe } from './hooks'

export function RequireAuth({ children }: { children: React.ReactNode }) {
  const isAuthenticated = useAuthStore(s => s.isAuthenticated)
  const login = useAuthStore(s => s.login)
  const demoMode = useAppStore(s => s.demoMode)
  const location = useLocation()

  // Demo mode probes too: its adapter answers /auth/refresh with the demo user, which is how
  // pages reading the signed-in user (Settings, RequireAdmin) get one.
  const probe = useSessionProbe(!isAuthenticated)

  useEffect(() => {
    if (probe.isSuccess) login(probe.data)
  }, [probe.isSuccess, probe.data, login])

  if (demoMode || isAuthenticated) return <>{children}</>
  if (probe.isPending || probe.isSuccess) return <RouteSpinner />

  const requested = location.pathname + location.search + location.hash
  return <Navigate to={requested === '/' ? '/login' : `/login?redirect=${encodeURIComponent(requested)}`} replace />
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
  const [searchParams] = useSearchParams()

  // The Authorization Server bounces an authorize request without a usable session to
  // /login?redirect=/oauth2/…. Neither the sessionStorage flag nor the cached probe can tell
  // whether that session is still restorable, so ask the server once and resume the
  // authorization on success; redirecting to "/" instead would lose it.
  const oauthTarget = safeRedirect(searchParams.get('redirect'))
  const resumeOAuth = probe && !demoMode && isOAuthAuthorizeRedirect(oauthTarget)
  const [oauthProbeFailed, setOauthProbeFailed] = useState(false)
  const oauthProbeStarted = useRef(false)

  const shouldProbe = probe && !demoMode && !isAuthenticated && !resumeOAuth
  const sessionProbe = useSessionProbe(shouldProbe)

  useEffect(() => {
    if (sessionProbe.isSuccess) login(sessionProbe.data)
  }, [sessionProbe.isSuccess, sessionProbe.data, login])

  useEffect(() => {
    if (!resumeOAuth || oauthProbeStarted.current) return
    oauthProbeStarted.current = true
    authApi.refresh().then(
      (user) => {
        login(user)
        // replace(): Back must not return to this spent spinner.
        window.location.replace(oauthTarget)
      },
      (err: unknown) => {
        if (getErrorCode(err) === 'SSO_REVALIDATE') markSsoRevalidate()
        useAuthStore.getState().logout()
        setOauthProbeFailed(true)
      },
    )
  }, [resumeOAuth, oauthTarget, login])

  if (resumeOAuth) return oauthProbeFailed ? <>{children}</> : <RouteSpinner />
  if (demoMode || isAuthenticated) return <Navigate to="/" replace />
  // While the probe resolves (only when actually enabled), hold the form back so
  // a restorable session redirects into the app rather than flashing /login.
  if (shouldProbe && (sessionProbe.isPending || sessionProbe.isSuccess)) return <RouteSpinner />

  return <>{children}</>
}

export function RequireAdmin({ children }: { children: React.ReactNode }) {
  const user = useAuthStore(s => s.user)
  const demoMode = useAppStore(s => s.demoMode)
  const shouldProbe = demoMode && !user
  const probe = useSessionProbe(shouldProbe)

  if (shouldProbe && (probe.isPending || probe.isSuccess)) return <RouteSpinner />
  if (!user) return <Navigate to="/login" replace />
  if (user.role !== 'ADMIN') return <Navigate to="/error/403" replace />
  return <>{children}</>
}
