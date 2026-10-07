import { MerchantAvatar } from "picsou"

export function Merchants() {
  return (
    <div className="flex items-center gap-3">
      <MerchantAvatar label="Carrefour" monogram="C" color="#0055ff" />
      <MerchantAvatar label="Netflix" monogram="N" color="#e50914" />
      <MerchantAvatar label="SNCF" monogram="S" color="#12b886" />
      <MerchantAvatar label="Spotify" monogram="S" color="#1db954" />
    </div>
  )
}

export function Sizes() {
  return (
    <div className="flex items-center gap-3">
      <MerchantAvatar label="Amazon" monogram="A" color="#ff9900" size="sm" />
      <MerchantAvatar label="Amazon" monogram="A" color="#ff9900" size="md" />
      <MerchantAvatar label="Amazon" monogram="A" color="#ff9900" size="lg" />
    </div>
  )
}

export function InRow() {
  return (
    <div className="flex w-72 items-center gap-3 rounded-lg border p-2.5">
      <MerchantAvatar label="Carrefour Market" monogram="C" color="#0055ff" />
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium">Carrefour Market</p>
        <p className="text-xs text-muted-foreground">Groceries · 2 Jul</p>
      </div>
      <span className="text-sm font-medium tabular-nums">-€64.20</span>
    </div>
  )
}
