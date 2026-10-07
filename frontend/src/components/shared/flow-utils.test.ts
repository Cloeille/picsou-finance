import type { TFunction } from 'i18next'
import { describe, expect, it } from 'vitest'
import type { CashflowFlowResponse } from '@/types/api'
import { FLOW_FALLBACK_COLOR, flowNodeColor, flowNodeLabel, flowSides } from './flow-utils'

// A stub translator that echoes the key, so we can assert which key was looked up.
const tEcho = ((key: string) => key) as unknown as TFunction

describe('flowNodeLabel', () => {
  it('prefers a node’s own label (real category)', () => {
    expect(flowNodeLabel({ key: 'cat:2', label: 'Courses' }, tEcho)).toBe('Courses')
  })

  it('translates synthetic sentinels via their i18n key', () => {
    expect(flowNodeLabel({ key: '__hub__', label: null }, tEcho)).toBe('budget.flow.node.hub')
    expect(flowNodeLabel({ key: '__savings__', label: null }, tEcho)).toBe('budget.flow.node.savings')
    expect(flowNodeLabel({ key: '__expense_uncat__', label: null }, tEcho)).toBe(
      'budget.flow.node.uncategorized',
    )
  })

  it('falls back to the raw key for an unknown sentinel', () => {
    expect(flowNodeLabel({ key: '__mystery__', label: null }, tEcho)).toBe('__mystery__')
  })
})

describe('flowNodeColor', () => {
  it('prefers a node’s own colour', () => {
    expect(flowNodeColor({ key: 'cat:2', color: '#abcdef' })).toBe('#abcdef')
  })

  it('uses the semantic colour for known sentinels', () => {
    expect(flowNodeColor({ key: '__savings__', color: null })).toBe('#22c55e')
    expect(flowNodeColor({ key: '__drawdown__', color: null })).toBe('#f59e0b')
  })

  it('falls back when neither colour nor sentinel matches', () => {
    expect(flowNodeColor({ key: '__mystery__', color: null })).toBe(FLOW_FALLBACK_COLOR)
  })
})

describe('flowSides', () => {
  // Salary → hub → {Logement, Savings}: a balanced positive-net graph.
  const flow: CashflowFlowResponse = {
    period: 'CYCLE',
    from: '2025-03-01',
    to: '2025-03-31',
    income: 3000,
    expense: 1000,
    net: 2000,
    nodes: [
      { key: 'cat:1', label: 'Salaire', color: '#10b981', type: 'INCOME' },
      { key: '__hub__', label: null, color: null, type: 'HUB' },
      { key: 'cat:2', label: 'Logement', color: '#6366f1', type: 'EXPENSE' },
      { key: '__savings__', label: null, color: null, type: 'SAVINGS' },
    ],
    links: [
      { source: 0, target: 1, value: 3000 },
      { source: 1, target: 2, value: 1000 },
      { source: 1, target: 3, value: 2000 },
    ],
  }

  it('splits links into sources (into hub) and sinks (out of hub)', () => {
    const { sources, sinks } = flowSides(flow)
    expect(sources.map((s) => s.node.key)).toEqual(['cat:1'])
    expect(sinks.map((s) => s.node.key)).toEqual(['__savings__', 'cat:2']) // sorted desc by value
    expect(sources[0].value).toBe(3000)
    expect(sinks[0].value).toBe(2000)
  })

  it('returns empty sides when there is no hub', () => {
    const { sources, sinks } = flowSides({ ...flow, nodes: [], links: [] })
    expect(sources).toEqual([])
    expect(sinks).toEqual([])
  })
})
