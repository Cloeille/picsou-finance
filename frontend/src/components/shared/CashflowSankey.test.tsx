import '@testing-library/jest-dom'
import { cloneElement, type ReactElement } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { render } from '@testing-library/react'
import type { TFunction } from 'i18next'
import { mockFlow } from '@/demo/data/budget'
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

// jsdom has no layout, so stand in for the box the container would measure.
vi.mock('recharts', async (importOriginal) => ({
  ...(await importOriginal<typeof import('recharts')>()),
  ResponsiveContainer: ({ children, height }: { children: ReactElement; height?: number | string }) =>
    cloneElement(children as ReactElement<{ width: number; height: number }>, {
      width: 960,
      height: typeof height === 'number' ? height : 360,
    }),
}))

describe('CashflowSankey', () => {
  const flow = mockFlow('CYCLE')
  const { sinks } = flowSides(flow)

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
      expect(row!.text).toMatch(/\d/)
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
})
