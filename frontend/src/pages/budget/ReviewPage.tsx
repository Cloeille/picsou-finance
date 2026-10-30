import { CategorizeTab } from './CategorizeTab'

/**
 * `/budget/review` — the "needs a glance" inbox. In the zero-config ideal this is
 * empty; M1 surfaces its count as a nudge on the overview rather than a standing tab.
 */
export function ReviewPage() {
  return <CategorizeTab />
}
