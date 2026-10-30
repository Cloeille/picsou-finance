import { Skeleton } from "picsou"

export function AccountCard() {
  return (
    <div className="flex w-72 items-center gap-3 rounded-xl border p-3">
      <Skeleton className="size-10 rounded-full" />
      <div className="flex-1 space-y-2">
        <Skeleton className="h-4 w-32" />
        <Skeleton className="h-3 w-20" />
      </div>
    </div>
  )
}

export function TransactionList() {
  return (
    <div className="w-72 space-y-3 rounded-xl border p-3">
      {Array.from({ length: 3 }).map((_, i) => (
        <div key={i} className="flex items-center gap-3">
          <Skeleton className="size-8 rounded-full" />
          <div className="flex-1 space-y-1.5">
            <Skeleton className="h-3.5 w-28" />
            <Skeleton className="h-3 w-16" />
          </div>
        </div>
      ))}
    </div>
  )
}

export function ChartPlaceholder() {
  return <Skeleton className="h-[220px] w-72 rounded-xl" />
}

export function StatGrid() {
  return (
    <div className="grid w-72 grid-cols-2 gap-3">
      <Skeleton className="h-20 w-full rounded-xl" />
      <Skeleton className="h-20 w-full rounded-xl" />
      <Skeleton className="h-20 w-full rounded-xl" />
      <Skeleton className="h-20 w-full rounded-xl" />
    </div>
  )
}
