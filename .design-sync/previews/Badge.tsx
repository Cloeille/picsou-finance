import { Badge } from "picsou"
import { Check, AlertTriangle } from "lucide-react"

export function Variants() {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Badge>Livret A</Badge>
      <Badge variant="secondary">Savings</Badge>
      <Badge variant="destructive">Overdue</Badge>
      <Badge variant="outline">Checking</Badge>
      <Badge variant="ghost">Draft</Badge>
      <Badge variant="link">Details</Badge>
    </div>
  )
}

export function SyncStatus() {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Badge className="bg-green-500/10 text-green-600 dark:text-green-400">Connected</Badge>
      <Badge variant="destructive">Failed</Badge>
      <Badge variant="outline">Pending</Badge>
    </div>
  )
}

export function WithIcons() {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Badge className="bg-green-500/10 text-green-600 dark:text-green-400">
        <Check data-icon="inline-start" /> Synced
      </Badge>
      <Badge variant="destructive">
        <AlertTriangle data-icon="inline-start" /> 3 errors
      </Badge>
    </div>
  )
}

export function AccessScopes() {
  return (
    <div className="flex flex-wrap gap-1">
      <Badge variant="secondary" className="font-mono text-[10px]">
        accounts:read
      </Badge>
      <Badge variant="secondary" className="font-mono text-[10px]">
        transactions:read
      </Badge>
      <Badge variant="secondary" className="font-mono text-[10px]">
        goals:write
      </Badge>
    </div>
  )
}
