import '@testing-library/jest-dom'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { GoalMonthEntry, GoalProgress } from '@/types/api'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

const goal = {
  id: 2,
  type: 'SAVINGS_TARGET',
  name: 'Apport immobilier',
  monthlyNeeded: 1373.33,
} as GoalProgress

function month(yearMonth: string, effective: number | null): GoalMonthEntry {
  return { yearMonth, objective: 1373.33, actual: effective, manualActual: null, override: null, effective }
}

const months: GoalMonthEntry[] = [
  month('2026-08', 1373.33),
  month('2026-09', 984.72),
  month('2026-10', 1490),
  month('2026-11', null),
]

const mutation = () => ({ mutate: vi.fn(), isPending: false })

vi.mock('@/features/goals/hooks', () => ({
  useGoal: () => ({ data: goal, isLoading: false, error: null }),
  useGoalMonths: () => ({ data: months, isLoading: false }),
  useSetMonthOverride: mutation,
  useDeleteMonthOverride: mutation,
  useSetManualContribution: mutation,
  useDeleteManualContribution: mutation,
  useExtendGoalHistory: mutation,
  useExtendGoalHistoryByMonth: mutation,
}))

const { GoalCalendarPage } = await import('./GoalCalendarPage')

const scrolledIntoView: Element[] = []

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(2026, 9, 31, 12))
  window.matchMedia = vi.fn().mockReturnValue({
    matches: true,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  })
  Element.prototype.scrollIntoView = function (this: Element) {
    scrolledIntoView.push(this)
  }
})

afterEach(() => {
  vi.useRealTimers()
  scrolledIntoView.length = 0
})

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/goals/2/calendar']}>
      <Routes>
        <Route path="/goals/:id/calendar" element={<GoalCalendarPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

function monthButton(abbr: string): HTMLElement {
  return screen.getAllByRole('button').find((b) => b.textContent?.startsWith(abbr))!
}

describe('GoalCalendarPage year grid', () => {
  it('titles the page with the goal name rather than a sentence built around it', () => {
    renderPage()

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/^Apport immobilier$/)
    expect(screen.getByText('goals.calendar')).toBeInTheDocument()
  })

  it('writes every ring amount and objective in whole euros, never mixing in thousands', () => {
    renderPage()

    const september = monthButton('sept.')
    expect(september).toHaveTextContent(/985\s/)
    expect(september).toHaveTextContent(/72\s%/)
    expect(september).toHaveTextContent(/obj.\s1\s373\s/)

    const october = monthButton('oct.')
    expect(october).toHaveTextContent(/1\s490\s/)
    expect(october).toHaveTextContent(/\+8\s%/)
    expect(october).toHaveTextContent(/obj.\s1\s373\s/)

    for (const label of ['août', 'sept.', 'oct.']) {
      expect(monthButton(label).textContent).not.toMatch(/\bk\b|,\d/)
    }
  })

  it('scrolls the current month into view', () => {
    renderPage()

    const october = monthButton('oct.')
    expect(october).toHaveAttribute('aria-current', 'date')
    expect(scrolledIntoView).toContain(october)
  })

  it('explains progress and overshoot percentages in a legend', () => {
    renderPage()

    const legend = screen.getByText('goals.legendProgress').parentElement!
    expect(within(legend).getByText('goals.legendOvershoot')).toBeInTheDocument()
  })
})
