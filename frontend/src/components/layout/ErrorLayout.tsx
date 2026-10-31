import { Outlet } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth-store'
import { useAppStore } from '@/stores/app-store'
import { AppLayout } from './AppLayout'

/** Error pages keep the app shell for a signed-in user and stand alone for a visitor. */
export function ErrorLayout() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const demoMode = useAppStore((s) => s.demoMode)
  if (isAuthenticated || demoMode) return <AppLayout />
  return (
    <div className="flex min-h-dvh flex-col bg-background">
      <Outlet />
    </div>
  )
}
