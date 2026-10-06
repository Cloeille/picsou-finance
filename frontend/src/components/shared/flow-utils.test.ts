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
    expect(flowNodeLabel({ key: '__unspent__', label: null }, tEcho)).toBe('budget.flow.node.unspent')
    expect(flowNodeLabel({ key: '__shortfall__', label: null }, tEcho)).toBe('budget.flow.node.shortfall')
    expect(flowNodeLabel({ key: '__expense_uncat__', label: null }, tEcho)).toBe(
      'budget.flow.node.uncategorized',
    )
  })

  it('prefixes a savings account sink with its asset class', () => {
    const tSpy = ((key: string, opts?: { name: string }) => `${key}|${opts?.name}`) as unknown as TFunction
    expect(
      flowNodeLabel({ key: 'acct:1', label: 'Livret A', type: 'SAVINGS', assetClass: 'SAVINGS' }, tSpy),
    ).toBe('budget.flow.node.savingsAccount|Livret A')
    expect(
      flowNodeLabel({ key: 'acct:2', label: 'PEA', type: 'SAVINGS', assetClass: 'INVESTMENT' }, tSpy),
    ).toBe('budget.flow.node.investmentAccount|PEA')
    // A missing assetClass defaults to the savings wording.
    expect(flowNodeLabel({ key: 'acct:3', label: 'LEP', type: 'SAVINGS' }, tSpy)).toBe(
      'budget.flow.node.savingsAccount|LEP',
    )
  })

  it('leaves a category node label unchanged', () => {
    expect(flowNodeLabel({ key: 'cat:2', label: 'Courses', type: 'EXPENSE', assetClass: null }, tEcho)).toBe('Courses')
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
    expect(flowNodeColor({ key: '__unspent__', color: null })).toBe('#0ea5e9')
    expect(flowNodeColor({ key: '__shortfall__', color: null })).toBe('#f59e0b')
  })

  it('colours a SAVINGS node without colour green, but keeps an account’s own colour', () => {
    expect(flowNodeColor({ key: 'acct:1', color: null, type: 'SAVINGS' })).toBe('#22c55e')
    expect(flowNodeColor({ key: 'acct:2', color: '#123456', type: 'SAVINGS' })).toBe('#123456')
  })

  it('falls back when neither colour nor sentinel matches', () => {
    expect(flowNodeColor({ key: '__mystery__', color: null })).toBe(FLOW_FALLBACK_COLOR)
  })
})

describe('flowSides', () => {
  // Salary → hub → {Logement, Livret A}: a balanced graph (income = expense + saved).
  const flow: CashflowFlowResponse = {
    period: 'CYCLE',
    from: '2025-03-01',
    to: '2025-03-31',
    income: 3000,
    expense: 1000,
    net: 2000,
    saved: 2000,
    nodes: [
      { key: 'cat:1', label: 'Salaire', color: '#10b981', type: 'INCOME' },
      { key: '__hub__', label: null, color: null, type: 'HUB' },
      { key: 'cat:2', label: 'Logement', color: '#6366f1', type: 'EXPENSE' },
      { key: 'acct:1', label: 'Livret A', color: '#22c55e', type: 'SAVINGS' },
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
    expect(sinks.map((s) => s.node.key)).toEqual(['acct:1', 'cat:2']) // sorted desc by value
    expect(sources[0].value).toBe(3000)
    expect(sinks[0].value).toBe(2000)
  })

  it('returns empty sides when there is no hub', () => {
    const { sources, sinks } = flowSides({ ...flow, nodes: [], links: [] })
    expect(sources).toEqual([])
    expect(sinks).toEqual([])
  })
})
