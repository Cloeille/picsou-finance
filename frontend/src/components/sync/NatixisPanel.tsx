import { EnvelopeSimple } from '@phosphor-icons/react'
import { NatixisBrowserSession } from "@/components/sync/NatixisBrowserSession"
import { SidecarSessionPanel } from "@/components/sync/SidecarSessionPanel"

interface NatixisPanelProps {
  onConnected?: () => void
}

/**
 * Natixis Interépargne employee savings. The code arrives by SMS, e-mail or voice call, never an app push.
 * An account Natixis makes register a passkey signs in on the Natixis site instead, and hands the session over.
 */
export function NatixisPanel({ onConnected }: NatixisPanelProps = {}) {
  return (
    <div className="space-y-6">
      <SidecarSessionPanel
        provider="natixis"
        translationPrefix="sync.natixis"
        fieldIdPrefix="natixis"
        loginIcon={EnvelopeSimple}
        otpRetryable
        onConnected={onConnected}
      />
      <NatixisBrowserSession onConnected={onConnected} />
    </div>
  )
}
