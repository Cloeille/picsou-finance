import { Navigate } from 'react-router-dom'
import { useSetupStatus } from './hooks'
import { LoadingSkeleton } from '@/components/shared/LoadingSkeleton'
import { useAppStore } from '@/stores/app-store'

/**
 * Wraps every authenticated route. Before Picsou is fully configured, it
 * redirects to {@code /setup} so the admin can finish the wizard. In demo
 * mode we skip the check entirely — the demo bundle has its own fake state.
 *
 * We intentionally gate on the status query, not on a synchronous flag:
 * a second browser tab that just completed setup elsewhere should unlock
 * this tab on the next status refetch without a full page reload.
 */
export function RequireSetup({ children }: { children: React.ReactNode }) {
  const demoMode = useAppStore(s => s.demoMode)
  const { data, isLoading, error } = useSetupStatus(!demoMode)

  if (demoMode) return <>{children}</>
  if (isLoading) return <LoadingSkeleton />

  // If the status endpoint itself fails (e.g. backend down), let the error
  // boundaries / connectivity banner handle it — don't loop to /setup.
  if (error && !data) return <>{children}</>

  if (data?.needsSetup) return <Navigate to="/setup" replace />

  return <>{children}</>
}

/**
 * Inverse guard. While setup is in progress, keep the user on the wizard
 * even if they bookmarked a deep route like {@code /setup/integrations}.
 * Once complete, bounce them to the dashboard — the wizard's post-complete
 * endpoints return 410 Gone and rendering the old cards would just throw.
 */
export function SetupOnly({ children }: { children: React.ReactNode }) {
  const demoMode = useAppStore(s => s.demoMode)
  const { data, isLoading } = useSetupStatus(!demoMode)

  if (demoMode) return <Navigate to="/" replace />
  if (isLoading) return <LoadingSkeleton />
  if (data && !data.needsSetup) return <Navigate to="/" replace />

  return <>{children}</>
}
