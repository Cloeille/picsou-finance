import { Input, Label, Checkbox } from "picsou"

export function WithInput() {
  return (
    <div className="flex w-64 flex-col gap-1.5">
      <Label htmlFor="label-email">Email</Label>
      <Input id="label-email" defaultValue="alex@example.com" />
    </div>
  )
}

export function WithOptionalHint() {
  return (
    <div className="flex w-64 flex-col gap-1.5">
      <Label htmlFor="label-name">
        Nom <span className="text-muted-foreground text-xs">(optionnel)</span>
      </Label>
      <Input id="label-name" placeholder="Épargne vacances" />
    </div>
  )
}

export function WithCheckbox() {
  return (
    <div className="flex items-center gap-2">
      <Checkbox id="label-remember" />
      <Label htmlFor="label-remember" className="cursor-pointer">
        Se souvenir de moi
      </Label>
    </div>
  )
}

const accounts = [
  { id: "cc", name: "Compte courant", color: "#3b82f6", balance: "2 450 €" },
  { id: "la", name: "Livret A", color: "#22c55e", balance: "8 900 €" },
  { id: "cj", name: "Compte joint", color: "#f59e0b", balance: "1 120 €" },
]

export function GroupLabel() {
  return (
    <div className="flex w-72 flex-col gap-2">
      <Label>Comptes inclus</Label>
      <div className="flex flex-col gap-2">
        {accounts.map((a) => (
          <label key={a.id} className="flex items-center gap-2.5 cursor-pointer select-none">
            <Checkbox checked />
            <span className="size-2.5 shrink-0 rounded-full" style={{ background: a.color }} />
            <span className="flex-1 text-sm">{a.name}</span>
            <span className="text-xs text-muted-foreground tabular-nums">{a.balance}</span>
          </label>
        ))}
      </div>
    </div>
  )
}
