import '@testing-library/jest-dom'
import { Children, cloneElement, isValidElement, type ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

interface ChartPoint {
  date: string
  total: number | null
  invested?: number | null
  pnl?: number | null
}

interface TooltipPoint {
  date?: string
  timestamp?: string
  total: number | null
  invested?: number | null
  pnl?: number | null
  target?: number
  projection?: number
}

interface TooltipEntry {
  value: number | null
  dataKey: string
  payload: TooltipPoint
}

interface TooltipProps {
  active?: boolean
  payload?: TooltipEntry[]
}

const UNKNOWN_POINT_TOOLTIP: TooltipEntry[] = [
  {
    dataKey: 'total',
    value: 1200,
    payload: { date: '2026-02-01', total: 1200, invested: null, pnl: null },
  },
  {
    dataKey: 'invested',
    value: null,
    payload: { date: '2026-02-01', total: 1200, invested: null, pnl: null },
  },
]

vi.mock('recharts', async (importOriginal) => {
  const { getNiceTickValues } = await importOriginal<typeof import('recharts')>()
  const ResponsiveContainer = ({ children }: { children?: ReactNode }) => <div>{children}</div>
  const AreaChart = ({ data, children }: { data: ChartPoint[]; children?: ReactNode }) => (
    <div data-testid="area-chart">
      {data.map((point, index) => (
        <div key={point.date}>
          <span data-testid={`chart-total-${index}`}>{point.total ?? '—'}</span>
          <span data-testid={`chart-invested-${index}`}>{point.invested ?? '—'}</span>
        </div>
      ))}
      {Children.map(children, child => isValidElement(child) && typeof child.type !== 'string' ? child : null)}
    </div>
  )
  const Area = ({ dataKey }: { dataKey: string }) => <span data-testid={`series-${dataKey}`} />
  const Line = ({ dataKey }: { dataKey: string }) => <span data-testid={`series-${dataKey}`} />
  const Legend = ({ content }: { content?: () => ReactNode }) => <div>{content?.()}</div>
  const Empty = () => null

  return {
    getNiceTickValues,
    ResponsiveContainer,
    AreaChart,
    Area,
    Line,
    Legend,
    CartesianGrid: Empty,
    ReferenceLine: Empty,
    XAxis: Empty,
    YAxis: ({ width }: { width?: number }) => <span data-testid="y-axis" data-width={width} />,
    Tooltip: Empty,
  }
})

vi.mock('@/components/ui/chart', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/components/ui/chart')>()
  return {
    ...actual,
    ChartTooltip: ({ content }: { content?: ReactNode }) => {
      if (!isValidElement<TooltipProps>(content)) return null
      return (
        <div data-testid="active-tooltip">
          {cloneElement(content, { active: true, payload: UNKNOWN_POINT_TOOLTIP })}
        </div>
      )
    },
  }
})
vi.mock('@/components/shared/TimeRangeSelector', () => ({ TimeRangeSelector: () => null }))
vi.mock('@/components/shared/EmptyChartState', () => ({ EmptyChartState: () => null }))
vi.mock('@/hooks/use-money', () => ({
  useMoney: () => ({
    amount: (value: number) => String(value),
    tick: (value: number) => String(value),
  }),
}))
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'en', language: 'en' },
  }),
}))

const { NetWorthChart } = await import('./NetWorthChart')

describe('NetWorthChart nullable performance data', () => {
  it('keeps the total while unknown invested capital and PnL stay out of the chart tooltip', () => {
    render(
      <NetWorthChart
        data={[
          { date: '2026-02-01', total: 1200, invested: null, pnl: null },
          { date: '2026-03-01', total: 1250, invested: 1200, pnl: 50 },
        ]}
        range="ALL"
        onRangeChange={() => {}}
      />,
    )

    expect(screen.getByTestId('chart-total-0')).toHaveTextContent('1200')
    expect(screen.getByTestId('chart-invested-0')).toHaveTextContent('—')
    expect(screen.getByTestId('series-total')).toBeInTheDocument()
    expect(screen.getByTestId('series-invested')).toBeInTheDocument()

    const tooltip = screen.getByTestId('active-tooltip')
    expect(tooltip).toHaveTextContent('dashboard.netWorth')
    expect(tooltip).toHaveTextContent('1200')
    expect(tooltip).not.toHaveTextContent('dashboard.invested')
    expect(tooltip).not.toHaveTextContent('dashboard.gainLoss')
    const displayedValues = Array.from(tooltip.querySelectorAll('span'), span => span.textContent?.trim())
    expect(displayedValues).not.toContain('0')
  })
})

describe('NetWorthChart Y axis width', () => {
  const axisWidth = (props: { invested: number | null; showInvested: boolean }) => {
    const { unmount } = render(
      <NetWorthChart
        data={[{ date: '2026-02-01', total: 100, invested: props.invested, pnl: null }]}
        range="ALL"
        onRangeChange={() => {}}
        showInvested={props.showInvested}
      />,
    )
    const width = Number(screen.getByTestId('y-axis').dataset.width)
    unmount()
    return width
  }

  it('is sized for the visible series only when invested capital is hidden', () => {
    const withoutInvestedData = axisWidth({ invested: null, showInvested: false })

    expect(axisWidth({ invested: 100_000, showInvested: false })).toBe(withoutInvestedData)
    expect(axisWidth({ invested: 100_000, showInvested: true })).toBeGreaterThan(withoutInvestedData)
  })
})
