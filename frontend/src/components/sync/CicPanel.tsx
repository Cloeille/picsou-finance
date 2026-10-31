import { Landmark } from "lucide-react"
import { SidecarSessionPanel } from "@/components/sync/SidecarSessionPanel"
import {
  useClearCicSession,
  useCicStatus,
  useCompleteCicAuth,
  useInitiateCicAuth,
  useReconnectCic,
  useSyncCic,
} from "@/features/sync/hooks"

interface CicPanelProps {
  onConnected?: () => void
  /** Start the stored-credentials reconnect on arrival (the Sync-all row sent the user here for it). */
  autoReconnect?: boolean
  onAutoReconnectStarted?: () => void
}

/** CIC online banking: the livrets and the PEL the PSD2 connection does not expose. */
export function CicPanel({ onConnected, autoReconnect, onAutoReconnectStarted }: CicPanelProps = {}) {
  return (
    <SidecarSessionPanel
      translationPrefix="sync.cic"
      fieldIdPrefix="cic"
      loginIcon={Landmark}
      appPush
      otpRetryable
      useStatus={useCicStatus}
      useInitiateAuth={useInitiateCicAuth}
      useReconnect={useReconnectCic}
      autoReconnect={autoReconnect}
      onAutoReconnectStarted={onAutoReconnectStarted}
      useCompleteAuth={useCompleteCicAuth}
      useSync={useSyncCic}
      useClearSession={useClearCicSession}
      onConnected={onConnected}
    />
  )
}
