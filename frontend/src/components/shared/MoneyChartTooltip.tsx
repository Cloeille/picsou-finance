import { ChartTooltipContent, type ChartConfig } from '@/components/ui/chart'
import { useMoney } from '@/hooks/use-money'
import { cn } from '@/lib/utils'

type TooltipProps = React.ComponentProps<typeof ChartTooltipContent>

/**
 * A chart tooltip whose values are amounts, and are therefore masked in privacy mode.
 *
 * `ChartTooltipContent` prints `item.value.toLocaleString()` when no `formatter` is given, which
 * is the raw euro figure — the one place a chart leaks even though nothing about it says "money".
 * Passing a bare `formatter` would fix the number and lose the rest: the formatter replaces the
 * *whole* row, colour indicator and series name included (`ui/chart.tsx:215`). So the row is
 * reproduced here.
 *
 * The config has to come in as a prop: `useChart()` is not exported and `ui/chart.tsx` is vendored
 * shadcn, marked do-not-edit. Both call sites build their config anyway, so they pass it.
 */
export function MoneyChartTooltip({
  config,
  currency,
  indicator = 'dot',
  ...props
}: TooltipProps & { config: ChartConfig; currency?: string }) {
  const money = useMoney()

  return (
    <ChartTooltipContent
      {...props}
      indicator={indicator}
      formatter={(value, name, item) => {
        const color = item?.payload?.fill ?? item?.color
        return (
          <>
            <div
              className={cn(
                'shrink-0 rounded-[2px] border-(--color-border) bg-(--color-bg)',
                indicator === 'dot' ? 'h-2.5 w-2.5 self-center' : 'w-1',
              )}
              style={{ '--color-bg': color, '--color-border': color } as React.CSSProperties}
            />
            <div className="flex flex-1 items-center justify-between gap-2 leading-none">
              <span className="text-muted-foreground">
                {config[name as string]?.label ?? name}
              </span>
              <span className="font-mono font-medium text-foreground tabular-nums">
                {money.amount(Number(value), currency)}
              </span>
            </div>
          </>
        )
      }}
    />
  )
}
