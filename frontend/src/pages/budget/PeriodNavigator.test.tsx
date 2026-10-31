import '@testing-library/jest-dom'
import React from 'react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { PeriodNavigator } from './PeriodNavigator'

// ─── Hoisted mock config (accessible inside vi.mock factory) ─────────────────

const mockSettings = vi.hoisted(() => ({ cycleStartDay: 1 }))

// ─── Module mocks ─────────────────────────────────────────────────────────────

vi.mock('@/features/budget/hooks', () => ({
  useBudgetSettings: () => ({ data: { cycleStartDay: mockSettings.cycleStartDay } }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

// Mock getLocale for deterministic en-US output; cn simplified (Button still works).
vi.mock('@/lib/utils', () => ({
  getLocale: () => 'en-US',
  cn: (...classes: unknown[]) => classes.filter(Boolean).join(' '),
}))

// Flatten the dropdown so items render immediately — no jsdom portal / animation issues.
// Note: shadcn's real DropdownMenu uses a Radix portal; items would not be queryable in jsdom
// without this mock. Prev/Next behavior and disabled-state tests are portal-independent.
vi.mock('@/components/ui/dropdown-menu', () => ({
  DropdownMenu: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DropdownMenuTrigger: ({
    children,
  }: {
    children: React.ReactNode
    asChild?: boolean
  }) => <div>{children}</div>,
  DropdownMenuContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DropdownMenuItem: ({
    children,
    onSelect,
  }: {
    children: React.ReactNode
    onSelect?: () => void
  }) => (
    <button role="menuitem" onClick={onSelect}>
      {children}
    </button>
  ),
}))

// ─── Typing helper ────────────────────────────────────────────────────────────
// vi.fn() returns Mock<Procedure|Constructable> which TS does not directly assign to a
// typed callback prop. Cast at the JSX call site; keep the raw spy for assertions.
type AnchorSpy = ReturnType<typeof vi.fn>
function cb(spy: AnchorSpy): (anchorIso: string) => void {
  return spy as unknown as (anchorIso: string) => void
}

// ─── Helper: today as YYYY-MM-DD (mirrors component logic) ────────────────────
function getTodayIso(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

// ─── Test suites ──────────────────────────────────────────────────────────────

describe('PeriodNavigator', () => {
  // ── 1. CYCLE: past month (2024-03) ─────────────────────────────────────────
  describe('CYCLE past month — 2024-03', () => {
    let spy: AnchorSpy

    beforeEach(() => {
      spy = vi.fn()
    })

    it('renders a label "March 2024" (en-US locale)', () => {
      render(
        <PeriodNavigator
          period="CYCLE"
          from="2024-03-01"
          to="2024-03-31"
          onAnchorChange={cb(spy)}
        />,
      )
      // The trigger button text is the Intl-formatted label
      expect(screen.getByRole('button', { name: /march 2024/i })).toBeInTheDocument()
    })

    it('Next is ENABLED for a past period (to < today)', () => {
      render(
        <PeriodNavigator
          period="CYCLE"
          from="2024-03-01"
          to="2024-03-31"
          onAnchorChange={cb(spy)}
        />,
      )
      expect(screen.getByRole('button', { name: 'budget.period.next' })).not.toBeDisabled()
    })

    it('click Next → onAnchorChange("2024-04-01")', () => {
      render(
        <PeriodNavigator
          period="CYCLE"
          from="2024-03-01"
          to="2024-03-31"
          onAnchorChange={cb(spy)}
        />,
      )
      fireEvent.click(screen.getByRole('button', { name: 'budget.period.next' }))
      expect(spy).toHaveBeenCalledWith('2024-04-01')
    })

    it('click Prev → onAnchorChange("2024-02-29") — addDays(from, -1) on a leap year', () => {
      render(
        <PeriodNavigator
          period="CYCLE"
          from="2024-03-01"
          to="2024-03-31"
          onAnchorChange={cb(spy)}
        />,
      )
      fireEvent.click(screen.getByRole('button', { name: 'budget.period.prev' }))
      expect(spy).toHaveBeenCalledWith('2024-02-29')
    })
  })

  // ── 2. CYCLE: Next disabled when `to` >= today or undefined ────────────────
  describe('CYCLE — Next disabled', () => {
    it('Next is disabled when to is far in the future (to >= today)', () => {
      const spy = vi.fn()
      render(
        <PeriodNavigator
          period="CYCLE"
          from="2999-12-01"
          to="2999-12-31"
          onAnchorChange={cb(spy)}
        />,
      )
      expect(screen.getByRole('button', { name: 'budget.period.next' })).toBeDisabled()
    })

    it('Next is disabled when to is undefined (period not yet resolved)', () => {
      const spy = vi.fn()
      render(
        <PeriodNavigator
          period="CYCLE"
          from="2024-06-01"
          onAnchorChange={cb(spy)}
        />,
      )
      expect(screen.getByRole('button', { name: 'budget.period.next' })).toBeDisabled()
    })
  })

  // ── 3. YTD: past year 2024 ──────────────────────────────────────────────────
  describe('YTD — year 2024', () => {
    let spy: AnchorSpy

    beforeEach(() => {
      spy = vi.fn()
    })

    it('renders the label "2024"', () => {
      render(
        <PeriodNavigator
          period="YTD"
          from="2024-01-01"
          to="2024-12-31"
          onAnchorChange={cb(spy)}
        />,
      )
      // The trigger button text is just the year
      expect(screen.getByRole('button', { name: /^2024$/ })).toBeInTheDocument()
    })

    it('click Prev → onAnchorChange("2023-12-31")', () => {
      render(
        <PeriodNavigator
          period="YTD"
          from="2024-01-01"
          to="2024-12-31"
          onAnchorChange={cb(spy)}
        />,
      )
      fireEvent.click(screen.getByRole('button', { name: 'budget.period.prev' }))
      expect(spy).toHaveBeenCalledWith('2023-12-31')
    })

    it('click Next → emits an anchor starting with "2025-"', () => {
      render(
        <PeriodNavigator
          period="YTD"
          from="2024-01-01"
          to="2024-12-31"
          onAnchorChange={cb(spy)}
        />,
      )
      fireEvent.click(screen.getByRole('button', { name: 'budget.period.next' }))
      const emitted = spy.mock.calls[0]?.[0] as string
      // 2025 < currentYear (2026 at time of writing) → "2025-12-31"; safe anchor with /^2025-/
      expect(emitted).toMatch(/^2025-/)
    })
  })

  // ── 4. Jump dropdown — CYCLE (24 month items) ───────────────────────────────
  describe('Jump dropdown — CYCLE', () => {
    it('renders 24 month items and clicking one emits a YYYY-MM-01 anchor (cycleStartDay=1)', () => {
      const spy = vi.fn()
      render(
        <PeriodNavigator
          period="CYCLE"
          from="2024-03-01"
          to="2024-03-31"
          onAnchorChange={cb(spy)}
        />,
      )
      // With the mocked dropdown, all items are always visible (no open/close interaction needed)
      const items = screen.getAllByRole('menuitem')
      expect(items).toHaveLength(24)

      // Click the first item (most recent month)
      fireEvent.click(items[0])
      const emitted = spy.mock.calls[0]?.[0] as string
      // cycleStartDay=1 → anchor ends in "-01"
      expect(emitted).toMatch(/^\d{4}-\d{2}-01$/)
    })
  })

  // ── 5. Jump dropdown — YTD (6 year items) ───────────────────────────────────
  describe('Jump dropdown — YTD', () => {
    it('renders 6 year items; first item (current year) emits todayIso', () => {
      const spy = vi.fn()
      render(
        <PeriodNavigator
          period="YTD"
          from="2024-01-01"
          to="2024-12-31"
          onAnchorChange={cb(spy)}
        />,
      )
      const items = screen.getAllByRole('menuitem')
      expect(items).toHaveLength(6)

      // First item = current year → yearAnchor(currentYear, currentYear, todayIso) = todayIso
      fireEvent.click(items[0])
      const emitted = spy.mock.calls[0]?.[0] as string
      expect(emitted).toBe(getTodayIso())
    })
  })

  // ── 6. No future cycle in jump dropdown (cycleStartDay=25) ──────────────────
  describe('Jump dropdown — CYCLE no-future-cycle guard (cycleStartDay=25)', () => {
    beforeEach(() => {
      mockSettings.cycleStartDay = 25
    })

    afterEach(() => {
      mockSettings.cycleStartDay = 1
    })

    it('every rendered cycle item emits an anchor <= todayIso (calendar-safe)', () => {
      const spy = vi.fn()
      render(
        <PeriodNavigator
          period="CYCLE"
          from="2024-03-25"
          to="2024-04-24"
          onAnchorChange={cb(spy)}
        />,
      )
      const todayIso = getTodayIso()
      const items = screen.getAllByRole('menuitem')

      // Every item must emit an anchor that is not in the future.
      items.forEach((item, idx) => {
        fireEvent.click(item)
        const emitted = spy.mock.calls[idx]?.[0] as string
        expect(emitted <= todayIso).toBe(true)
      })
    })
  })
})
