import { Briefcase } from "lucide-react"
import { SidecarSessionPanel } from "@/components/sync/SidecarSessionPanel"
import {
  useClearCmesSession,
  useCmesStatus,
  useCompleteCmesAuth,
  useInitiateCmesAuth,
  useSyncCmes,
} from "@/features/sync/hooks"

interface CmesPanelProps {
  onConnected?: () => void
}

/** Crédit Mutuel Épargne Salariale, the saver's space CIC savers use too. */
export function CmesPanel({ onConnected }: CmesPanelProps = {}) {
  return (
    <SidecarSessionPanel
      translationPrefix="sync.cmes"
      fieldIdPrefix="cmes"
      loginIcon={Briefcase}
      appPush
      useStatus={useCmesStatus}
      useInitiateAuth={useInitiateCmesAuth}
      useCompleteAuth={useCompleteCmesAuth}
      useSync={useSyncCmes}
      useClearSession={useClearCmesSession}
      onConnected={onConnected}
    />
  )
}
