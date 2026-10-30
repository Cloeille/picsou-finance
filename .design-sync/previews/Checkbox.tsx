import { Checkbox } from "picsou"

export function Unchecked() {
  return (
    <label className="flex cursor-pointer items-center gap-2 text-sm text-muted-foreground">
      <Checkbox />
      Se souvenir de moi
    </label>
  )
}

export function Checked() {
  return (
    <label className="flex cursor-pointer items-center gap-2 text-sm text-muted-foreground">
      <Checkbox checked />
      Tout sélectionner
    </label>
  )
}

export function Disabled() {
  return (
    <div className="flex flex-col gap-2">
      <label className="flex items-center gap-2 text-sm text-muted-foreground opacity-50">
        <Checkbox disabled />
        Compte clôturé
      </label>
      <label className="flex items-center gap-2 text-sm text-muted-foreground opacity-50">
        <Checkbox checked disabled />
        Déjà importé
      </label>
    </div>
  )
}

const accounts = [
  { id: "cc", name: "Compte courant", count: 128, checked: true },
  { id: "la", name: "Livret A", count: 34, checked: true },
  { id: "cj", name: "Compte joint", count: 61, checked: false },
]

export function AccountList() {
  return (
    <div className="flex w-72 flex-col gap-2">
      {accounts.map((a) => (
        <label
          key={a.id}
          className="flex items-start gap-3 rounded-lg p-2 cursor-pointer hover:bg-muted/50"
        >
          <Checkbox checked={a.checked} className="mt-0.5" />
          <div className="min-w-0">
            <p className="truncate text-sm font-medium">{a.name}</p>
            <p className="text-xs text-muted-foreground">{a.count} transactions</p>
          </div>
        </label>
      ))}
    </div>
  )
}
