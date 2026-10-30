import { Progress } from "picsou"

export function Values() {
  return (
    <div className="flex w-72 flex-col gap-4">
      <Progress value={30} className="h-2" />
      <Progress value={70} className="h-2" />
      <Progress value={100} className="h-2" />
    </div>
  )
}

export function GoalProgress() {
  return (
    <div className="w-72 space-y-2">
      <div className="flex items-center justify-between text-sm">
        <span className="font-medium">Fonds d'urgence</span>
        <span className="text-muted-foreground">68%</span>
      </div>
      <Progress value={68} className="h-2" />
      <div className="flex items-center justify-between text-xs text-muted-foreground">
        <span>On track</span>
        <span>4 months left</span>
      </div>
    </div>
  )
}

export function ColoredIndicator() {
  return (
    <div className="w-72 space-y-2">
      <p className="text-sm font-medium">Vacances 2026</p>
      <Progress
        value={82}
        className="h-2.5 [&_[data-slot=progress-indicator]]:bg-emerald-500"
      />
    </div>
  )
}

export function WizardStep() {
  return (
    <div className="w-72">
      <Progress value={45} className="h-1 rounded-full" />
    </div>
  )
}
