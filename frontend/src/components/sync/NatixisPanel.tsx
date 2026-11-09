import { Mail } from "lucide-react"
import { SidecarSessionPanel } from "@/components/sync/SidecarSessionPanel"

interface NatixisPanelProps {
  onConnected?: () => void
}

/** Natixis Interépargne employee savings; the code arrives by SMS, e-mail or voice call, never an app push. */
export function NatixisPanel({ onConnected }: NatixisPanelProps = {}) {
  return (
    <SidecarSessionPanel
      provider="natixis"
      translationPrefix="sync.natixis"
      fieldIdPrefix="natixis"
      loginIcon={Mail}
      otpRetryable
      onConnected={onConnected}
    />
  )
}
