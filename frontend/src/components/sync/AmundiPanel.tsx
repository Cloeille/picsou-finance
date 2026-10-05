import { PiggyBank } from "lucide-react"
import { SidecarSessionPanel } from "@/components/sync/SidecarSessionPanel"
import {
  useAmundiStatus,
  useClearAmundiSession,
  useCompleteAmundiAuth,
  useInitiateAmundiAuth,
  useSyncAmundi,
} from "@/features/sync/hooks"

interface AmundiPanelProps {
  onConnected?: () => void
}

export function AmundiPanel({ onConnected }: AmundiPanelProps = {}) {
  return (
    <SidecarSessionPanel
      translationPrefix="sync.amundi"
      fieldIdPrefix="amundi"
      loginIcon={PiggyBank}
      appPush
      useStatus={useAmundiStatus}
      useInitiateAuth={useInitiateAmundiAuth}
      useCompleteAuth={useCompleteAmundiAuth}
      useSync={useSyncAmundi}
      useClearSession={useClearAmundiSession}
      onConnected={onConnected}
    />
  )
}
