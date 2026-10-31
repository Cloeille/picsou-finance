import { CurrencyDisplay } from "picsou"

export function Amounts() {
  return (
    <div className="flex flex-col gap-1.5 text-lg font-semibold tabular-nums">
      <CurrencyDisplay value={12450} />
      <CurrencyDisplay value={-890.5} className="text-red-500" />
      <CurrencyDisplay value={3200} showSign className="text-emerald-500" />
      <CurrencyDisplay value={4200.75} currency="USD" />
    </div>
  )
}

export function InlineBalance() {
  return (
    <div className="flex items-baseline justify-between gap-6 rounded-lg border p-3">
      <span className="text-sm text-muted-foreground">Compte courant</span>
      <CurrencyDisplay value={1204.55} className="font-semibold tabular-nums" />
    </div>
  )
}
