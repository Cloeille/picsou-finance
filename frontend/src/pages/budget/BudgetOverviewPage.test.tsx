import { render } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

const trackFeature = vi.hoisted(() => vi.fn())
vi.mock('@/lib/telemetry', () => ({ trackFeature }))
vi.mock('./OverviewTab', () => ({ OverviewTab: () => <div>Budget overview</div> }))

const { BudgetOverviewPage } = await import('./BudgetOverviewPage')

describe('BudgetOverviewPage telemetry', () => {
  it('tracks one payload-free view event per mount', () => {
    const view = render(<BudgetOverviewPage />)
    view.rerender(<BudgetOverviewPage />)

    expect(trackFeature).toHaveBeenCalledTimes(1)
    expect(trackFeature).toHaveBeenCalledWith('budget_viewed')
    expect(JSON.stringify(trackFeature.mock.calls)).toBe(JSON.stringify([['budget_viewed']]))
  })
})