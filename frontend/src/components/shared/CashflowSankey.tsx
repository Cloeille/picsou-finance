import type { TFunction } from 'i18next'
import { useLayoutEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Layer, Rectangle, Sankey, Tooltip } from 'recharts'
import type { CashflowFlowResponse, FlowNode } from '@/types/api'
import { useMoney } from '@/hooks/use-money'
import { FlowBars } from './FlowBars'
import { FLOW_FALLBACK_COLOR, flowNodeColor, flowNodeLabel, flowSides } from './flow-utils'

/**
 * Income → budget → expense Sankey, or `FlowBars` when the container is narrower than the two
 * label columns plus a readable diagram (phones, and a 768px window with the sidebar open).
 *
 * Recharts owns the layout: it clones the `node`/`link` elements we pass, injecting the
 * computed geometry (`x/y/width/height`, bezier control points) while preserving the extra
 * props we set (`t`, `nodes`). We render fully custom shapes so node colours come from the
 * category palette / semantic sentinels and links are tinted by their non-hub endpoint.
 *
 * Node magnitudes live on the links, never the nodes, so the diagram and `FlowBars` can
 * never disagree — they read the same `{nodes, links}` the backend guarantees balanced.
 */

const MAX_LABEL = 20
/** Room for a full label beside the outer columns: MAX_LABEL characters plus an amount. */
const LABEL_MARGIN = 200
/**
 * Below this the diagram between the label columns is too thin to read, and below zero Recharts
 * lays the columns out right to left. Narrower containers get `FlowBars`.
 */
const MIN_PLOT_WIDTH = 240
const MARGIN_Y = 16
const MIN_HEIGHT = 360
/**
 * Recharts guarantees this gap between nodes of a column, so it is what keeps two labels from
 * overlapping however small their nodes get: one 11px line plus air.
 */
const NODE_PADDING = 18
/** Vertical room per node in the busiest column; past the minimum height the diagram grows. */
const ROW_HEIGHT = 30

function truncate(label: string): string {
  return label.length > MAX_LABEL ? `${label.slice(0, MAX_LABEL - 1)}…` : label
}

/** A node rectangle plus its name and amount, placed outside the diagram (left for in, right for out). */
function FlowSankeyNode(props: {
  x?: number
  y?: number
  width?: number
  height?: number
  payload?: FlowNode & { value?: number }
  t?: TFunction
}) {
  const money = useMoney()
  const { x = 0, y = 0, width = 0, height = 0, payload, t } = props
  if (!payload || !t) return null

  const color = flowNodeColor(payload)
  const label = flowNodeLabel(payload, t)
  const isHub = payload.type === 'HUB'
  const isRight =
    payload.type === 'EXPENSE' ||
    payload.type === 'SAVINGS' ||
    payload.type === 'TRANSFER_OUT' ||
    payload.type === 'UNSPENT'

  return (
    <Layer>
      <Rectangle
        x={x}
        y={y}
        width={width}
        height={height}
        fill={color}
        fillOpacity={0.9}
        radius={2}
      />
      {!isHub && (
        <text
          x={isRight ? x + width + 8 : x - 8}
          y={y + height / 2}
          textAnchor={isRight ? 'start' : 'end'}
          dominantBaseline="middle"
          fontSize={11}
        >
          <tspan className="fill-muted-foreground">{truncate(label)}</tspan>
          <tspan dx={6} className="fill-foreground font-medium tabular-nums">
            {money.amount(payload.value ?? 0)}
          </tspan>
        </text>
      )}
    </Layer>
  )
}

function resolveEndpoint(
  ref: number | FlowNode | undefined,
  nodes: FlowNode[],
): FlowNode | undefined {
  if (ref == null) return undefined
  return typeof ref === 'number' ? nodes[ref] : ref
}

/** A translucent ribbon tinted by whichever endpoint isn't the hub. */
function FlowSankeyLink(props: {
  sourceX?: number
  sourceY?: number
  sourceControlX?: number
  targetControlX?: number
  targetX?: number
  targetY?: number
  linkWidth?: number
  payload?: { source?: number | FlowNode; target?: number | FlowNode }
  nodes?: FlowNode[]
}) {
  const {
    sourceX = 0,
    sourceY = 0,
    sourceControlX = 0,
    targetControlX = 0,
    targetX = 0,
    targetY = 0,
    linkWidth = 0,
    payload,
    nodes = [],
  } = props

  const source = resolveEndpoint(payload?.source, nodes)
  const target = resolveEndpoint(payload?.target, nodes)
  const tinted = source && source.type !== 'HUB' ? source : target
  const color = tinted ? flowNodeColor(tinted) : FLOW_FALLBACK_COLOR

  return (
    <path
      d={`M${sourceX},${sourceY}C${sourceControlX},${sourceY} ${targetControlX},${targetY} ${targetX},${targetY}`}
      fill="none"
      stroke={color}
      strokeWidth={Math.max(1, linkWidth)}
      strokeOpacity={0.28}
    />
  )
}

function FlowTooltip(props: {
  active?: boolean
  payload?: Array<{ payload?: Record<string, unknown> }>
  t?: TFunction
  nodes?: FlowNode[]
}) {
  // Above the early return, as hook order demands. The tooltip subscribes for itself rather than
  // reading a formatter passed down, so one that is open when the toggle flips redraws with it.
  const money = useMoney()
  const { active, payload, t, nodes = [] } = props
  if (!active || !payload?.length || !t) return null
  const datum = payload[0]?.payload
  if (!datum) return null

  const value = typeof datum.value === 'number' ? datum.value : 0
  // A link datum carries source/target; a node datum carries a `type`.
  const isLink = 'source' in datum && 'target' in datum

  if (isLink) {
    const source = resolveEndpoint(datum.source as number | FlowNode, nodes)
    const target = resolveEndpoint(datum.target as number | FlowNode, nodes)
    if (!source || !target) return null
    return (
      <div className="rounded-lg border bg-background px-3 py-2 text-xs shadow-md">
        <p className="font-medium">
          {flowNodeLabel(source, t)} → {flowNodeLabel(target, t)}
        </p>
        <p className="text-muted-foreground tabular-nums">{money.amount(value)}</p>
      </div>
    )
  }

  return (
    <div className="rounded-lg border bg-background px-3 py-2 text-xs shadow-md">
      <p className="font-medium">{flowNodeLabel(datum as unknown as FlowNode, t)}</p>
      <p className="text-muted-foreground tabular-nums">{money.amount(value)}</p>
    </div>
  )
}

/**
 * Visually-hidden equivalent of the diagram for assistive tech. The SVG itself is opaque to
 * screen readers, so we mark it `aria-hidden` and expose the exact same `flowSides` split as a
 * real data table — every inflow/outflow with its amount, not just the aggregate summary.
 */
function FlowDataTable({ flow, t }: { flow: CashflowFlowResponse; t: TFunction }) {
  const money = useMoney()
  const { sources, sinks } = flowSides(flow)
  const caption = t('budget.flow.dataTableCaption', {
    income: money.amount(flow.income),
    expense: money.amount(flow.expense),
    net: money.amount(flow.net),
  })

  return (
    <table className="sr-only">
      <caption>{caption}</caption>
      <thead>
        <tr>
          <th scope="col">{t('budget.flow.colDirection')}</th>
          <th scope="col">{t('budget.flow.colName')}</th>
          <th scope="col">{t('budget.flow.colAmount')}</th>
        </tr>
      </thead>
      <tbody>
        {sources.map((bar, i) => (
          <tr key={`in-${bar.node.key}-${i}`}>
            <td>{t('budget.flow.inflows')}</td>
            <td>{flowNodeLabel(bar.node, t)}</td>
            <td>{money.amount(bar.value)}</td>
          </tr>
        ))}
        {sinks.map((bar, i) => (
          <tr key={`out-${bar.node.key}-${i}`}>
            <td>{t('budget.flow.outflows')}</td>
            <td>{flowNodeLabel(bar.node, t)}</td>
            <td>{money.amount(bar.value)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function SankeyFigure({ flow, width }: { flow: CashflowFlowResponse; width: number }) {
  const { t } = useTranslation()

  // Recharts mutates its data in place during layout — hand it fresh copies each render.
  const data = {
    nodes: flow.nodes.map((n) => ({ ...n })),
    links: flow.links.map((l) => ({ ...l })),
  }
  const { sources, sinks } = flowSides(flow)
  const height = Math.max(MIN_HEIGHT, Math.max(sources.length, sinks.length) * ROW_HEIGHT + 2 * MARGIN_Y)

  return (
    <figure className="m-0">
      <div aria-hidden="true">
        <Sankey
          width={width}
          height={height}
          data={data}
          nodeWidth={12}
          nodePadding={NODE_PADDING}
          margin={{ top: MARGIN_Y, right: LABEL_MARGIN, bottom: MARGIN_Y, left: LABEL_MARGIN }}
          node={<FlowSankeyNode t={t} />}
          link={<FlowSankeyLink nodes={data.nodes} />}
        >
          <Tooltip content={<FlowTooltip t={t} nodes={data.nodes} />} />
        </Sankey>
      </div>
      <FlowDataTable flow={flow} t={t} />
    </figure>
  )
}

export function CashflowSankey({ flow }: { flow: CashflowFlowResponse }) {
  const box = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState<number | null>(null)

  // Layout effect: the observer's first report lands before paint, so nothing flashes.
  useLayoutEffect(() => {
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width))
    observer.observe(box.current!)
    return () => observer.disconnect()
  }, [])

  return (
    <div ref={box} className="w-full">
      {width === null ? null : width < 2 * LABEL_MARGIN + MIN_PLOT_WIDTH ? (
        <FlowBars flow={flow} />
      ) : (
        <SankeyFigure flow={flow} width={width} />
      )}
    </div>
  )
}
