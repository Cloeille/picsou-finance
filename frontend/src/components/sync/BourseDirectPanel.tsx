import { BriefcaseBusiness } from "lucide-react"
import { SidecarSessionPanel } from "@/components/sync/SidecarSessionPanel"
import {
  useBourseDirectStatus,
  useClearBourseDirectSession,
  useCompleteBourseDirectAuth,
  useInitiateBourseDirectAuth,
  useSyncBourseDirect,
} from "@/features/sync/hooks"

interface BourseDirectPanelProps {
  onConnected?: () => void
}

export function BourseDirectPanel({ onConnected }: BourseDirectPanelProps = {}) {
  return (
    <SidecarSessionPanel
      translationPrefix="sync.bourseDirect"
      fieldIdPrefix="bourse-direct"
      loginIcon={BriefcaseBusiness}
      useStatus={useBourseDirectStatus}
      useInitiateAuth={useInitiateBourseDirectAuth}
      useCompleteAuth={useCompleteBourseDirectAuth}
      useSync={useSyncBourseDirect}
      useClearSession={useClearBourseDirectSession}
      onConnected={onConnected}
    />
  )
}
