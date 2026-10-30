import { AccountCard } from "picsou"

const savings = {
  id: 1,
  name: "Livret A",
  type: "SAVINGS",
  currentBalanceEur: 12450,
  currency: "EUR",
  color: "#0055ff",
  provider: "Crédit Agricole",
  lastSyncedAt: "2024-05-15T09:30:00Z",
}

const checking = {
  id: 2,
  name: "Compte courant",
  type: "CHECKING",
  currentBalanceEur: 1204.55,
  currency: "EUR",
  color: "#12b886",
  provider: "Boursorama",
  lastSyncedAt: "2024-05-15T08:05:00Z",
}

export function Savings() {
  return (
    <div className="w-80">
      <AccountCard account={savings} />
    </div>
  )
}

export function Checking() {
  return (
    <div className="w-80">
      <AccountCard account={checking} />
    </div>
  )
}
