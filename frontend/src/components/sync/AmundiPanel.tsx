import { PiggyBank } from '@phosphor-icons/react'
import { SidecarSessionPanel } from "@/components/sync/SidecarSessionPanel"

interface AmundiPanelProps {
  onConnected?: () => void
}

export function AmundiPanel({ onConnected }: AmundiPanelProps = {}) {
  return (
    <SidecarSessionPanel
      provider="amundi"
      translationPrefix="sync.amundi"
      fieldIdPrefix="amundi"
      loginIcon={PiggyBank}
      appPush
      onConnected={onConnected}
    />
  )
}
