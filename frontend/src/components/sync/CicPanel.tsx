import { Landmark } from "lucide-react"
import { SidecarSessionPanel } from "@/components/sync/SidecarSessionPanel"

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
      provider="cic"
      translationPrefix="sync.cic"
      fieldIdPrefix="cic"
      loginIcon={Landmark}
      appPush
      otpRetryable
      autoReconnect={autoReconnect}
      onAutoReconnectStarted={onAutoReconnectStarted}
      onConnected={onConnected}
    />
  )
}
