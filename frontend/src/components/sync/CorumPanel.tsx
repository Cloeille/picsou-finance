import { Building2 } from "lucide-react"
import { SidecarSessionPanel } from "@/components/sync/SidecarSessionPanel"
import { ScpiSyncSummary } from "@/components/scpi/ScpiSyncSummary"
import { lifeRun } from "@/components/scpi/scpi-sync-run"
import { CorumLifeSyncSummary } from "@/components/sync/CorumLifeSyncSummary"
import { useAllAccounts } from "@/features/accounts/hooks"
import { useProviderStatus } from "@/features/sync/hooks"

interface CorumPanelProps {
  onConnected?: () => void
}

/**
 * CORUM needs no second factor, so unlike Amundi this panel never renders the
 * OTP or app-push state. The shared component handles that on its own: it only
 * reaches those screens when the auth response says a second factor is required.
 */
export function CorumPanel({ onConnected }: CorumPanelProps = {}) {
  // Same query as the panel's own status: the shared cache makes this free.
  const status = useProviderStatus("corum")
  // Hidden accounts included: a hidden contract the run updated is still a Life run.
  const accounts = useAllAccounts()
  const completedAt = status.data?.lastSyncCompletedAt
  const lifeAmbiguous = status.data?.lifeAmbiguousCount ?? 0
  const hasLife = lifeAmbiguous > 0
    || (!!accounts.data && !!completedAt && lifeRun(accounts.data, completedAt).length > 0)
  return (
    <SidecarSessionPanel
      provider="corum"
      translationPrefix="sync.corum"
      fieldIdPrefix="corum"
      loginIcon={Building2}
      onConnected={onConnected}
      successSummary={
        <>
          {/* Until the accounts load, an empty fund list may be a life-only login: no warning yet.
              A failed accounts read does not hide it for good: the warning then shows. */}
          <ScpiSyncSummary provider="CORUM" completedAt={completedAt} hideWhenEmpty={accounts.isPending || hasLife} />
          <CorumLifeSyncSummary completedAt={completedAt} ambiguous={lifeAmbiguous} />
        </>
      }
    />
  )
}
