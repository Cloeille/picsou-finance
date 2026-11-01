import { useEffect } from 'react'
import { Outlet } from 'react-router'
import { useAuthStore } from '@/stores/auth-store'
import { useAppStore } from '@/stores/app-store'
import { useSessionProbe } from '@/features/auth/hooks'
import { RouteSpinner } from '@/components/shared/RouteFallback'
import { AppLayout } from './AppLayout'

/**
 * Error pages keep the app shell for a signed-in user and stand alone for a visitor.
 * They sit outside RequireAuth, so they probe the cookie session themselves: a
 * Remember Me user opening an error URL after a browser restart keeps the shell.
 */
export function ErrorLayout() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const login = useAuthStore((s) => s.login)
  const demoMode = useAppStore((s) => s.demoMode)

  const probe = useSessionProbe(!demoMode && !isAuthenticated)

  useEffect(() => {
    if (probe.isSuccess) login(probe.data)
  }, [probe.isSuccess, probe.data, login])

  if (isAuthenticated || demoMode) return <AppLayout />
  if (probe.isPending || probe.isSuccess) return <RouteSpinner />
  return (
    <div className="flex min-h-dvh flex-col bg-background">
      <Outlet />
    </div>
  )
}
