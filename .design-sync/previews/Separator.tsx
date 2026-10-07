import { Separator } from "picsou"

export function Horizontal() {
  return (
    <div className="w-64">
      <p className="text-sm font-medium">Compte courant</p>
      <Separator className="my-3" />
      <p className="text-sm font-medium">Livret A</p>
    </div>
  )
}

export function Vertical() {
  return (
    <div className="flex h-5 items-center gap-3 text-sm text-muted-foreground">
      <span>Crédit Agricole</span>
      <Separator orientation="vertical" />
      <span>IBAN FR76 •••• 4821</span>
      <Separator orientation="vertical" />
      <span>Checking</span>
    </div>
  )
}

export function InList() {
  return (
    <div className="w-72 rounded-lg border p-3">
      <div className="flex items-center justify-between text-sm">
        <span>Boulangerie du Coin</span>
        <span className="tabular-nums">-€4.20</span>
      </div>
      <Separator className="my-2" />
      <div className="flex items-center justify-between text-sm">
        <span>Spotify</span>
        <span className="tabular-nums">-€9.99</span>
      </div>
      <Separator className="my-2" />
      <div className="flex items-center justify-between text-sm">
        <span>Virement salaire</span>
        <span className="tabular-nums text-emerald-500">+€2,450.00</span>
      </div>
    </div>
  )
}
