import { NumericInput, Label } from "picsou"

export function Amount() {
  return (
    <div className="grid w-64 gap-1.5">
      <Label htmlFor="ni-amount">Monthly contribution (€)</Label>
      <NumericInput id="ni-amount" defaultValue="250,00" placeholder="0,00" />
    </div>
  )
}

export function Empty() {
  return (
    <div className="grid w-64 gap-1.5">
      <Label htmlFor="ni-target">Target amount (€)</Label>
      <NumericInput id="ni-target" placeholder="e.g. 15 000" />
    </div>
  )
}
