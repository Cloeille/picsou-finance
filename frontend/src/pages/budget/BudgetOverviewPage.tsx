import { useEffect, useRef } from 'react'
import { trackFeature } from '@/lib/telemetry'
import { OverviewTab } from './OverviewTab'

/** `/budget` index — the recap surface. */
export function BudgetOverviewPage() {
  const tracked = useRef(false)
  useEffect(() => {
    if (tracked.current) return
    tracked.current = true
    trackFeature('budget_viewed')
  }, [])

  return <OverviewTab />
}
