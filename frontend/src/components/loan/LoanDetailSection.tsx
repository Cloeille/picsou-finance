import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { useLoanSummary } from '@/features/accounts/hooks'
import { LoanMonthlyBreakdownCard } from './LoanMonthlyBreakdownCard'
import { LoanProgressCard } from './LoanProgressCard'
import { LoanCostSummaryTable } from './LoanCostSummaryTable'
import { LoanAmortizationChart } from './LoanAmortizationChart'

interface LoanDetailSectionProps {
  accountId: number
}

export function LoanDetailSection({ accountId }: LoanDetailSectionProps) {
  const { data, isLoading, isError } = useLoanSummary(accountId)

  if (isLoading) {
    return (
      <Card>
        <CardContent className="pt-6">
          <Skeleton className="h-32 w-full" />
        </CardContent>
      </Card>
    )
  }

  if (isError || !data) return null

  const { summary, schedule } = data

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <LoanMonthlyBreakdownCard summary={summary} />
        <LoanProgressCard summary={summary} />
      </div>
      <LoanCostSummaryTable summary={summary} />
      <LoanAmortizationChart schedule={schedule} />
    </div>
  )
}
