import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { useTranslation } from 'react-i18next'
import { useGoals, useCreateGoal, useUpdateGoal, useDeleteGoal } from '@/features/goals/hooks'
import { useAccounts } from '@/features/accounts/hooks'
import { useMemberProfile } from '@/features/profile/hooks'
import { AllocationPicker } from './AllocationPicker'
import { allocatedTotal, hasIncompleteAllocations } from './plan-math'
import { SavingsRateCard } from './SavingsRateCard'
import { parseRecurringGoalPrefill } from '@/pages/analysis/projection-inflow'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { EmptyState } from '@/components/shared/EmptyState'
import { ErrorState } from '@/components/shared/ErrorState'
import { PageHeader } from '@/components/shared/PageHeader'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { LoadingSkeleton } from '@/components/shared/LoadingSkeleton'
import { GoalDetailModal } from './GoalDetailModal'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { NumericInput } from '@/components/shared/NumericInput'
import { DateInput } from '@/components/shared/DateInput'
import { formatDate, formatPercent, parseAmount } from '@/lib/utils'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Progress } from '@/components/ui/progress'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '@/components/ui/dialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {
  Target,
  Plus,
  PencilSimple,
  Trash,
  DotsThree,
  CaretDown,
  CircleNotch,
} from '@phosphor-icons/react'
import { cn } from '@/lib/utils'
import type { GoalProgress, GoalType } from '@/types/api'

const emptyForm = {
  name: '',
  type: 'SAVINGS_TARGET' as GoalType,
  targetAmount: '',
  deadline: '',
  monthlyAmount: '',
  expectedReturn: '',
  startDate: '',
  endDate: '',
  // Amounts stay strings while the user types, like every other field here; parseAmount runs
  // once at submit.
  allocations: [] as { ticker: string; amount: string }[],
  accountIds: [] as number[],
}

export function GoalsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()

  const { data: goals, isLoading, isError, refetch } = useGoals()
  const { data: accounts } = useAccounts()
  const { data: profile } = useMemberProfile()
  const createGoal = useCreateGoal()
  const updateGoal = useUpdateGoal()
  const deleteGoal = useDeleteGoal()

  const [showForm, setShowForm] = useState(() => parseRecurringGoalPrefill(searchParams).open)
  const [editingGoal, setEditingGoal] = useState<GoalProgress | null>(null)
  const [deleteId, setDeleteId] = useState<number | null>(null)
  const [form, setForm] = useState(() => {
    const prefill = parseRecurringGoalPrefill(searchParams)
    if (!prefill.open) return emptyForm
    return {
      ...emptyForm,
      type: 'RECURRING_INVESTMENT' as GoalType,
      monthlyAmount: prefill.monthlyAmount,
      accountIds: prefill.accountIds,
    }
  })
  const [detailGoalId, setDetailGoalId] = useState<number | null>(null)
  // Lazy initializer, not a bare `new Date()` in render: the React Compiler's `purity` rule
  // forbids reading the clock while rendering, and a date that changed every render would
  // re-evaluate which plans are running on every keystroke.
  const [today] = useState(() => new Date().toISOString().slice(0, 10))

  const openCreate = () => {
    setEditingGoal(null)
    setForm(emptyForm)
    setShowForm(true)
  }

  const openEdit = (goal: GoalProgress) => {
    setEditingGoal(goal)
    setForm({
      name: goal.name,
      type: goal.type,
      targetAmount: goal.targetAmount == null ? '' : String(goal.targetAmount),
      deadline: goal.deadline ?? '',
      monthlyAmount: goal.monthlyAmount == null ? '' : String(goal.monthlyAmount),
      expectedReturn: goal.expectedReturn == null ? '' : String(goal.expectedReturn),
      startDate: goal.startDate ?? '',
      endDate: goal.endDate ?? '',
      allocations: goal.allocations.map((a) => ({ ticker: a.ticker, amount: String(a.monthlyAmount) })),
      accountIds: goal.accounts.map((a) => a.id),
    })
    setShowForm(true)
  }

  const closeForm = () => {
    setShowForm(false)
    setEditingGoal(null)
    if (searchParams.has('create') || searchParams.has('amount') || searchParams.has('accountId')) {
      const next = new URLSearchParams(searchParams)
      next.delete('create')
      next.delete('amount')
      next.delete('accountId')
      setSearchParams(next, { replace: true })
    }
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    // The two shapes send different halves; the backend defaults an omitted type, but being
    // explicit here keeps the payload readable in the network tab.
    const recurring = form.type === 'RECURRING_INVESTMENT'
    const data = {
      name: form.name,
      type: form.type,
      targetAmount: recurring ? null : parseAmount(form.targetAmount),
      deadline: recurring ? null : form.deadline,
      monthlyAmount: recurring ? parseAmount(form.monthlyAmount) : null,
      expectedReturn: recurring && form.expectedReturn ? parseAmount(form.expectedReturn) : null,
      startDate: recurring && form.startDate ? form.startDate : null,
      endDate: recurring && form.endDate ? form.endDate : null,
      allocations: recurring
        ? form.allocations
            .map((a) => ({ ticker: a.ticker, monthlyAmount: parseAmount(a.amount) }))
            // A ticked line with an empty amount is a line the user has not finished; sending
            // NaN would be a 422 on a field they cannot see.
            .filter((a) => Number.isFinite(a.monthlyAmount) && a.monthlyAmount > 0)
        : [],
      accountIds: form.accountIds,
    }
    if (editingGoal) {
      await updateGoal.mutateAsync({ id: editingGoal.id, data })
    } else {
      await createGoal.mutateAsync(data)
    }
    closeForm()
  }

  const toggleAccount = (id: number) => {
    setForm((f) => {
      // A recurring plan funds exactly one account — the backend refuses more, so the picker
      // behaves like a radio group rather than letting the user build an invalid request.
      if (f.type === 'RECURRING_INVESTMENT') {
        // The split names positions of the funded account, so changing that account invalidates
        // every line: keeping them would post tickers the new account does not hold, and the
        // backend answers 400.
        return { ...f, accountIds: f.accountIds.includes(id) ? [] : [id], allocations: [] }
      }
      return {
        ...f,
        accountIds: f.accountIds.includes(id)
          ? f.accountIds.filter((a) => a !== id)
          : [...f.accountIds, id],
      }
    })
  }

  // Derived once for the picker and the Save guard, so the two cannot disagree about whether
  // the split fits. The backend's 422 is still the authority; this is the earlier answer.
  const monthlyAmountValue = (() => {
    const parsed = parseAmount(form.monthlyAmount)
    return Number.isFinite(parsed) ? parsed : 0
  })()
  const overAllocated =
    form.type === 'RECURRING_INVESTMENT' &&
    allocatedTotal(form.allocations) - monthlyAmountValue > 0.005
  const incompleteAllocations =
    form.type === 'RECURRING_INVESTMENT' &&
    hasIncompleteAllocations(form.allocations)

  const handleConfirmDelete = () => {
    if (deleteId != null) {
      deleteGoal.mutate(deleteId)
      setDeleteId(null)
    }
  }

  if (isError) {
    return (
      <div className="space-y-6">
        <PageHeader title={t('goals.title')} />
        <ErrorState title={t('common.error')} onRetry={() => void refetch()} />
      </div>
    )
  }

  if (isLoading) return <LoadingSkeleton />

  const goalList = goals ?? []
  // Two shapes, two sections: a savings target has a percentage and a deadline, a recurring plan
  // has neither, and one list would have to render an empty progress bar for half its rows.
  const savingsTargets = goalList.filter((g) => g.type !== 'RECURRING_INVESTMENT')
  const recurringPlans = goalList.filter((g) => g.type === 'RECURRING_INVESTMENT')

  return (
    <div className="space-y-6">
      <PageHeader
        title={t('goals.title')}
        actions={
          <Button onClick={openCreate} size="sm" className="gap-1.5">
            <Plus className="size-4" />
            {t('goals.addGoal')}
          </Button>
        }
      />

      {goalList.length === 0 ? (
        <Card>
          <CardContent>
            <EmptyState
              className="py-8"
              icon={<Target className="size-10" />}
              title={t('goals.noGoals')}
            />
          </CardContent>
        </Card>
      ) : (
        <div className="flex flex-col gap-8">
          {savingsTargets.length > 0 && (
            <section className="flex flex-col gap-3">
              <h2 className="text-sm text-muted-foreground">{t('goals.sections.savings')}</h2>
              {savingsTargets.map((goal) => (
                <GoalCard
                  key={goal.id}
                  goal={goal}
                  onOpenDetail={() => setDetailGoalId(goal.id)}
                />
              ))}
            </section>
          )}

          {recurringPlans.length > 0 && (
            <section className="flex flex-col gap-3">
              <h2 className="text-sm text-muted-foreground">{t('goals.sections.recurring')}</h2>
              <SavingsRateCard
                plans={recurringPlans}
                monthlyNetIncome={profile?.monthlyNetIncome ?? null}
                today={today}
                onOpenSettings={() => navigate('/settings?tab=profile')}
              />
              {recurringPlans.map((goal) => (
                <RecurringPlanCard
                  key={goal.id}
                  goal={goal}
                  onEdit={() => openEdit(goal)}
                  onDelete={() => setDeleteId(goal.id)}
                />
              ))}
            </section>
          )}
        </div>
      )}

      {/* Goal detail modal */}
      <GoalDetailModal
        goalId={detailGoalId}
        onClose={() => setDetailGoalId(null)}
        onEdit={
          detailGoalId != null
            ? () => {
                const target = goalList.find((g) => g.id === detailGoalId)
                setDetailGoalId(null)
                if (target) openEdit(target)
              }
            : undefined
        }
        onCalendar={
          detailGoalId != null
            ? () => {
                const id = detailGoalId
                setDetailGoalId(null)
                navigate(`/goals/${id}/calendar`)
              }
            : undefined
        }
        onDelete={
          detailGoalId != null
            ? () => {
                const id = detailGoalId
                setDetailGoalId(null)
                setDeleteId(id)
              }
            : undefined
        }
      />

      {/* Create / Edit dialog */}
      <Dialog open={showForm} onOpenChange={(open) => { if (!open) closeForm() }}>
        <DialogContent className="sm:max-w-2xl" aria-describedby={undefined}>
          <DialogHeader>
            <DialogTitle>
              {editingGoal ? t('goals.editGoal') : t('goals.addGoal')}
            </DialogTitle>
          </DialogHeader>

          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            {!editingGoal && (
              <div className="flex flex-col gap-1.5">
                <Label>{t('goals.goalType')}</Label>
                <div className="flex gap-2">
                  {(['SAVINGS_TARGET', 'RECURRING_INVESTMENT'] as GoalType[]).map((type) => (
                    <Button
                      key={type}
                      type="button"
                      variant={form.type === type ? 'default' : 'outline'}
                      size="sm"
                      onClick={() => setForm((f) => ({ ...f, type, accountIds: [], allocations: [] }))}
                    >
                      {t(`goals.types.${type}`)}
                    </Button>
                  ))}
                </div>
              </div>
            )}

            <div className="flex flex-col gap-1.5">
              <Label htmlFor="goal-name">{t('goals.title')}</Label>
              <Input
                id="goal-name"
                required
                value={form.name}
                onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
                placeholder={t('goals.namePlaceholder')}
              />
            </div>

            {form.type === 'SAVINGS_TARGET' ? (
              <div className="grid items-end grid-cols-2 gap-3">
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="goal-target">{t('goals.targetAmount')}</Label>
                  <NumericInput
                    id="goal-target"
                    required
                    value={form.targetAmount}
                    onChange={(e) => setForm((f) => ({ ...f, targetAmount: e.target.value }))}
                    placeholder="50000"
                  />
                </div>
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="goal-deadline">{t('goals.deadline')}</Label>
                  <DateInput
                    id="goal-deadline"
                    required
                    value={form.deadline}
                    onChange={(iso) => setForm((f) => ({ ...f, deadline: iso }))}
                  />
                </div>
              </div>
            ) : (
              <>
                <div className="grid items-end grid-cols-2 gap-3">
                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="goal-monthly">{t('goals.monthlyAmount')}</Label>
                    <NumericInput
                      id="goal-monthly"
                      required
                      value={form.monthlyAmount}
                      onChange={(e) => setForm((f) => ({ ...f, monthlyAmount: e.target.value }))}
                      placeholder="300"
                    />
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="goal-return">{t('goals.expectedReturn')}</Label>
                    <NumericInput
                      id="goal-return"
                      value={form.expectedReturn}
                      onChange={(e) => setForm((f) => ({ ...f, expectedReturn: e.target.value }))}
                      placeholder="7.5"
                    />
                  </div>
                </div>
                <div className="grid items-end grid-cols-2 gap-3">
                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="goal-start">{t('goals.startDate')}</Label>
                    <DateInput
                      id="goal-start"
                      value={form.startDate}
                      onChange={(iso) => setForm((f) => ({ ...f, startDate: iso }))}
                    />
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="goal-end">{t('goals.endDate')}</Label>
                    <DateInput
                      id="goal-end"
                      value={form.endDate}
                      onChange={(iso) => setForm((f) => ({ ...f, endDate: iso }))}
                    />
                  </div>
                </div>
              </>
            )}

            <div className="flex flex-col gap-2">
              <Label>{t('goals.includedAccounts')}</Label>
              <div className="flex flex-col gap-2 max-h-40 overflow-y-auto">
                {(accounts ?? []).map((a) => (
                  <label
                    key={a.id}
                    className="flex items-center gap-2.5 cursor-pointer select-none"
                  >
                    <input
                      type="checkbox"
                      checked={form.accountIds.includes(a.id)}
                      onChange={() => toggleAccount(a.id)}
                      className="rounded accent-primary"
                    />
                    <span
                      className="w-2.5 h-2.5 rounded-full shrink-0"
                      style={{ background: a.color }}
                    />
                    <span className="text-sm flex-1">{a.name}</span>
                    <span className="text-xs text-muted-foreground">
                      <CurrencyDisplay value={a.currentBalanceEur} />
                    </span>
                  </label>
                ))}
              </div>
            </div>

            {form.type === 'RECURRING_INVESTMENT' && (
              <AllocationPicker
                accountId={form.accountIds[0] ?? null}
                monthlyAmount={monthlyAmountValue}
                allocations={form.allocations}
                onChange={(allocations) => setForm((f) => ({ ...f, allocations }))}
              />
            )}

            <DialogFooter>
              <Button type="button" variant="outline" onClick={closeForm}>
                {t('common.cancel')}
              </Button>
              <Button
                type="submit"
                disabled={
                  createGoal.isPending ||
                  updateGoal.isPending ||
                  form.accountIds.length === 0 ||
                  overAllocated ||
                  incompleteAllocations
                }
              >
                {(createGoal.isPending || updateGoal.isPending) && (
                  <CircleNotch
                    className="size-4 animate-spin" />
                )}
                {t('common.save')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Delete confirm dialog */}
      <ConfirmDialog
        open={deleteId != null}
        onOpenChange={(open) => { if (!open) setDeleteId(null) }}
        title={t('goals.deleteGoal')}
        description={t('goals.deleteGoal')}
        onConfirm={handleConfirmDelete}
        loading={deleteGoal.isPending}
        variant="destructive"
      />
    </div>
  )
}

/**
 * A recurring plan has no target, no deadline and no progress bar — showing an empty one would
 * be inventing a completion percentage for something with no end. It shows what goes in every
 * month, into which account, and what that account is worth today.
 */
function RecurringPlanCard({
  goal,
  onEdit,
  onDelete,
}: {
  goal: GoalProgress
  onEdit: () => void
  onDelete: () => void
}) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const account = goal.accounts[0]

  const monthlyAmount = goal.monthlyAmount ?? 0
  const detailed = goal.allocations.length > 0
  const allocated = goal.allocations.reduce((sum, a) => sum + a.monthlyAmount, 0)
  // Shown as its own row rather than left implicit: an incomplete split is a legitimate state,
  // and a breakdown that silently does not add up to the amount above it reads as a bug.
  const unallocated = monthlyAmount - allocated

  return (
    <Card className="py-3.5">
      <CardContent className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex min-w-0 items-center gap-2">
            {detailed && (
              <Button
                variant="ghost"
                size="icon"
                onClick={() => setOpen(o => !o)}
                aria-expanded={open}
                aria-label={t('goals.allocation.toggle')}
              >
                <CaretDown
                  className={cn('size-4 transition-transform', open && 'rotate-180')}
                />
              </Button>
            )}
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <span className="truncate font-medium text-foreground">{goal.name}</span>
                {account && <Badge variant="secondary">{account.name}</Badge>}
                {goal.endDate && (
                  <Badge variant="outline">
                    {t('goals.until', { date: formatDate(goal.endDate) })}
                  </Badge>
                )}
              </div>
              <p className="mt-1 text-sm text-muted-foreground">
                {t('common.labelColon', { label: t('goals.currentTotal') })} <CurrencyDisplay value={goal.currentTotal} />
              </p>
            </div>
          </div>

          <div className="flex items-center gap-4">
            <div className="text-right">
              <p className="mb-0.5 text-xs text-muted-foreground">{t('goals.monthlyAmount')}</p>
              <p className="text-sm font-semibold tabular-nums">
                <CurrencyDisplay value={monthlyAmount} />
              </p>
            </div>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button
                  variant="ghost"
                  size="icon"
                  className="size-8 text-muted-foreground hover:text-foreground"
                  aria-label={t('common.actions')}
                >
                  <DotsThree className="size-4" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuItem onSelect={onEdit}>
                  <PencilSimple className="size-4" aria-hidden="true" />
                  {t('common.edit')}
                </DropdownMenuItem>
                <DropdownMenuItem
                  className="text-destructive focus:text-destructive"
                  onSelect={onDelete}
                >
                  <Trash className="size-4" aria-hidden="true" />
                  {t('common.delete')}
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>

        {detailed && open && (
          <div className="flex flex-col gap-1.5 border-t pt-3">
            {goal.allocations.map((line) => (
              <div key={line.ticker} className="flex items-center justify-between gap-3 text-sm">
                <span className="flex min-w-0 items-center gap-2">
                  <span className="font-mono">{line.ticker}</span>
                  <span className="truncate text-muted-foreground">{line.name ?? line.ticker}</span>
                </span>
                <span className="flex shrink-0 items-center gap-3">
                  {/* A share, so it survives privacy mode; the amount beside it does not. */}
                  {monthlyAmount > 0 && (
                    <span className="text-muted-foreground tabular-nums">
                      {formatPercent((line.monthlyAmount / monthlyAmount) * 100, { fractionDigits: 0 })}
                    </span>
                  )}
                  <CurrencyDisplay value={line.monthlyAmount} className="font-medium" />
                </span>
              </div>
            ))}
            {unallocated > 0.005 && (
              <div className="flex items-center justify-between gap-3 text-sm text-muted-foreground">
                <span>{t('goals.allocation.unallocated')}</span>
                <CurrencyDisplay value={unallocated} />
              </div>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  )
}

interface GoalCardProps {
  goal: GoalProgress
  onOpenDetail: () => void
}

function GoalCard({ goal, onOpenDetail }: GoalCardProps) {
  const { t } = useTranslation()

  return (
    <Card
      role="button"
      tabIndex={0}
      className="cursor-pointer py-4 transition-colors hover:border-foreground/20 hover:bg-muted/20 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      onClick={onOpenDetail}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          onOpenDetail()
        }
      }}
    >
      <CardContent>
        {/* Header: goal name */}
        <div className="mb-2.5 flex items-center justify-between gap-2">
          <span className="cn-font-heading line-clamp-2 break-words text-sm font-semibold text-foreground">
            {goal.name}
          </span>
        </div>

        {/* Current total */}
        <CurrencyDisplay
          value={goal.currentTotal}
          className="text-3xl font-semibold tabular-nums"
        />

        {/* Progress bar */}
        <Progress
          value={goal.percentComplete}
          className="mt-3 [&_[data-slot=progress-indicator]]:bg-emerald-500"
        />

        {/* Footer: percent + target */}
        <div className="flex items-center justify-between mt-3">
          <span className="text-sm text-muted-foreground">
            {formatPercent(goal.percentComplete ?? 0, { fractionDigits: 0 })} {t('dashboard.achieved')}
          </span>
          <CurrencyDisplay
            value={goal.targetAmount ?? 0}
            className="text-sm font-medium tabular-nums"
          />
        </div>
      </CardContent>
    </Card>
  )
}
