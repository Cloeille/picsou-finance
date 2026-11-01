import { Briefcase } from "lucide-react"
import { SidecarSessionPanel } from "@/components/sync/SidecarSessionPanel"

interface CmesPanelProps {
  onConnected?: () => void
}

/** Crédit Mutuel Épargne Salariale, the saver's space CIC savers use too. */
export function CmesPanel({ onConnected }: CmesPanelProps = {}) {
  return (
    <SidecarSessionPanel
      provider="cmes"
      translationPrefix="sync.cmes"
      fieldIdPrefix="cmes"
      loginIcon={Briefcase}
      appPush
      otpRetryable
      onConnected={onConnected}
    />
  )
}
