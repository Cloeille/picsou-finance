import type { TFunction } from 'i18next'
import type { CashflowFlowResponse, FlowNode } from '@/types/api'

/**
 * Pure helpers for the cashflow flow visualisations (Sankey + FlowBars). Kept in a
 * component-free module so the chart components stay Fast-Refresh-friendly
 * (react-refresh/only-export-components) while still sharing label/colour resolution.
 *
 * Backend `FlowNode`s are either real categories (carry their own `label`/`color`) or
 * `__…__` sentinels (label/color null) that we localise and colour here, so the Sankey
 * and the mobile bars always agree on naming.
 */

const SYNTHETIC_LABEL_KEY: Record<string, string> = {
  __hub__: 'budget.flow.node.hub',
  __income_other__: 'budget.flow.node.incomeOther',
  __drawdown__: 'budget.flow.node.drawdown',
  __savings__: 'budget.flow.node.savings',
  __expense_uncat__: 'budget.flow.node.uncategorized',
  __expense_more__: 'budget.flow.node.moreExpenses',
}

/** Semantic colours for synthetic nodes; real categories use their own colour. */
const SYNTHETIC_COLOR: Record<string, string> = {
  __hub__: 'var(--chart-3)',
  __income_other__: 'var(--chart-2)',
  __drawdown__: '#f59e0b', // amber: money pulled from savings to cover overspend
  __savings__: '#22c55e', // green: money kept
  __expense_uncat__: '#94a3b8',
  __expense_more__: '#94a3b8',
}

export const FLOW_FALLBACK_COLOR = '#6366f1'

/** Localised display name for a node — its own label, or the sentinel's translation. */
export function flowNodeLabel(node: Pick<FlowNode, 'key' | 'label'>, t: TFunction): string {
  if (node.label) return node.label
  const labelKey = SYNTHETIC_LABEL_KEY[node.key]
  return labelKey ? t(labelKey) : node.key
}

/** Display colour for a node — its own colour, else a semantic synthetic colour. */
export function flowNodeColor(node: Pick<FlowNode, 'key' | 'color'>): string {
  return node.color || SYNTHETIC_COLOR[node.key] || FLOW_FALLBACK_COLOR
}

export interface FlowBar {
  node: FlowNode
  value: number
}

/**
 * Splits the flow into its two human-readable sides by reading the hub's links: every
 * link into the hub is an inflow source, every link out of it is an outflow sink. Both
 * sides are sorted largest-first. Node magnitudes live on the links (not the nodes), so
 * this is the single place that pairs each node with its amount.
 */
export function flowSides(flow: CashflowFlowResponse): { sources: FlowBar[]; sinks: FlowBar[] } {
  const hubIndex = flow.nodes.findIndex((n) => n.type === 'HUB')
  const sources: FlowBar[] = []
  const sinks: FlowBar[] = []
  if (hubIndex < 0) return { sources, sinks }

  for (const link of flow.links) {
    if (link.target === hubIndex) {
      sources.push({ node: flow.nodes[link.source], value: link.value })
    } else if (link.source === hubIndex) {
      sinks.push({ node: flow.nodes[link.target], value: link.value })
    }
  }
  sources.sort((a, b) => b.value - a.value)
  sinks.sort((a, b) => b.value - a.value)
  return { sources, sinks }
}
