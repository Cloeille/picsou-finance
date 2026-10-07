import { Input, Label } from "picsou"
import { Eye } from "lucide-react"

export function WithLabel() {
  return (
    <div className="flex w-64 flex-col gap-1.5">
      <Label htmlFor="account-name">Nom du compte</Label>
      <Input id="account-name" placeholder="Livret A" />
    </div>
  )
}

export function PasswordField() {
  return (
    <div className="flex w-64 flex-col gap-1.5">
      <Label htmlFor="account-password">Mot de passe</Label>
      <div className="relative">
        <Input id="account-password" type="password" defaultValue="hunter2024" className="pr-9" />
        <Eye
          size={16}
          className="absolute right-2.5 top-1/2 -translate-y-1/2 text-muted-foreground"
        />
      </div>
    </div>
  )
}

export function Currency() {
  return (
    <div className="flex w-64 flex-col gap-1.5">
      <Label htmlFor="initial-balance">Solde initial</Label>
      <div className="relative">
        <Input
          id="initial-balance"
          type="number"
          defaultValue="2450.00"
          className="pr-7 tabular-nums"
        />
        <span className="absolute right-2.5 top-1/2 -translate-y-1/2 text-xs text-muted-foreground">
          €
        </span>
      </div>
    </div>
  )
}

export function Invalid() {
  return (
    <div className="flex w-64 flex-col gap-1.5">
      <Label htmlFor="contact-email">Email</Label>
      <Input id="contact-email" defaultValue="alex@example" aria-invalid />
      <p className="text-xs text-destructive">Adresse email invalide</p>
    </div>
  )
}

export function Disabled() {
  return (
    <div className="flex w-64 flex-col gap-1.5">
      <Label htmlFor="provider-name">Fournisseur</Label>
      <Input id="provider-name" defaultValue="Boursorama" disabled />
    </div>
  )
}
