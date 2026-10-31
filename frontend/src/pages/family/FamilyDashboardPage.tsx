import { useTranslation } from 'react-i18next'
import { useMoney } from '@/hooks/use-money'
import { PageHeader } from '@/components/shared/PageHeader'
import { EmptyState } from '@/components/shared/EmptyState'
import { LoadingSkeleton } from '@/components/shared/LoadingSkeleton'
import { useFamilyDashboard } from '@/features/family/hooks'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Wallet, Target, Users } from 'lucide-react'

export function FamilyDashboardPage() {
  const money = useMoney()
  const { t } = useTranslation()
  const { data, isLoading } = useFamilyDashboard()

  if (isLoading) {
    return <LoadingSkeleton />
  }

  const dashboard = data!

  return (
    <div className="space-y-6">
      <PageHeader
        title={t('family.dashboard.title')}
      />

      {/* Total shared net worth */}
      <Card>
        <CardHeader className="flex flex-row items-center gap-4">
          <div className="flex size-12 items-center justify-center rounded-lg bg-muted">
            <Users className="size-6 text-muted-foreground" />
          </div>
          <div>
            <CardTitle className="text-sm font-medium text-muted-foreground">
              {t('family.dashboard.sharedNetWorth')}
            </CardTitle>
            <p className="text-2xl font-bold">
              {money.amount(dashboard.totalSharedNetWorth)}
            </p>
          </div>
        </CardHeader>
      </Card>

      {/* Shared accounts */}
      <div>
        <h2 className="mb-3 text-lg font-semibold flex items-center gap-2">
          <Wallet className="size-5" />
          {t('family.dashboard.sharedAccounts')}
        </h2>
        {dashboard.sharedAccounts.length === 0 ? (
          <Card>
            <CardContent>
              <EmptyState
                className="py-8"
                icon={<Wallet className="size-10" />}
                title={t('family.dashboard.noSharedAccounts')}
              />
            </CardContent>
          </Card>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {dashboard.sharedAccounts.map((account) => (
              <Card key={`${account.ownerName}-${account.id}`}>
                <CardHeader className="pb-2">
                  <div className="flex items-center justify-between">
                    <CardTitle className="text-sm">{account.name}</CardTitle>
                    <span className="text-xs text-muted-foreground">{account.ownerName}</span>
                  </div>
                  <p className="text-xs text-muted-foreground">{account.type}</p>
                </CardHeader>
                <CardContent>
                  <p className="text-lg font-bold">
                    {money.amount(account.balanceEur)}
                  </p>
                  {account.currency !== 'EUR' && (
                    <p className="text-xs text-muted-foreground">
                      {money.amount(account.balance, account.currency)}
                    </p>
                  )}
                </CardContent>
              </Card>
            ))}
          </div>
        )}
      </div>

      {/* Shared goals */}
      <div>
        <h2 className="mb-3 text-lg font-semibold flex items-center gap-2">
          <Target className="size-5" />
          {t('family.dashboard.sharedGoals')}
        </h2>
        {dashboard.sharedGoals.length === 0 ? (
          <Card>
            <CardContent>
              <EmptyState
                className="py-8"
                icon={<Target className="size-10" />}
                title={t('family.dashboard.noSharedGoals')}
              />
            </CardContent>
          </Card>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2">
            {dashboard.sharedGoals.map((goal) => {
              const progress = goal.targetAmount > 0
                ? Math.min((goal.currentTotal / goal.targetAmount) * 100, 100)
                : 0
              return (
                <Card key={`${goal.ownerName}-${goal.id}`}>
                  <CardHeader className="pb-2">
                    <div className="flex items-center justify-between">
                      <CardTitle className="text-sm">{goal.name}</CardTitle>
                      <span className="text-xs text-muted-foreground">{goal.ownerName}</span>
                    </div>
                  </CardHeader>
                  <CardContent className="space-y-3">
                    {/* Progress bar */}
                    <div>
                      <div className="flex justify-between text-xs mb-1">
                        <span>{money.amount(goal.currentTotal)}</span>
                        <span className="text-muted-foreground">{money.amount(goal.targetAmount)}</span>
                      </div>
                      <div className="h-2 rounded-full bg-muted overflow-hidden">
                        <div
                          className="h-full rounded-full bg-primary transition-[width]"
                          style={{ width: `${progress}%` }}
                        />
                      </div>
                    </div>

                    {/* Per-member contributions */}
                    {goal.contributions.length > 0 && (
                      <div className="space-y-1">
                        <p className="text-xs font-medium text-muted-foreground">
                          {t('family.dashboard.contributions')}
                        </p>
                        {goal.contributions.map((c) => (
                          <div key={c.memberName} className="flex justify-between text-xs">
                            <span>{c.memberName}</span>
                            <span className="font-medium">{money.amount(c.amount)}</span>
                          </div>
                        ))}
                      </div>
                    )}
                  </CardContent>
                </Card>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}
