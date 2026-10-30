import { RecurringTab } from './RecurringTab'

/**
 * `/budget/subscriptions` — recurring payments. M3 turns this into the auto-confirmed
 * subscriptions surface with price-change alerts and a "what changed" activity feed.
 */
export function SubscriptionsPage() {
  return <RecurringTab />
}
