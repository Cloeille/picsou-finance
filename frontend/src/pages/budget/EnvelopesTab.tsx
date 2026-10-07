import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Plus, Pencil, Trash2, Wallet } from 'lucide-react'
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
  DialogDescription,
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
import { parseAmount } from '@/lib/utils'
import type { Budget } from '@/types/api'
import { ColorDot } from './budget-utils'

function EnvelopeForm({
  open,
  onOpenChange,
  editing,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  editing: Budget | null
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
    .filter((c) => editing != null ? c.id === editing.categoryId : !budgetedIds.has(c.id))

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
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>
            {editing ? t('budget.envelope.edit') : t('budget.envelope.add')}
          </DialogTitle>
          <DialogDescription>{t('budget.envelope.formHint')}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="env-cat">{t('budget.envelope.category')}</Label>
            <select
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
            </select>
          </div>
          <div className="space-y-2">
            <Label htmlFor="env-limit">{t('budget.envelope.monthlyLimit')}</Label>
            <NumericInput id="env-limit" value={limit}
              onChange={(e) => setLimit(e.target.value)} placeholder="400" />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={pending}>
            {t('common.cancel')}
          </Button>
          <Button onClick={submit} disabled={pending || categoryId === '' || limit.trim() === ''}>
            {pending ? t('common.loading') : t('common.save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function EnvelopeRow({ budget, onEdit, onDelete }: {
  budget: Budget
  onEdit: () => void
  onDelete: () => void
}) {
  const { t } = useTranslation()
  const pct = Math.min(budget.percent, 100)
  const barTone = budget.overBudget
    ? 'bg-destructive'
    : budget.percent >= 80 ? 'bg-amber-500' : 'bg-primary'

  return (
    <Card>
      <CardContent className="py-4">
        <div className="flex items-center gap-3">
          <ColorDot color={budget.categoryColor} className="size-3 shrink-0 rounded-full" />
          <span className="truncate font-medium">{budget.categoryName}</span>
          {budget.rollup && (
            <Badge variant="ghost" className="shrink-0 text-muted-foreground">
              {t('budget.envelope.includesSubcategories')}
            </Badge>
          )}
          <div className="ml-auto flex items-center gap-1">
            <Button size="icon" variant="ghost" onClick={onEdit}
              aria-label={t('budget.a11y.edit', { name: budget.categoryName })}>
              <Pencil className="size-4" />
            </Button>
            <Button size="icon" variant="ghost" onClick={onDelete}
              aria-label={t('budget.a11y.delete', { name: budget.categoryName })}>
              <Trash2 className="size-4" />
            </Button>
          </div>
        </div>
        <div className="mt-3 h-2 w-full overflow-hidden rounded-md bg-muted"
          role="progressbar" aria-label={budget.categoryName}
          aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}
          aria-valuetext={`${budget.percent}%`}>
          <div className={`h-full rounded-md transition-all ${barTone}`}
            style={{ width: `${pct}%` }} />
        </div>
        <div className="mt-2 flex items-center justify-between text-sm">
          <span className="text-muted-foreground">
            <CurrencyDisplay value={budget.spent} /> / <CurrencyDisplay value={budget.monthlyLimit} />
          </span>
          <span className={budget.overBudget ? 'font-medium text-destructive' : 'text-muted-foreground'}>
            <CurrencyDisplay value={budget.remaining} showSign /> {budget.percent}%
          </span>
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
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted-foreground">{t('budget.envelope.subtitle')}</p>
        <Button size="sm" onClick={openAdd}>
          <Plus className="size-4" /> {t('budget.envelope.add')}
        </Button>
      </div>

      {isError && <ErrorState message={t('budget.envelope.error')} onRetry={() => refetch()} />}

      {isLoading && (
        <div className="space-y-3">
          <Skeleton className="h-24 w-full rounded-xl" />
          <Skeleton className="h-24 w-full rounded-xl" />
        </div>
      )}

      {budgets && budgets.length === 0 && (
        <EmptyState icon={<Wallet className="size-10" />}
          title={t('budget.envelope.empty')}
          description={t('budget.envelope.emptyHint')}
          action={{ label: t('budget.envelope.add'), onClick: openAdd }} />
      )}

      {budgets && budgets.length > 0 && (
        <div className="space-y-3">
          {budgets.map((b) => (
            <EnvelopeRow key={b.id} budget={b}
              onEdit={() => openEdit(b)} onDelete={() => setDeleting(b)} />
          ))}
        </div>
      )}

      <EnvelopeForm key={formKey} open={formOpen} onOpenChange={setFormOpen} editing={editing} />

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
