import '@testing-library/jest-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { TFunction } from 'i18next'
import { mockFlow } from '@/demo/data/budget'
import { makeMoneyFormatter } from '@/lib/money'
import { localeFromLanguage } from '@/lib/utils'
import { CashflowSankey } from './CashflowSankey'
import { flowNodeLabel, flowSides } from './flow-utils'

const LABELS: Record<string, string> = {
  'budget.flow.node.savingsAccount': 'Épargne · {{name}}',
  'budget.flow.node.investmentAccount': 'Investissement · {{name}}',
  'budget.flow.node.uncategorized': 'Sans catégorie',
  'budget.flow.node.transferOut': 'Virements sortants',
  'budget.flow.node.unspent': 'Non dépensé',
}
const t = ((key: string, opts?: { name?: string }) =>
  (LABELS[key] ?? key).replace('{{name}}', opts?.name ?? '')) as unknown as TFunction

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t, i18n: { language: 'fr', resolvedLanguage: 'fr' } }),
}))

// jsdom has no layout, so stand in for the width the container would measure.
let boxWidth = 960
vi.stubGlobal(
  'ResizeObserver',
  class {
    private readonly report: ResizeObserverCallback
    constructor(report: ResizeObserverCallback) {
      this.report = report
    }
    observe() {
      this.report([{ contentRect: { width: boxWidth } } as ResizeObserverEntry], this as never)
    }
    disconnect() {}
  },
)

describe('CashflowSankey', () => {
  const flow = mockFlow('CYCLE')
  const { sinks } = flowSides(flow)
  const money = makeMoneyFormatter({ hidden: false, locale: localeFromLanguage('fr') })

  beforeEach(() => {
    boxWidth = 960
  })

  function labelRows(container: HTMLElement) {
    return [...container.querySelectorAll('svg text')].map((el) => ({
      text: el.textContent ?? '',
      y: Number(el.getAttribute('y')),
      anchor: el.getAttribute('text-anchor'),
    }))
  }

  it('labels every destination with its name and amount', () => {
    expect(sinks.length).toBeGreaterThan(10)
    const { container } = render(<CashflowSankey flow={flow} />)
    expect(labelRows(container).find((r) => r.anchor === 'end')?.text).toMatch(/^Salaire/)
    const rows = labelRows(container).filter((r) => r.anchor === 'start')

    expect(rows).toHaveLength(sinks.length)
    for (const sink of sinks) {
      const name = flowNodeLabel(sink.node, t).slice(0, 12)
      const row = rows.find((r) => r.text.startsWith(name))
      expect(row, `label for ${name}`).toBeDefined()
      expect(row!.text.endsWith(money.amount(sink.value)), `amount for ${name}`).toBe(true)
    }
  })

  it('keeps a full text line between adjacent destination labels', () => {
    const { container } = render(<CashflowSankey flow={flow} />)
    const ys = labelRows(container)
      .filter((r) => r.anchor === 'start')
      .map((r) => r.y)
      .sort((a, b) => a - b)

    expect(ys).toHaveLength(sinks.length)
    for (let i = 1; i < ys.length; i++) {
      expect(ys[i] - ys[i - 1]).toBeGreaterThanOrEqual(14)
    }
  })

  // A 768px window with the sidebar open leaves the card about 392px: less than the two label
  // columns alone, so a diagram there would fold its columns over each other.
  it('lists the flows instead of drawing a diagram too narrow for its labels', () => {
    boxWidth = 392
    const { container } = render(<CashflowSankey flow={flow} />)

    expect(container.querySelector('svg')).toBeNull()
    for (const sink of sinks) {
      expect(screen.getAllByRole('progressbar', { name: flowNodeLabel(sink.node, t) }).length).toBeGreaterThan(0)
    }
  })

  it('draws sources left of destinations in the narrowest card that gets a diagram', () => {
    boxWidth = 640
    const { container } = render(<CashflowSankey flow={flow} />)
    const nodeXs = (anchor: string) =>
      [...container.querySelectorAll(`svg text[text-anchor="${anchor}"]`)].map((el) =>
        Number(el.previousElementSibling?.getAttribute('x')),
      )

    expect(nodeXs('start')).toHaveLength(sinks.length)
    expect(Math.max(...nodeXs('end'))).toBeLessThan(Math.min(...nodeXs('start')))
  })
})
