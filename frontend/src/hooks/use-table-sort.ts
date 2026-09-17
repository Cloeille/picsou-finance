import { useMemo, useState } from 'react'
import { getLocale } from '@/lib/utils'

export type SortDirection = 'asc' | 'desc'

export interface SortState<K extends string> {
  key: K
  direction: SortDirection
}

/**
 * How one column is read and compared.
 *
 * `kind` is declared rather than inferred from the value: every column in these tables is
 * nullable, and one whose visible rows happen to be all null would otherwise pick its comparator
 * from nothing.
 */
export interface SortColumn<T> {
  kind: 'text' | 'number'
  value: (row: T) => string | number | null
}

export type SortColumns<T, K extends string> = Record<K, SortColumn<T>>

/** A number column reads high-to-low first; a text column reads A-to-Z first. */
function naturalDirection(kind: SortColumn<never>['kind']): SortDirection {
  return kind === 'number' ? 'desc' : 'asc'
}

/**
 * Client-side sorting for a table, held in component-local state.
 *
 * The state lives here rather than in a store, per the UI-filter ADR
 * (`docs/decisions/2026-04-05-component-local-state-for-ui-filters.md`), which names sorting
 * explicitly. Remounting the consumer with a React `key` is how a caller resets it.
 *
 * **Declare `columns` at module scope.** The accessors read nothing but the row, so they never
 * need to close over render state — and a map rebuilt each render would be a new object every
 * time, which defeats the memo below.
 *
 * Two rules the comparator enforces, both of which these tables depend on:
 *
 * - **Nulls sink to the bottom in both directions.** A dash here means "we could not price this
 *   line", not "zero" — the same distinction `PositionsByProduct` makes when it refuses to publish
 *   a subtotal over an unpriced row. Ordering null as the smallest value would float exactly the
 *   lines we know least about to the top of an ascending sort.
 * - **The sort is stable, and never in place.** `[...rows]` is copied, and `Array.sort` has been
 *   specified as stable since ES2019, so rows that tie keep the order the API sent them in. That
 *   is what makes sorting on a column of all-nulls a no-op rather than a shuffle.
 */
export function useTableSort<T, K extends string>(
  rows: T[],
  columns: SortColumns<T, K>,
  // `NoInfer` so the key union comes from `columns` alone. Without it TypeScript infers K from
  // this literal too, collapsing it to the single default key and rejecting every other column.
  initial: SortState<NoInfer<K>>,
) {
  const [sort, setSort] = useState<SortState<K>>(initial as SortState<K>)

  const sorted = useMemo(() => {
    const column = columns[sort.key]
    if (!column) return rows

    // Built once per sort rather than per comparison: constructing a Collator costs far more
    // than comparing a few dozen short strings with it.
    const collator = new Intl.Collator(getLocale(), { numeric: true, sensitivity: 'base' })
    const sign = sort.direction === 'asc' ? 1 : -1

    return [...rows].sort((a, b) => {
      const left = column.value(a)
      const right = column.value(b)

      // Deliberately not multiplied by `sign`: an unknown value belongs at the bottom whichever
      // way the column is pointing.
      if (left == null && right == null) return 0
      if (left == null) return 1
      if (right == null) return -1

      if (column.kind === 'number') return sign * (Number(left) - Number(right))
      return sign * collator.compare(String(left), String(right))
    })
  }, [rows, columns, sort])

  /** The same column flips direction; a different one starts at its natural direction. */
  const toggle = (key: K) =>
    setSort(current =>
      current.key === key
        ? { key, direction: current.direction === 'asc' ? 'desc' : 'asc' }
        : { key, direction: naturalDirection(columns[key].kind) },
    )

  return { rows: sorted, sort, toggle }
}
