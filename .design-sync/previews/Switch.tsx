import { Switch } from "picsou"

export function States() {
  return (
    <div className="flex items-center gap-4">
      <Switch />
      <Switch checked />
    </div>
  )
}

export function SettingsRow() {
  return (
    <div className="flex w-80 flex-col gap-3">
      <div className="flex items-start justify-between gap-4 rounded-lg border border-border/60 p-4">
        <div className="space-y-1">
          <p className="text-sm font-medium">Synchronisation automatique</p>
          <p className="text-xs text-muted-foreground">
            Récupère les nouvelles transactions chaque nuit
          </p>
        </div>
        <Switch checked />
      </div>
      <div className="flex items-start justify-between gap-4 rounded-lg border border-border/60 p-4">
        <div className="space-y-1">
          <p className="text-sm font-medium">Cookies sécurisés (HTTPS)</p>
          <p className="text-xs text-muted-foreground">Requiert un accès en HTTPS</p>
        </div>
        <Switch />
      </div>
    </div>
  )
}

export function Sizes() {
  return (
    <div className="flex items-center gap-4">
      <Switch size="sm" checked />
      <Switch size="default" checked />
    </div>
  )
}

export function Disabled() {
  return (
    <div className="flex items-center gap-4">
      <Switch disabled />
      <Switch disabled checked />
    </div>
  )
}
