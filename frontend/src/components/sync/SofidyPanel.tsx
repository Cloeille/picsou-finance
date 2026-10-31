import { Landmark } from "lucide-react"
import { SidecarSessionPanel } from "@/components/sync/SidecarSessionPanel"
import { ScpiSyncSummary } from "@/components/scpi/ScpiSyncSummary"
import {
  useClearSofidySession,
  useCompleteSofidyAuth,
  useInitiateSofidyAuth,
  useSofidyStatus,
  useSyncSofidy,
} from "@/features/sync/hooks"

interface SofidyPanelProps {
  onConnected?: () => void
}

/**
 * Sofidy always asks for a six-digit code by e-mail, so unlike CORUM this panel
 * does reach the OTP screen — the shared component handles that on its own from
 * the auth response. The login field carries the six-digit associate code printed
 * on Sofidy's paper mail, not an email address.
 *
 * `appPush` is deliberately not set: there is no app approval to wait for, only
 * a code to type.
 */
export function SofidyPanel({ onConnected }: SofidyPanelProps = {}) {
  // Same query as the panel's own status: the shared cache makes this free.
  const status = useSofidyStatus()
  return (
    <SidecarSessionPanel
      translationPrefix="sync.sofidy"
      fieldIdPrefix="sofidy"
      loginIcon={Landmark}
      useStatus={useSofidyStatus}
      useInitiateAuth={useInitiateSofidyAuth}
      useCompleteAuth={useCompleteSofidyAuth}
      useSync={useSyncSofidy}
      useClearSession={useClearSofidySession}
      onConnected={onConnected}
      successSummary={<ScpiSyncSummary provider="SOFIDY" completedAt={status.data?.lastSyncCompletedAt} />}
    />
  )
}
