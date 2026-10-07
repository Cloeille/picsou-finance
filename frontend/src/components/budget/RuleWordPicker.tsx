import { useState, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { usePreviewRule } from '@/features/budget/hooks'
import { formatDate, getLocale } from '@/lib/utils'
import type { RuleMatchType } from '@/types/api'

/**
 * RuleWordPicker — lets the user pick words from a transaction label to build
 * a KEYWORDS_ALL (AND) or KEYWORDS_ANY (OR) rule pattern.
 *
 * Props:
 *   label: string — the full display label (merchantLabel || counterparty || description)
 *   onConfirm: (payload: { pattern: string; matchType: RuleMatchType; applyToIds: number[] }) => void
 *   onClose: () => void
 */

// UUID pattern — used both for detection in source label and per-token matching
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const UUID_INLINE_RE = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi

/**
 * Tokenize a transaction label into words. UUIDs are extracted first as atomic tokens
 * so that the `-` separator does not fragment them.
 */
function tokenize(label: string): string[] {
  // Split around UUIDs: replace each UUID with a placeholder, tokenize the rest, then splice
  // UUIDs back in at their original positions.
  const tokens: string[] = []
  let last = 0
  let match: RegExpExecArray | null
  UUID_INLINE_RE.lastIndex = 0
  while ((match = UUID_INLINE_RE.exec(label)) !== null) {
    const before = label.slice(last, match.index)
    if (before.length > 0) {
      tokens.push(
        ...before
          .split(/[\s.,!?;:'"()[\]{}/\\@#$%^&*+=<>|~`-]+/)
          .filter((t) => t.length > 0)
      )
    }
    tokens.push(match[0]) // UUID as a single atomic token
    last = match.index + match[0].length
  }
  const tail = label.slice(last)
  if (tail.length > 0) {
    tokens.push(
      ...tail
        .split(/[\s.,!?;:'"()[\]{}/\\@#$%^&*+=<>|~`-]+/)
        .filter((t) => t.length > 0)
    )
  }
  return tokens
}

export function RuleWordPicker({
  label,
  onConfirm,
  onClose,
}: {
  label: string
  onConfirm: (payload: { pattern: string; matchType: RuleMatchType; applyToIds: number[] }) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const tokens = tokenize(label)
  const nonUuidIndices = tokens
    .map((tok, i) => ({ tok, i }))
    .filter(({ tok }) => !UUID_RE.test(tok))
    .map(({ i }) => i)

  const [selected, setSelected] = useState<Set<number>>(new Set(nonUuidIndices))
  const [matchType, setMatchType] = useState<RuleMatchType>('KEYWORDS_ALL')
  const [checkedPreviewIds, setCheckedPreviewIds] = useState<Set<number>>(new Set())
  const [debouncedPattern, setDebouncedPattern] = useState('')

  const previewMutation = usePreviewRule()

  const pattern = Array.from(selected)
    .sort((a, b) => a - b)
    .map((i) => tokens[i].toLowerCase())
    .join(' ')

  // Debounce pattern changes → trigger preview fetch
  useEffect(() => {
    if (pattern.trim().length === 0) return
    const timer = setTimeout(() => setDebouncedPattern(pattern), 300)
    return () => clearTimeout(timer)
  }, [pattern])

  useEffect(() => {
    if (!debouncedPattern || debouncedPattern.trim().length === 0) return
    // When preview data arrives, check all rows by default (via onSuccess callback).
    previewMutation.mutate(
      { matchType, pattern: debouncedPattern },
      {
        onSuccess: (data) => {
          setCheckedPreviewIds(new Set(data.transactions.map((tx) => tx.id)))
        },
      }
    )
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedPattern, matchType])

  function toggleToken(i: number) {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(i)) next.delete(i)
      else next.add(i)
      return next
    })
  }

  function togglePreviewRow(id: number) {
    setCheckedPreviewIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  function handleConfirm() {
    if (pattern.trim().length === 0) return
    onConfirm({ pattern, matchType, applyToIds: Array.from(checkedPreviewIds) })
  }

  const totalCount = previewMutation.data?.matchCount ?? 0
  const isPending = previewMutation.isPending

  return (
    <div className="space-y-4">
      {/* Token picker */}
      <div>
        <p className="mb-2 text-sm font-medium">{t('budget.rule.wordPickerTitle')}</p>
        <div className="flex flex-wrap gap-1.5">
          {tokens.map((tok, i) => {
            const isUuid = UUID_RE.test(tok)
            if (isUuid) {
              return (
                <span
                  key={i}
                  className="rounded px-2 py-0.5 text-xs bg-muted text-muted-foreground font-mono"
                >
                  {tok.slice(0, 8)}…
                </span>
              )
            }
            const isSel = selected.has(i)
            return (
              <button
                key={i}
                type="button"
                onClick={() => toggleToken(i)}
                className={`rounded px-2 py-0.5 text-sm font-medium transition-colors ${
                  isSel
                    ? 'bg-primary text-primary-foreground'
                    : 'bg-muted text-muted-foreground hover:bg-muted/80'
                }`}
              >
                {tok}
              </button>
            )
          })}
        </div>
      </div>

      {/* AND/OR toggle */}
      <div>
        <p className="mb-2 text-sm font-medium">{t('budget.rule.matchMode')}</p>
        <div className="inline-flex rounded-md border overflow-hidden">
          <button
            type="button"
            onClick={() => setMatchType('KEYWORDS_ALL')}
            className={`px-3 py-1.5 text-sm transition-colors ${
              matchType === 'KEYWORDS_ALL'
                ? 'bg-primary text-primary-foreground'
                : 'bg-background text-foreground hover:bg-muted'
            }`}
          >
            {t('budget.rule.matchAll')}
          </button>
          <button
            type="button"
            onClick={() => setMatchType('KEYWORDS_ANY')}
            className={`px-3 py-1.5 text-sm transition-colors ${
              matchType === 'KEYWORDS_ANY'
                ? 'bg-primary text-primary-foreground'
                : 'bg-background text-foreground hover:bg-muted'
            }`}
          >
            {t('budget.rule.matchAny')}
          </button>
        </div>
      </div>

      {/* Pattern preview */}
      {pattern && (
        <p className="text-xs text-muted-foreground">
          {t('budget.rule.pattern')}:{' '}
          <code className="font-mono">{pattern}</code>
        </p>
      )}

      {/* Preview list */}
      {pattern && (
        <div>
          <div className="flex items-center gap-2 mb-2">
            <p className="text-sm font-medium">
              {isPending
                ? t('budget.rule.previewLoading')
                : t('budget.rule.previewCount', { count: totalCount })}
            </p>
            {isPending && <Loader2 className="size-3.5 animate-spin text-muted-foreground" />}
          </div>
          {!isPending && previewMutation.data && previewMutation.data.transactions.length > 0 && (
            <div className="max-h-48 overflow-y-auto rounded-md border divide-y text-sm">
              {previewMutation.data.transactions.map((tx) => (
                <label
                  key={tx.id}
                  className="flex items-center gap-3 px-3 py-2 cursor-pointer hover:bg-muted/50"
                >
                  <Checkbox
                    checked={checkedPreviewIds.has(tx.id)}
                    onCheckedChange={() => togglePreviewRow(tx.id)}
                  />
                  <span className="flex-1 min-w-0 truncate text-foreground">{tx.label}</span>
                  <span className="shrink-0 text-xs text-muted-foreground">
                    {formatDate(tx.date, getLocale())}
                  </span>
                  <span
                    className={`shrink-0 tabular-nums font-medium ${
                      tx.amount >= 0 ? 'text-emerald-600 dark:text-emerald-400' : ''
                    }`}
                  >
                    <CurrencyDisplay value={tx.amount} showSign />
                  </span>
                </label>
              ))}
            </div>
          )}
          {totalCount > 200 && (
            <p className="mt-1 text-xs text-muted-foreground">
              {t('budget.rule.previewCapped', { count: totalCount })}
            </p>
          )}
        </div>
      )}

      {/* Actions */}
      <div className="flex justify-end gap-2 pt-2">
        <Button type="button" variant="outline" size="sm" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button
          type="button"
          size="sm"
          disabled={pattern.trim().length === 0}
          onClick={handleConfirm}
        >
          {t('budget.rule.createRule')}
        </Button>
      </div>
    </div>
  )
}
