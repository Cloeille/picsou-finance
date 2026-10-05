import { useTranslation } from 'react-i18next'
import { Info } from 'lucide-react'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { Card, CardContent } from '@/components/ui/card'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { FRENCH_HOUSEHOLD_SAVINGS_RATE } from '@/lib/constants'
import { monthlyContributions } from './plan-math'
import type { GoalProgress } from '@/types/api'

interface SavingsRateCardProps {
  /** The member's recurring plans; the card decides for itself which are running. */
  plans: GoalProgress[]
  /**
   * Net monthly income from the settings profile — what actually lands in the account. Null
   * until both figures behind it are stated, which is when the card asks instead of computing.
   */
  monthlyNetIncome: number | null
  /** ISO date, passed in rather than read here so the component stays pure. */
  today: string
  onOpenSettings: () => void
}

/**
 * What share of their income the member is putting aside every month, against the national
 * average.
 *
 * The denominator is **net** income, because that is what INSEE's rate uses too: "revenu
 * disponible brut" is gross of capital consumption, not of tax — it is measured after
 * compulsory levies. Dividing by a gross salary would understate the rate by roughly a quarter
 * and put the two sides of the comparison on different bases.
 *
 * They are still not the same quantity — disposable income is not salary, and standing orders
 * are not all of saving — so the tooltip quotes the definition rather than presenting the
 * comparison as arithmetic. It answers "more or less than people around me", which is the
 * question being asked.
 */
export function SavingsRateCard({
  plans,
  monthlyNetIncome,
  today,
  onOpenSettings,
}: SavingsRateCardProps) {
  const { t } = useTranslation()

  const contributions = monthlyContributions(plans, today)
  if (contributions <= 0) return null

  // No income stated: show what is being put aside and say what is missing, rather than
  // inventing a denominator. Same principle as the allocation targets form, which never guesses
  // an expense on the member's behalf.
  if (monthlyNetIncome == null || monthlyNetIncome <= 0) {
    return (
      <Card>
        <CardContent className="flex flex-wrap items-center justify-between gap-3 py-4">
          <p className="text-sm text-muted-foreground">{t('goals.savingsRate.needsIncome')}</p>
          <button
            type="button"
            onClick={onOpenSettings}
            className="text-sm font-medium text-primary underline-offset-4 hover:underline"
          >
            {t('goals.savingsRate.goToSettings')}
          </button>
        </CardContent>
      </Card>
    )
  }

  const rate = (contributions / monthlyNetIncome) * 100

  return (
    <Card>
      <CardContent className="flex flex-wrap items-center justify-between gap-4 py-4">
        <div className="min-w-0">
          <div className="flex items-center gap-1.5">
            <p className="text-xs text-muted-foreground">{t('goals.savingsRate.title')}</p>
            {/* Wrapped locally like PriceFreshnessDot and AccountCard: providers.tsx already
                supplies one app-wide, but this keeps the card renderable on its own. */}
            <TooltipProvider>
              <Tooltip>
                <TooltipTrigger asChild>
                  <button
                    type="button"
                    aria-label={t('goals.savingsRate.benchmarkLabel')}
                    className="text-muted-foreground hover:text-foreground"
                  >
                    <Info className="size-3.5" />
                  </button>
                </TooltipTrigger>
                <TooltipContent className="max-w-xs">
                  {t('goals.savingsRate.benchmark', { rate: FRENCH_HOUSEHOLD_SAVINGS_RATE })}
                </TooltipContent>
              </Tooltip>
            </TooltipProvider>
          </div>
          {/* A ratio, so it stays legible in privacy mode -- the amounts beside it do not. */}
          <p className="text-2xl font-semibold tabular-nums">{rate.toFixed(1)} %</p>
          <p className="mt-0.5 text-sm text-muted-foreground">
            <CurrencyDisplay value={contributions} />
            {' / '}
            <CurrencyDisplay value={monthlyNetIncome} />
            {' '}
            {t('goals.savingsRate.perMonth')}
          </p>
        </div>
      </CardContent>
    </Card>
  )
}
