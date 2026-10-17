import { Building2 } from "lucide-react"
import { SidecarSessionPanel } from "@/components/sync/SidecarSessionPanel"
import { ScpiSyncSummary } from "@/components/scpi/ScpiSyncSummary"
import {
  useAuthenticateCorum,
  useClearCorumSession,
  useCompleteCorumAuth,
  useCorumStatus,
  useSyncCorum,
} from "@/features/sync/hooks"

interface CorumPanelProps {
  onConnected?: () => void
}

/**
 * CORUM needs no second factor, so unlike Amundi this panel never renders the
 * OTP or app-push state. The shared component handles that on its own: it only
 * reaches those screens when the auth response says a second factor is required.
 */
export function CorumPanel({ onConnected }: CorumPanelProps = {}) {
  // Same query as the panel's own status: the shared cache makes this free.
  const status = useCorumStatus()
  return (
    <SidecarSessionPanel
      translationPrefix="sync.corum"
      fieldIdPrefix="corum"
      loginIcon={Building2}
      useStatus={useCorumStatus}
      useInitiateAuth={useAuthenticateCorum}
      useCompleteAuth={useCompleteCorumAuth}
      useSync={useSyncCorum}
      useClearSession={useClearCorumSession}
      onConnected={onConnected}
      successSummary={<ScpiSyncSummary provider="CORUM" completedAt={status.data?.lastSyncCompletedAt} />}
    />
  )
}
