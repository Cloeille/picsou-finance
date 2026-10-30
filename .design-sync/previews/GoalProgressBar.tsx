import { GoalProgressBar } from "picsou"

const emergencyFund = {
  id: 1,
  name: "Emergency fund",
  targetAmount: 15000,
  deadline: "2024-12-01",
  createdAt: "2024-01-01",
  historyStartMonth: null,
  accounts: [],
  currentTotal: 10200,
  percentComplete: 68,
  monthsLeft: 4,
  monthlyNeeded: 1200,
  avgMonthlyContribution: 950,
  isOnTrack: true,
}

const vacation = {
  ...emergencyFund,
  id: 2,
  name: "Vacances 2026",
  targetAmount: 6000,
  currentTotal: 2160,
  percentComplete: 36,
  monthsLeft: 9,
  isOnTrack: false,
}

export function OnTrack() {
  return (
    <div className="w-80">
      <GoalProgressBar goal={emergencyFund} />
    </div>
  )
}

export function Behind() {
  return (
    <div className="w-80">
      <GoalProgressBar goal={vacation} />
    </div>
  )
}
