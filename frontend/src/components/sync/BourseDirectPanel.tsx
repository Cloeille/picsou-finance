import { Briefcase } from '@phosphor-icons/react'
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
      loginIcon={Briefcase}
      onConnected={onConnected}
    />
  )
}
