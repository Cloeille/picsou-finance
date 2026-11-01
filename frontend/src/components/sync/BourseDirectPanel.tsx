import { BriefcaseBusiness } from "lucide-react"
import { SidecarSessionPanel } from "@/components/sync/SidecarSessionPanel"

interface BourseDirectPanelProps {
  onConnected?: () => void
}

export function BourseDirectPanel({ onConnected }: BourseDirectPanelProps = {}) {
  return (
    <SidecarSessionPanel
      provider="bourseDirect"
      translationPrefix="sync.bourseDirect"
      fieldIdPrefix="bourse-direct"
      loginIcon={BriefcaseBusiness}
      onConnected={onConnected}
    />
  )
}
