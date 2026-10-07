import { EnvelopesTab } from './EnvelopesTab'
import { AllocationTab } from './AllocationTab'

/**
 * `/budget/envelopes` — spending caps + where the surplus goes. The redesign merges
 * the former separate "allocation" tab into this surface: envelopes (per-cycle caps)
 * on top, the savings/investment allocation breakdown below.
 */
export function EnvelopesPage() {
  return (
    <div className="space-y-8">
      <EnvelopesTab />
      <AllocationTab />
    </div>
  )
}
