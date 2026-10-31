import { ChevronDown, ChevronsUpDown, ChevronUp } from 'lucide-react'
import { TableHead } from '@/components/ui/table'
import { cn } from '@/lib/utils'
import type { SortState } from '@/hooks/use-table-sort'

interface SortableTableHeadProps<K extends string> {
  sortKey: K
  sort: SortState<K>
  onSort: (key: K) => void
  /** Numeric columns are right-aligned, so their control has to hug the right edge too. */
  align?: 'left' | 'right'
  className?: string
  children: React.ReactNode
}

/**
 * A `TableHead` whose label is a sort control.
 *
 * It wraps the shadcn primitive rather than extending it: `components/ui/` is generated and
 * marked do-not-edit (`docs/CODING_RULES.md` rule 1), and a future `shadcn add table` would
 * overwrite anything added there.
 *
 * The trigger is a bare `<button>`, not the shadcn `Button`. That primitive is `h-10 px-8` by
 * design — the readable CTA rhythm — which is taller than the header row it would sit in. Header
 * controls are the "dense table data" the styling convention exempts.
 *
 * The inactive state still shows a muted `ChevronsUpDown`: a column that can be sorted has to say
 * so before the first click, not after it.
 */
export function SortableTableHead<K extends string>({
  sortKey,
  sort,
  onSort,
  align = 'left',
  className,
  children,
}: SortableTableHeadProps<K>) {
  const active = sort.key === sortKey
  const Icon = !active ? ChevronsUpDown : sort.direction === 'asc' ? ChevronUp : ChevronDown

  return (
    <TableHead
      className={cn(align === 'right' && 'text-right', className)}
      aria-sort={!active ? 'none' : sort.direction === 'asc' ? 'ascending' : 'descending'}
    >
      <button
        type="button"
        onClick={() => onSort(sortKey)}
        className={cn(
          'inline-flex w-full items-center gap-1 rounded-md font-medium transition-colors hover:text-foreground',
          active ? 'text-foreground' : 'text-muted-foreground',
          align === 'right' ? 'justify-end' : 'justify-start',
        )}
      >
        {children}
        <Icon className={cn('size-3.5 shrink-0', !active && 'text-muted-foreground/50')} aria-hidden="true" />
      </button>
    </TableHead>
  )
}
