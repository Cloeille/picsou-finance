import { PriceFreshnessDot } from "picsou"

// Capture clock is pinned to 2024-05-15T12:00:00Z; "live" is < 2 min old.
export function Holdings() {
  return (
    <div className="flex w-72 flex-col gap-2">
      <div className="flex items-center gap-2 rounded-lg border p-2.5">
        <span className="flex-1 text-sm font-medium">Bitcoin</span>
        <span className="text-sm tabular-nums">€2,840.12</span>
        <PriceFreshnessDot priceUpdatedAt="2024-05-15T11:59:10Z" />
      </div>
      <div className="flex items-center gap-2 rounded-lg border p-2.5">
        <span className="flex-1 text-sm font-medium">Ethereum</span>
        <span className="text-sm tabular-nums">€1,510.40</span>
        <PriceFreshnessDot priceUpdatedAt="2024-05-15T08:20:00Z" />
      </div>
    </div>
  )
}
