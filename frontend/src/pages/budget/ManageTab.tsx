import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useLocation, useNavigate } from 'react-router'
import {
  Archive,
  ArrowCounterClockwise,
  CaretRight,
  ArrowElbowDownRight,
  PencilSimple,
  Plus,
  GearSix,
  Trash,
} from '@phosphor-icons/react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { Badge } from '@/components/ui/badge'
import { ColorPicker } from '@/components/shared/ColorPicker'
import { ErrorState } from '@/components/shared/ErrorState'
import { NativeSelect } from '@/components/shared/NativeSelect'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  useArchiveCategory,
  useBudgetSettings,
  useCategories,
  useCreateCategory,
  useCreateRule,
  useDeleteRule,
  useRules,
  useUnarchiveCategory,
  useUpdateBudgetSettings,
  useUpdateCategory,
  useUpdateRule,
} from '@/features/budget/hooks'
import { useAuthStore } from '@/stores/auth-store'
import { selectIsAdvancedMode, useAppStore } from '@/stores/app-store'
import { formatDate, formatPercent, getLocale } from '@/lib/utils'
import type { AiCategorizationMode, CategorizationRule, Category, CategoryKind, RuleMatchType } from '@/types/api'
import { ColorDot } from './budget-utils'
import { KIND_META } from './budget-meta'

const KINDS: CategoryKind[] = ['INCOME', 'EXPENSE', 'TRANSFER']
const MATCH_TYPES: RuleMatchType[] = ['COUNTERPARTY', 'KEYWORD']
const AI_MODES: AiCategorizationMode[] = ['SUGGEST', 'AUTO_HIGH_CONFIDENCE', 'AUTO_ALL']
const CYCLE_DAYS = Array.from({ length: 28 }, (_, i) => i + 1)

// ─── Unified budget preferences ──────────────────────────────────────────────

function BudgetPreferencesCard() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const isAdmin = useAuthStore((s) => s.user?.role === 'ADMIN')
  const isAdvancedMode = useAppStore(selectIsAdvancedMode)
  const { data, isLoading, isError, refetch } = useBudgetSettings()
  const update = useUpdateBudgetSettings()

  const [threshold, setThreshold] = useState(75)
  const [synced, setSynced] = useState(false)
  if (!synced && data) {
    setSynced(true)
    setThreshold(data.aiConfidenceThreshold)
  }

  function save(patch: {
    cycleStartDay?: number
    logoFetchEnabled?: boolean
    aiCategorizationEnabled?: boolean
    aiMode?: AiCategorizationMode
    aiConfidenceThreshold?: number
  }) {
    if (!data) return
    update.mutate({
      cycleStartDay: data.cycleStartDay,
      logoFetchEnabled: data.logoFetchEnabled,
      aiCategorizationEnabled: data.aiCategorizationEnabled,
      aiMode: data.aiMode,
      aiConfidenceThreshold: data.aiConfidenceThreshold,
      ...patch,
    })
  }

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-base">
          {t('settings.title')}
        </CardTitle>
      </CardHeader>
      <CardContent>
        {isLoading && <Skeleton className="h-36 w-full rounded-xl" />}
        {!isLoading && isError && !data && (
          <ErrorState message={t('budget.settings.error')} onRetry={() => void refetch()} />
        )}
        {data && (
          <div className="divide-y divide-border/60">
            {/* Cycle start day */}
            <div className="flex flex-col gap-3 py-4 first:pt-2 sm:flex-row sm:items-center sm:justify-between">
              <div className="space-y-0.5">
                <Label htmlFor="cycle-day" className="text-sm font-medium text-foreground">
                  {t('budget.settings.cycleStartDay')}
                </Label>
                <p className="text-sm tabular-nums text-muted-foreground">
                  {t('budget.settings.currentCycle', {
                    start: formatDate(data.currentCycleStart, getLocale()),
                    end: formatDate(data.currentCycleEnd, getLocale()),
                  })}
                </p>
              </div>
              <div className="w-24 shrink-0">
                <NativeSelect
                  id="cycle-day"
                  value={data.cycleStartDay}
                  disabled={update.isPending}
                  onChange={(e) => save({ cycleStartDay: Number(e.target.value) })}
                >
                  {CYCLE_DAYS.map((d) => (
                    <option key={d} value={d}>{d}</option>
                  ))}
                </NativeSelect>
              </div>
            </div>

            {/* Brand logos */}
            <div className="flex items-center justify-between gap-4 py-4">
              <Label htmlFor="logo-fetch" className="text-sm font-medium text-foreground">
                {t('budget.settings.logoTitle')}
              </Label>
              <Switch
                id="logo-fetch"
                className="shrink-0 dark:data-unchecked:bg-muted-foreground/50"
                checked={data.logoFetchEnabled}
                disabled={update.isPending}
                onCheckedChange={(checked) => save({ logoFetchEnabled: checked })}
              />
            </div>

            {/* AI categorization */}
            <div className="py-4 last:pb-1">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <Label htmlFor="ai-enabled" className="text-sm font-medium text-foreground">
                  {t('budget.settings.aiTitle')}
                </Label>
                <div className="flex shrink-0 items-center gap-3">
                  {isAdmin && isAdvancedMode && data.aiCategorizationEnabled && (
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      onClick={() => navigate('/admin?tab=ai')}
                    >
                      <GearSix className="size-3.5" aria-hidden="true" />
                      {t('budget.categorize.aiSettings')}
                    </Button>
                  )}
                  <Switch
                    id="ai-enabled"
                    aria-label={t('budget.settings.aiEnableLabel')}
                    className="shrink-0"
                    checked={data.aiCategorizationEnabled}
                    disabled={update.isPending}
                    onCheckedChange={(checked) => save({ aiCategorizationEnabled: checked })}
                  />
                </div>
              </div>

              {data.aiCategorizationEnabled && (
                <div
                  className={
                    data.aiMode === 'AUTO_HIGH_CONFIDENCE'
                      ? 'mt-4 grid gap-4 rounded-xl bg-muted/35 p-4 sm:grid-cols-2 sm:items-end'
                      : 'mt-4 max-w-md rounded-xl bg-muted/35 p-4'
                  }
                >
                  <div className="space-y-1.5">
                    <Label htmlFor="ai-mode">{t('budget.settings.aiModeLabel')}</Label>
                    <NativeSelect
                      id="ai-mode"
                      value={data.aiMode}
                      disabled={update.isPending}
                      onChange={(e) => save({ aiMode: e.target.value as AiCategorizationMode })}
                    >
                      {AI_MODES.map((m) => (
                        <option key={m} value={m}>{t(`budget.settings.aiModeOption.${m}`)}</option>
                      ))}
                    </NativeSelect>
                  </div>

                  {data.aiMode === 'AUTO_HIGH_CONFIDENCE' && (
                    <div className="space-y-2.5 pb-1">
                      <div className="flex items-center justify-between gap-2">
                        <Label htmlFor="ai-threshold">{t('budget.settings.aiThresholdLabel')}</Label>
                        <Badge variant="outline" className="bg-background tabular-nums">
                          {formatPercent(threshold, { fractionDigits: 0 })}
                        </Badge>
                      </div>
                      <input
                        id="ai-threshold"
                        type="range"
                        min={0}
                        max={100}
                        step={5}
                        value={threshold}
                        disabled={update.isPending}
                        onChange={(e) => setThreshold(Number(e.target.value))}
                        onPointerUp={() => save({ aiConfidenceThreshold: threshold })}
                        onKeyUp={() => save({ aiConfidenceThreshold: threshold })}
                        onBlur={() => save({ aiConfidenceThreshold: threshold })}
                        className="range-input w-full"
                        style={{ '--range-fill': `${threshold}%` } as React.CSSProperties}
                      />
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

// ─── Category form ───────────────────────────────────────────────────────────

function CategoryForm({ open, onOpenChange, editing }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  editing: Category | null
}) {
  const { t } = useTranslation()
  const { data: categories } = useCategories()
  const createCategory = useCreateCategory()
  const updateCategory = useUpdateCategory()

  const [name, setName] = useState(editing?.name ?? '')
  const [kind, setKind] = useState<CategoryKind>(editing?.kind ?? 'EXPENSE')
  const [color, setColor] = useState(editing?.color ?? '#6366f1')
  const [parentId, setParentId] = useState<number | ''>(editing?.parentId ?? '')
  const pending = createCategory.isPending || updateCategory.isPending

  const hasChildren = (categories ?? []).some((c) => c.parentId === editing?.id)
  const parentOptions = (categories ?? []).filter(
    (c) => c.parentId == null && c.kind === kind && !c.archived && c.id !== editing?.id,
  )

  function submit() {
    if (name.trim() === '') return
    const payload = { name: name.trim(), kind, color, parentId: parentId === '' ? null : Number(parentId) }
    const onDone = { onSuccess: () => onOpenChange(false) }
    if (editing) updateCategory.mutate({ id: editing.id, data: payload }, onDone)
    else createCategory.mutate(payload, onDone)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>
            {editing ? t('budget.category.edit') : t('budget.category.add')}
          </DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="cat-name">{t('budget.category.name')}</Label>
            <Input
              id="cat-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={t('budget.category.namePlaceholder')}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="cat-kind">{t('budget.category.kind')}</Label>
            <NativeSelect
              id="cat-kind"
              value={kind}
              disabled={editing != null}
              onChange={(e) => {
                setKind(e.target.value as CategoryKind)
                setParentId('')
              }}
            >
              {KINDS.map((k) => (
                <option key={k} value={k}>{t(KIND_META[k].labelKey)}</option>
              ))}
            </NativeSelect>
          </div>
          <div className="space-y-2">
            <Label htmlFor="cat-parent">{t('budget.category.parent')}</Label>
            <NativeSelect
              id="cat-parent"
              value={parentId}
              disabled={hasChildren}
              onChange={(e) => setParentId(e.target.value === '' ? '' : Number(e.target.value))}
            >
              <option value="">{t('budget.category.parentNone')}</option>
              {parentOptions.map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </NativeSelect>
            {hasChildren && (
              <p className="text-sm text-muted-foreground">
                {t('budget.category.parentLocked')}
              </p>
            )}
          </div>
          <div className="space-y-2">
            <Label>{t('budget.category.color')}</Label>
            <ColorPicker value={color} onChange={setColor} />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={pending}>
            {t('common.cancel')}
          </Button>
          <Button onClick={submit} disabled={pending || name.trim() === ''}>
            {pending ? t('common.loading') : t('common.save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function CategoriesCard() {
  const { t } = useTranslation()
  const { data: categories, isLoading, isError, refetch } = useCategories()
  const archive = useArchiveCategory()
  const unarchive = useUnarchiveCategory()

  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<Category | null>(null)
  const [formKey, setFormKey] = useState(0)

  function open(cat: Category | null) {
    setEditing(cat)
    setFormKey((k) => k + 1)
    setFormOpen(true)
  }

  const roots = (categories ?? []).filter((c) => c.parentId == null)
  const childrenOf = (parentId: number) => (categories ?? []).filter((c) => c.parentId === parentId)

  function row(c: Category, child: boolean) {
    return (
      <div
        key={c.id}
        className={`flex items-center gap-3 py-2.5 transition-colors hover:bg-muted/25 ${child ? 'pl-6 sm:pl-8' : ''}`}
      >
        {child && <ArrowElbowDownRight className="-ml-2 size-3.5 shrink-0 text-muted-foreground/60" aria-hidden="true" />}
        <ColorDot color={c.color} className="size-3 shrink-0 rounded-full" />
        <div className="flex min-w-0 flex-1 flex-wrap items-center gap-x-2.5 gap-y-1">
          <span className={`max-w-full truncate text-sm font-medium ${c.archived ? 'text-muted-foreground line-through' : 'text-foreground'}`}>
            {c.name}
          </span>
          {child ? (
            <Badge variant="secondary" className="text-muted-foreground">{t('budget.category.subcategory')}</Badge>
          ) : (
            <Badge variant="outline" className={KIND_META[c.kind].tone}>
              {t(KIND_META[c.kind].labelKey)}
            </Badge>
          )}
          {c.isDefault && (
            <Badge variant="ghost" className="text-muted-foreground">
              {t('budget.category.default')}
            </Badge>
          )}
        </div>
        <div className="flex shrink-0 items-center gap-1">
          <Button
            size="icon-sm"
            variant="ghost"
            onClick={() => open(c)}
            aria-label={t('budget.a11y.edit', { name: c.name })}
          >
            <PencilSimple className="size-4" />
          </Button>
          {c.archived ? (
            <Button
              size="icon-sm"
              variant="ghost"
              aria-label={t('budget.a11y.unarchive', { name: c.name })}
              disabled={unarchive.isPending}
              onClick={() => unarchive.mutate(c.id)}
            >
              <ArrowCounterClockwise className="size-4" />
            </Button>
          ) : (
            <Button
              size="icon-sm"
              variant="ghost"
              aria-label={t('budget.a11y.archive', { name: c.name })}
              disabled={archive.isPending}
              onClick={() => archive.mutate(c.id)}
            >
              <Archive className="size-4" />
            </Button>
          )}
        </div>
      </div>
    )
  }

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between gap-4 space-y-0">
        <CardTitle className="flex items-center gap-2 text-base">
          {t('budget.category.title')}
          {categories && (
            <Badge variant="secondary" className="tabular-nums">{categories.length}</Badge>
          )}
        </CardTitle>
        <Button size="sm" onClick={() => open(null)}>
          <Plus className="size-4" /> {t('budget.category.add')}
        </Button>
      </CardHeader>
      <CardContent>
        {isLoading && <Skeleton className="h-32 w-full rounded-xl" />}
        {!isLoading && isError && !categories && (
          <ErrorState message={t('budget.category.error')} onRetry={() => void refetch()} />
        )}
        {categories && (
          <div className="divide-y divide-border/60">
            {roots.map((r) => (
              <div key={r.id} className="divide-y divide-border/40">
                {row(r, false)}
                {childrenOf(r.id).map((c) => row(c, true))}
              </div>
            ))}
          </div>
        )}
      </CardContent>
      <CategoryForm key={formKey} open={formOpen} onOpenChange={setFormOpen} editing={editing} />
    </Card>
  )
}

// ─── Rules ───────────────────────────────────────────────────────────────────

function RuleForm({ open, onOpenChange, editing }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  editing: CategorizationRule | null
}) {
  const { t } = useTranslation()
  const { data: categories } = useCategories()
  const createRule = useCreateRule()
  const updateRule = useUpdateRule()

  const [matchType, setMatchType] = useState<RuleMatchType>(editing?.matchType ?? 'COUNTERPARTY')
  const [pattern, setPattern] = useState(editing?.pattern ?? '')
  const [categoryId, setCategoryId] = useState<number | ''>(editing?.categoryId ?? '')
  const pending = createRule.isPending || updateRule.isPending
  // The multi-word types come from the review picker: listed only to edit a rule that has one.
  const matchTypes = editing && !MATCH_TYPES.includes(editing.matchType)
    ? [...MATCH_TYPES, editing.matchType]
    : MATCH_TYPES

  function submit() {
    if (pattern.trim() === '' || categoryId === '') return
    const payload = { matchType, pattern: pattern.trim(), categoryId: Number(categoryId) }
    const onDone = { onSuccess: () => onOpenChange(false) }
    if (editing) updateRule.mutate({ id: editing.id, data: payload }, onDone)
    else createRule.mutate(payload, onDone)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>
            {editing ? t('budget.rule.edit') : t('budget.rule.add')}
          </DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="rule-match">{t('budget.rule.matchType')}</Label>
            <NativeSelect
              id="rule-match"
              value={matchType}
              onChange={(e) => setMatchType(e.target.value as RuleMatchType)}
            >
              {matchTypes.map((m) => (
                <option key={m} value={m}>{t(`budget.rule.match.${m.toLowerCase()}`)}</option>
              ))}
            </NativeSelect>
          </div>
          <div className="space-y-2">
            <Label htmlFor="rule-pattern">{t('budget.rule.pattern')}</Label>
            <Input
              id="rule-pattern"
              value={pattern}
              onChange={(e) => setPattern(e.target.value)}
              placeholder="NETFLIX"
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="rule-cat">{t('budget.rule.category')}</Label>
            <NativeSelect
              id="rule-cat"
              value={categoryId}
              onChange={(e) => setCategoryId(e.target.value === '' ? '' : Number(e.target.value))}
            >
              <option value="">{t('budget.categorize.selectCategory')}</option>
              {(categories ?? []).filter((c) => !c.archived || c.id === editing?.categoryId).map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </NativeSelect>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={pending}>
            {t('common.cancel')}
          </Button>
          <Button
            onClick={submit}
            disabled={pending || pattern.trim() === '' || categoryId === ''}
          >
            {pending ? t('common.loading') : t('common.save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function RulesCard() {
  const { t } = useTranslation()
  const { hash } = useLocation()
  const { data: rules, isLoading, isError, refetch } = useRules()
  const deleteRule = useDeleteRule()
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<CategorizationRule | null>(null)
  const [formKey, setFormKey] = useState(0)
  const cardRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (hash === '#rules') {
      cardRef.current?.scrollIntoView({ block: 'start' })
    }
  }, [hash])

  function open(rule: CategorizationRule | null) {
    setEditing(rule)
    setFormKey((k) => k + 1)
    setFormOpen(true)
  }

  return (
    <Card id="rules" ref={cardRef} className="scroll-mt-4">
      <CardHeader className="flex flex-row items-center justify-between gap-4 space-y-0">
        <CardTitle className="flex items-center gap-2 text-base">
          {t('budget.rule.title')}
          {rules && rules.length > 0 && (
            <Badge variant="secondary" className="tabular-nums">{rules.length}</Badge>
          )}
        </CardTitle>
        <Button size="sm" onClick={() => open(null)}>
          <Plus className="size-4" /> {t('budget.rule.add')}
        </Button>
      </CardHeader>
      <CardContent>
        {isLoading && <Skeleton className="h-24 w-full rounded-xl" />}
        {!isLoading && isError && !rules && (
          <ErrorState message={t('budget.rule.error')} onRetry={() => void refetch()} />
        )}
        {!isLoading && !isError && (rules?.length ?? 0) === 0 && (
          <p className="py-6 text-center text-sm text-muted-foreground">
            {t('budget.rule.empty')}
          </p>
        )}
        {rules && rules.length > 0 && (
          <div className="divide-y divide-border/60">
            {rules.map((r) => (
              <div key={r.id} className="flex items-center justify-between gap-3 py-3 text-sm">
                <div className="flex min-w-0 flex-1 flex-wrap items-center gap-2">
                  <Badge variant="outline">{t(`budget.rule.match.${r.matchType.toLowerCase()}`)}</Badge>
                  <span
                    className="max-w-full truncate rounded-md bg-muted/60 px-2 py-0.5 text-xs font-medium text-foreground"
                    title={r.pattern}
                  >
                    {r.pattern}
                  </span>
                  <CaretRight className="size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
                  <span className="truncate font-medium text-foreground">{r.categoryName}</span>
                  {r.source === 'AUTO' && (
                    <Badge variant="secondary" className="h-5 px-1.5 text-xs">
                      {t('budget.rule.learned')}
                    </Badge>
                  )}
                </div>
                <div className="flex shrink-0 items-center gap-1">
                  <Button
                    size="icon-sm"
                    variant="ghost"
                    onClick={() => open(r)}
                    aria-label={t('budget.a11y.edit', { name: r.pattern })}
                  >
                    <PencilSimple className="size-4" />
                  </Button>
                  <Button
                    size="icon-sm"
                    variant="ghost"
                    aria-label={t('budget.a11y.delete', { name: r.pattern })}
                    disabled={deleteRule.isPending}
                    onClick={() => deleteRule.mutate(r.id)}
                  >
                    <Trash className="size-4" />
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </CardContent>
      <RuleForm key={formKey} open={formOpen} onOpenChange={setFormOpen} editing={editing} />
    </Card>
  )
}

export function ManageTab() {
  return (
    <div className="space-y-6">
      <BudgetPreferencesCard />
      <RulesCard />
      <CategoriesCard />
    </div>
  )
}
