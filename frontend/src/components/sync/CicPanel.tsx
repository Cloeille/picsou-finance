import { Landmark } from "lucide-react"
import { SidecarSessionPanel } from "@/components/sync/SidecarSessionPanel"
import {
  useClearCicSession,
  useCicStatus,
  useCompleteCicAuth,
  useInitiateCicAuth,
  useSyncCic,
} from "@/features/sync/hooks"

interface CicPanelProps {
  onConnected?: () => void
}

/** CIC online banking: the livrets and the PEL the PSD2 connection does not expose. */
export function CicPanel({ onConnected }: CicPanelProps = {}) {
  return (
    <SidecarSessionPanel
      translationPrefix="sync.cic"
      fieldIdPrefix="cic"
      loginIcon={Landmark}
      appPush
      useStatus={useCicStatus}
      useInitiateAuth={useInitiateCicAuth}
      useCompleteAuth={useCompleteCicAuth}
      useSync={useSyncCic}
      useClearSession={useClearCicSession}
      onConnected={onConnected}
    />
  )
}
