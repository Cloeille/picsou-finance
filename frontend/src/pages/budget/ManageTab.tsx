import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Archive, ArchiveRestore, Pencil, Plus, Trash2 } from 'lucide-react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { Badge } from '@/components/ui/badge'
import { ColorPicker } from '@/components/shared/ColorPicker'
import { ErrorState } from '@/components/shared/ErrorState'
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
} from '@/features/budget/hooks'
import { formatDate, getLocale } from '@/lib/utils'
import type { AiCategorizationMode, Category, CategoryKind, RuleMatchType } from '@/types/api'
import { ColorDot } from './budget-utils'
import { KIND_META } from './budget-meta'

const KINDS: CategoryKind[] = ['INCOME', 'EXPENSE', 'TRANSFER']
const MATCH_TYPES: RuleMatchType[] = ['COUNTERPARTY', 'KEYWORD']
const AI_MODES: AiCategorizationMode[] = ['SUGGEST', 'AUTO_HIGH_CONFIDENCE', 'AUTO_ALL']
const SELECT_CLS =
  'flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus:border-ring'

// ─── Payday cycle ──────────────────────────────────────────────────────────

function CycleSettingsCard() {
  const { t } = useTranslation()
  const { data, isLoading, isError, refetch } = useBudgetSettings()
  const update = useUpdateBudgetSettings()
  const [day, setDay] = useState<number | ''>('')

  // Sync local state from the loaded value once, during render (no effect).
  const [synced, setSynced] = useState(false)
  if (!synced && data) {
    setSynced(true)
    setDay(data.cycleStartDay)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{t('budget.settings.cycleTitle')}</CardTitle>
        <CardDescription>{t('budget.settings.cycleHint')}</CardDescription>
      </CardHeader>
      <CardContent>
        {isLoading && <Skeleton className="h-9 w-full rounded-md" />}
        {!isLoading && isError && !data && (
          <ErrorState message={t('budget.settings.error')} onRetry={() => void refetch()} />
        )}
        {data && (
          <div className="flex flex-wrap items-end gap-3">
            <div className="space-y-2">
              <Label htmlFor="cycle-day">{t('budget.settings.cycleStartDay')}</Label>
              <Input id="cycle-day" type="number" min={1} max={28} className="w-28"
                value={day}
                onChange={(e) => setDay(e.target.value === '' ? '' : Number(e.target.value))} />
            </div>
            <Button disabled={update.isPending || day === '' || day < 1 || day > 28}
              onClick={() => day !== '' && update.mutate({
                cycleStartDay: Number(day),
                logoFetchEnabled: data.logoFetchEnabled,
                aiCategorizationEnabled: data.aiCategorizationEnabled,
                aiMode: data.aiMode,
                aiConfidenceThreshold: data.aiConfidenceThreshold,
              })}>
              {update.isPending ? t('common.loading') : t('common.save')}
            </Button>
            <p className="w-full text-xs text-muted-foreground">
              {t('budget.settings.currentCycle', {
                start: formatDate(data.currentCycleStart, getLocale()),
                end: formatDate(data.currentCycleEnd, getLocale()),
              })}
            </p>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

// ─── Brand logos (opt-in) ────────────────────────────────────────────────────

function LogoSettingsCard() {
  const { t } = useTranslation()
  const { data, isLoading, isError, refetch } = useBudgetSettings()
  const update = useUpdateBudgetSettings()

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{t('budget.settings.logoTitle')}</CardTitle>
        <CardDescription>{t('budget.settings.logoHint')}</CardDescription>
      </CardHeader>
      <CardContent>
        {isLoading && <Skeleton className="h-9 w-full rounded-md" />}
        {!isLoading && isError && !data && (
          <ErrorState message={t('budget.settings.error')} onRetry={() => void refetch()} />
        )}
        {data && (
          <div className="flex items-center justify-between gap-4">
            <Label htmlFor="logo-fetch" className="font-normal text-muted-foreground">
              {t('budget.settings.logoFetchLabel')}
            </Label>
            <Switch
              id="logo-fetch"
              checked={data.logoFetchEnabled}
              disabled={update.isPending}
              onCheckedChange={(checked) =>
                update.mutate({
                  cycleStartDay: data.cycleStartDay,
                  logoFetchEnabled: checked,
                  aiCategorizationEnabled: data.aiCategorizationEnabled,
                  aiMode: data.aiMode,
                  aiConfidenceThreshold: data.aiConfidenceThreshold,
                })
              }
            />
          </div>
        )}
      </CardContent>
    </Card>
  )
}

// ─── AI categorization (opt-in) ──────────────────────────────────────────────

function AiCategorizationCard() {
  const { t } = useTranslation()
  const { data, isLoading, isError, refetch } = useBudgetSettings()
  const update = useUpdateBudgetSettings()

  // The threshold slider needs live local feedback while dragging; sync it from the loaded
  // value once (during render, no effect) like CycleSettingsCard does, then commit on release.
  const [threshold, setThreshold] = useState(75)
  const [synced, setSynced] = useState(false)
  if (!synced && data) {
    setSynced(true)
    setThreshold(data.aiConfidenceThreshold)
  }

  // Each control sends the whole settings object (the backend replaces it wholesale), so fold the
  // single changed field over the current values.
  function save(patch: {
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
      <CardHeader>
        <CardTitle className="text-base">{t('budget.settings.aiTitle')}</CardTitle>
        <CardDescription>{t('budget.settings.aiHint')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {isLoading && <Skeleton className="h-9 w-full rounded-md" />}
        {!isLoading && isError && !data && (
          <ErrorState message={t('budget.settings.error')} onRetry={() => void refetch()} />
        )}
        {data && (
          <>
            <div className="flex items-center justify-between gap-4">
              <Label htmlFor="ai-enabled" className="font-normal text-muted-foreground">
                {t('budget.settings.aiEnableLabel')}
              </Label>
              <Switch
                id="ai-enabled"
                checked={data.aiCategorizationEnabled}
                disabled={update.isPending}
                onCheckedChange={(checked) => save({ aiCategorizationEnabled: checked })}
              />
            </div>

            {data.aiCategorizationEnabled && (
              <>
                <div className="space-y-2">
                  <Label htmlFor="ai-mode">{t('budget.settings.aiModeLabel')}</Label>
                  <select
                    id="ai-mode"
                    value={data.aiMode}
                    disabled={update.isPending}
                    onChange={(e) => save({ aiMode: e.target.value as AiCategorizationMode })}
                    className={SELECT_CLS}
                  >
                    {AI_MODES.map((m) => (
                      <option key={m} value={m}>{t(`budget.settings.aiModeOption.${m}`)}</option>
                    ))}
                  </select>
                  <p className="text-xs text-muted-foreground">
                    {t(`budget.settings.aiModeHint.${data.aiMode}`)}
                  </p>
                </div>

                {data.aiMode === 'AUTO_HIGH_CONFIDENCE' && (
                  <div className="space-y-2">
                    <div className="flex items-center justify-between">
                      <Label htmlFor="ai-threshold">{t('budget.settings.aiThresholdLabel')}</Label>
                      <span className="text-sm tabular-nums text-muted-foreground">{threshold}%</span>
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
                      className="w-full accent-primary"
                    />
                    <p className="text-xs text-muted-foreground">
                      {t('budget.settings.aiThresholdHint')}
                    </p>
                  </div>
                )}
              </>
            )}
          </>
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

  // A category that already has sub-categories can't itself become one (the backend enforces a
  // single level of nesting), so its parent selector is locked. Eligible parents are top-level
  // categories of the same kind, not archived, and not the category being edited.
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
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>
            {editing ? t('budget.category.edit') : t('budget.category.add')}
          </DialogTitle>
          <DialogDescription>{t('budget.category.formHint')}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="cat-name">{t('budget.category.name')}</Label>
            <Input id="cat-name" value={name} onChange={(e) => setName(e.target.value)}
              placeholder={t('budget.category.namePlaceholder')} />
          </div>
          <div className="space-y-2">
            <Label htmlFor="cat-kind">{t('budget.category.kind')}</Label>
            <select id="cat-kind" value={kind} disabled={editing != null}
              onChange={(e) => {
                setKind(e.target.value as CategoryKind)
                setParentId('') // a parent must share the child's kind — drop a now-invalid choice
              }}
              className={`${SELECT_CLS} disabled:opacity-60`}>
              {KINDS.map((k) => (
                <option key={k} value={k}>{t(KIND_META[k].labelKey)}</option>
              ))}
            </select>
          </div>
          <div className="space-y-2">
            <Label htmlFor="cat-parent">{t('budget.category.parent')}</Label>
            <select id="cat-parent" value={parentId} disabled={hasChildren}
              onChange={(e) => setParentId(e.target.value === '' ? '' : Number(e.target.value))}
              className={`${SELECT_CLS} disabled:opacity-60`}>
              <option value="">{t('budget.category.parentNone')}</option>
              {parentOptions.map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </select>
            <p className="text-xs text-muted-foreground">
              {hasChildren ? t('budget.category.parentLocked') : t('budget.category.parentHint')}
            </p>
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

  // The backend hands back a flat list; we fold it into a one-level tree for display — roots in
  // their given order, each immediately followed by its (indented) sub-categories. A single level
  // of nesting is guaranteed server-side, so there is no deeper recursion to handle.
  const roots = (categories ?? []).filter((c) => c.parentId == null)
  const childrenOf = (parentId: number) => (categories ?? []).filter((c) => c.parentId === parentId)

  function row(c: Category, child: boolean) {
    return (
      <div key={c.id} className={`flex items-center gap-3 py-2.5 ${child ? 'pl-5 sm:pl-7' : ''}`}>
        {child && <span aria-hidden className="-ml-2 text-muted-foreground/50">↳</span>}
        <ColorDot color={c.color} className="size-3 shrink-0 rounded-full" />
        <span className={`truncate ${c.archived ? 'text-muted-foreground line-through' : ''}`}>
          {c.name}
        </span>
        {child ? (
          <Badge variant="ghost" className="text-muted-foreground">{t('budget.category.subcategory')}</Badge>
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
        <div className="ml-auto flex items-center gap-1">
          <Button size="icon" variant="ghost" onClick={() => open(c)}
            aria-label={t('budget.a11y.edit', { name: c.name })}>
            <Pencil className="size-4" />
          </Button>
          {c.archived ? (
            <Button size="icon" variant="ghost"
              aria-label={t('budget.a11y.unarchive', { name: c.name })}
              disabled={unarchive.isPending} onClick={() => unarchive.mutate(c.id)}>
              <ArchiveRestore className="size-4" />
            </Button>
          ) : (
            <Button size="icon" variant="ghost"
              aria-label={t('budget.a11y.archive', { name: c.name })}
              disabled={archive.isPending} onClick={() => archive.mutate(c.id)}>
              <Archive className="size-4" />
            </Button>
          )}
        </div>
      </div>
    )
  }

  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between space-y-0">
        <div>
          <CardTitle className="text-base">{t('budget.category.title')}</CardTitle>
          <CardDescription>{t('budget.category.hint')}</CardDescription>
        </div>
        <Button size="sm" onClick={() => open(null)}>
          <Plus className="size-4" /> {t('budget.category.add')}
        </Button>
      </CardHeader>
      <CardContent>
        {isLoading && <Skeleton className="h-24 w-full rounded-xl" />}
        {!isLoading && isError && !categories && (
          <ErrorState message={t('budget.category.error')} onRetry={() => void refetch()} />
        )}
        {categories && (
          <div className="divide-y divide-border">
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

function RuleForm({ open, onOpenChange }: {
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { t } = useTranslation()
  const { data: categories } = useCategories()
  const createRule = useCreateRule()

  const [matchType, setMatchType] = useState<RuleMatchType>('COUNTERPARTY')
  const [pattern, setPattern] = useState('')
  const [categoryId, setCategoryId] = useState<number | ''>('')

  function submit() {
    if (pattern.trim() === '' || categoryId === '') return
    createRule.mutate(
      { matchType, pattern: pattern.trim(), categoryId: Number(categoryId) },
      { onSuccess: () => onOpenChange(false) },
    )
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{t('budget.rule.add')}</DialogTitle>
          <DialogDescription>{t('budget.rule.formHint')}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="rule-match">{t('budget.rule.matchType')}</Label>
            <select id="rule-match" value={matchType}
              onChange={(e) => setMatchType(e.target.value as RuleMatchType)} className={SELECT_CLS}>
              {MATCH_TYPES.map((m) => (
                <option key={m} value={m}>{t(`budget.rule.match.${m.toLowerCase()}`)}</option>
              ))}
            </select>
          </div>
          <div className="space-y-2">
            <Label htmlFor="rule-pattern">{t('budget.rule.pattern')}</Label>
            <Input id="rule-pattern" value={pattern} onChange={(e) => setPattern(e.target.value)}
              placeholder="NETFLIX" />
          </div>
          <div className="space-y-2">
            <Label htmlFor="rule-cat">{t('budget.rule.category')}</Label>
            <select id="rule-cat" value={categoryId}
              onChange={(e) => setCategoryId(e.target.value === '' ? '' : Number(e.target.value))}
              className={SELECT_CLS}>
              <option value="">{t('budget.categorize.selectCategory')}</option>
              {(categories ?? []).filter((c) => !c.archived).map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </select>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={createRule.isPending}>
            {t('common.cancel')}
          </Button>
          <Button onClick={submit}
            disabled={createRule.isPending || pattern.trim() === '' || categoryId === ''}>
            {createRule.isPending ? t('common.loading') : t('common.save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function RulesCard() {
  const { t } = useTranslation()
  const { data: rules, isLoading, isError, refetch } = useRules()
  const deleteRule = useDeleteRule()
  const [formOpen, setFormOpen] = useState(false)

  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between space-y-0">
        <div>
          <CardTitle className="text-base">{t('budget.rule.title')}</CardTitle>
          <CardDescription>{t('budget.rule.hint')}</CardDescription>
        </div>
        <Button size="sm" onClick={() => setFormOpen(true)}>
          <Plus className="size-4" /> {t('budget.rule.add')}
        </Button>
      </CardHeader>
      <CardContent>
        {isLoading && <Skeleton className="h-20 w-full rounded-xl" />}
        {!isLoading && isError && !rules && (
          <ErrorState message={t('budget.rule.error')} onRetry={() => void refetch()} />
        )}
        {!isLoading && !isError && (rules?.length ?? 0) === 0 && (
          <p className="py-4 text-center text-sm text-muted-foreground">{t('budget.rule.empty')}</p>
        )}
        {rules && rules.length > 0 && (
          <div className="divide-y divide-border">
            {rules.map((r) => (
              <div key={r.id} className="flex items-center gap-3 py-2.5 text-sm">
                <Badge variant="outline">{t(`budget.rule.match.${r.matchType.toLowerCase()}`)}</Badge>
                <span className="truncate font-mono text-xs">{r.pattern}</span>
                <span className="text-muted-foreground">→</span>
                <span className="truncate">{r.categoryName}</span>
                {r.source === 'AUTO' && (
                  <Badge variant="ghost" className="text-muted-foreground">
                    {t('budget.rule.learned')}
                  </Badge>
                )}
                <Button size="icon" variant="ghost" className="ml-auto"
                  aria-label={t('budget.a11y.delete', { name: r.pattern })}
                  disabled={deleteRule.isPending} onClick={() => deleteRule.mutate(r.id)}>
                  <Trash2 className="size-4" />
                </Button>
              </div>
            ))}
          </div>
        )}
      </CardContent>
      <RuleForm open={formOpen} onOpenChange={setFormOpen} />
    </Card>
  )
}

export function ManageTab() {
  return (
    <div className="space-y-4">
      <CycleSettingsCard />
      <LogoSettingsCard />
      <AiCategorizationCard />
      <CategoriesCard />
      <RulesCard />
    </div>
  )
}
