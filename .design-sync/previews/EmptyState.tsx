import { EmptyState } from "picsou"
import { Inbox, Target } from "lucide-react"

export function NoTransactions() {
  return (
    <div className="w-96 rounded-lg border">
      <EmptyState
        icon={<Inbox className="size-12" />}
        title="No transactions yet"
        description="Connect a bank account to start tracking your spending."
        action={{ label: "Connect a bank", onClick: () => {} }}
      />
    </div>
  )
}

export function NoGoals() {
  return (
    <div className="w-96 rounded-lg border">
      <EmptyState
        icon={<Target className="size-12" />}
        title="No savings goals"
        description="Set a target and track your progress toward it."
      />
    </div>
  )
}
