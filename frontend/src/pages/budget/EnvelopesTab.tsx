import { useState } from 'react'
import { NativeSelect } from '@/components/shared/NativeSelect'
import { useTranslation } from 'react-i18next'
import { Plus, Trash, Wallet } from '@phosphor-icons/react'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Label } from '@/components/ui/label'
import { NumericInput } from '@/components/shared/NumericInput'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { EmptyState } from '@/components/shared/EmptyState'
import { ErrorState } from '@/components/shared/ErrorState'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  useBudgets,
  useCategories,
  useCreateBudget,
  useDeleteBudget,
  useUpdateBudget,
} from '@/features/budget/hooks'
import { cn, formatPercent, parseAmount } from '@/lib/utils'
import { useMoney } from '@/hooks/use-money'
import type { Budget } from '@/types/api'
import { ColorDot } from './budget-utils'

function EnvelopeForm({
  open,
  onOpenChange,
  editing,
  onDelete,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  editing: Budget | null
  onDelete?: () => void
}) {
  const { t } = useTranslation()
  const { data: categories } = useCategories()
  const { data: budgets } = useBudgets()
  const createBudget = useCreateBudget()
  const updateBudget = useUpdateBudget()

  // Lazy init keeps these correct on (re)open via the key-remount below.
  const [categoryId, setCategoryId] = useState<number | ''>(editing?.categoryId ?? '')
  const [limit, setLimit] = useState<string>(editing ? String(editing.monthlyLimit) : '')

  // Only expense categories can hold an envelope, and (when creating) only those
  // not already budgeted.
  const budgetedIds = new Set((budgets ?? []).map((b) => b.categoryId))
  const options = (categories ?? [])
    .filter((c) => c.kind === 'EXPENSE' && !c.archived)
    .filter((c) => (editing != null ? c.id === editing.categoryId : !budgetedIds.has(c.id)))

  const pending = createBudget.isPending || updateBudget.isPending

  function submit() {
    if (categoryId === '' || limit.trim() === '') return
    const payload = { categoryId: Number(categoryId), monthlyLimit: parseAmount(limit) }
    const onDone = { onSuccess: () => onOpenChange(false) }
    if (editing) updateBudget.mutate({ id: editing.id, data: payload }, onDone)
    else createBudget.mutate(payload, onDone)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>
            {editing ? t('budget.envelope.edit') : t('budget.envelope.add')}
          </DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="env-cat">{t('budget.envelope.category')}</Label>
            <NativeSelect
              id="env-cat"
              value={categoryId}
              disabled={editing != null}
              onChange={(e) => setCategoryId(e.target.value === '' ? '' : Number(e.target.value))}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus:border-ring disabled:opacity-60"
            >
              <option value="">{t('budget.envelope.selectCategory')}</option>
              {options.map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </NativeSelect>
          </div>
          <div className="space-y-2">
            <Label htmlFor="env-limit">{t('budget.envelope.monthlyLimit')}</Label>
            <NumericInput
              id="env-limit"
              value={limit}
              onChange={(e) => setLimit(e.target.value)}
              placeholder="400"
            />
          </div>
        </div>
        <DialogFooter className="gap-2 sm:justify-between">
          {editing && onDelete ? (
            <Button
              type="button"
              variant="ghost"
              className="text-destructive hover:text-destructive sm:mr-auto"
              onClick={onDelete}
              disabled={pending}
            >
              <Trash className="size-4" aria-hidden="true" />
              {t('common.delete')}
            </Button>
          ) : (
            <span />
          )}
          <div className="flex items-center justify-end gap-2">
            <Button variant="outline" onClick={() => onOpenChange(false)} disabled={pending}>
              {t('common.cancel')}
            </Button>
            <Button onClick={submit} disabled={pending || categoryId === '' || limit.trim() === ''}>
              {pending ? t('common.loading') : t('common.save')}
            </Button>
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function EnvelopeRow({
  budget,
  onEdit,
}: {
  budget: Budget
  onEdit: () => void
}) {
  const { t } = useTranslation()
  const money = useMoney()
  const pct = Math.min(budget.percent, 100)
  const percent = formatPercent(budget.percent, { fractionDigits: 0 })
  const barTone = budget.overBudget
    ? 'bg-destructive'
    : budget.percent >= 80
      ? 'bg-foreground/75'
      : 'bg-primary'

  return (
    <Card
      role="button"
      tabIndex={0}
      aria-label={t('budget.a11y.edit', { name: budget.categoryName })}
      onClick={onEdit}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          onEdit()
        }
      }}
      className="cursor-pointer py-3.5 transition-colors hover:border-foreground/20 hover:bg-muted/20 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
    >
      <CardContent className="space-y-2.5">
        <div className="flex items-center justify-between gap-3">
          <div className="flex min-w-0 items-center gap-2.5">
            <ColorDot color={budget.categoryColor} className="size-2.5 shrink-0 rounded-full" />
            <span className="truncate text-sm font-medium text-foreground">
              {budget.categoryName}
            </span>
          </div>
          <span
            className={cn(
              'shrink-0 text-sm tabular-nums',
              budget.overBudget ? 'font-medium text-destructive' : 'text-muted-foreground',
            )}
          >
            {t(budget.overBudget ? 'budget.envelope.overspent' : 'budget.envelope.remaining', {
              amount: money.amount(Math.abs(budget.remaining)),
              percent,
            })}
          </span>
        </div>

        <div
          className="h-2 w-full overflow-hidden rounded-full bg-muted"
          role="progressbar"
          aria-label={budget.categoryName}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={pct}
          aria-valuetext={percent}
        >
          <div
            className={`h-full rounded-full transition-all ${barTone}`}
            style={{ width: `${pct}%` }}
          />
        </div>

        <div className="flex items-center justify-between gap-2 text-sm">
          <span className="text-muted-foreground tabular-nums">
            <CurrencyDisplay value={budget.spent} /> / <CurrencyDisplay value={budget.monthlyLimit} />
          </span>
          {budget.rollup && (
            <Badge variant="secondary" className="shrink-0 text-xs font-normal">
              {t('budget.envelope.includesSubcategories')}
            </Badge>
          )}
        </div>
      </CardContent>
    </Card>
  )
}

export function EnvelopesTab() {
  const { t } = useTranslation()
  const { data: budgets, isLoading, isError, refetch } = useBudgets()
  const deleteBudget = useDeleteBudget()

  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<Budget | null>(null)
  const [deleting, setDeleting] = useState<Budget | null>(null)
  // Remount the form on each open/target change so its local state re-initialises.
  const [formKey, setFormKey] = useState(0)

  function openAdd() {
    setEditing(null)
    setFormKey((k) => k + 1)
    setFormOpen(true)
  }
  function openEdit(b: Budget) {
    setEditing(b)
    setFormKey((k) => k + 1)
    setFormOpen(true)
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-base font-semibold text-foreground">{t('budget.tab.envelopes')}</h2>
        <Button size="sm" onClick={openAdd}>
          <Plus className="size-4" />
          {t('budget.envelope.add')}
        </Button>
      </div>

      {isError && <ErrorState message={t('budget.envelope.error')} onRetry={() => refetch()} />}

      {isLoading && (
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
          <Skeleton className="h-24 w-full rounded-xl" />
          <Skeleton className="h-24 w-full rounded-xl" />
        </div>
      )}

      {budgets && budgets.length === 0 && (
        <Card>
          <CardContent>
            <EmptyState
              className="py-8"
              icon={<Wallet className="size-10" />}
              title={t('budget.envelope.empty')}
              description={t('budget.envelope.emptyHint')}
            />
          </CardContent>
        </Card>
      )}

      {budgets && budgets.length > 0 && (
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
          {budgets.map((b) => (
            <EnvelopeRow key={b.id} budget={b} onEdit={() => openEdit(b)} />
          ))}
        </div>
      )}

      <EnvelopeForm
        key={formKey}
        open={formOpen}
        onOpenChange={setFormOpen}
        editing={editing}
        onDelete={
          editing
            ? () => {
                setFormOpen(false)
                setDeleting(editing)
              }
            : undefined
        }
      />

      <ConfirmDialog
        open={deleting != null}
        onOpenChange={(o) => !o && setDeleting(null)}
        title={t('budget.envelope.deleteTitle')}
        description={t('budget.envelope.deleteDescription', { name: deleting?.categoryName ?? '' })}
        loading={deleteBudget.isPending}
        onConfirm={() => {
          if (deleting) deleteBudget.mutate(deleting.id, { onSuccess: () => setDeleting(null) })
        }}
      />
    </div>
  )
}
