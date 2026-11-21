import type { Account } from '@/types/api'

type OrderKey = Pick<Account, 'id' | 'listPosition'>
type CollapseKey = Pick<Account, 'listCollapsed'>

/**
 * Manual ordering for the "Tout" account list. Positioned entries come first
 * (ascending `listPosition`), the rest keep their incoming (natural) order
 * after them. Display-only: every total, budget and chart keeps using the
 * unfiltered lists.
 */
export function orderAccountsForList<T>(items: T[], select: (item: T) => OrderKey = defaultSelect): T[] {
  const keyed = items.map((item) => ({ item, key: select(item) }))
  const positioned = keyed
    .filter(({ key }) => key.listPosition != null)
    .sort(
      (x, y) =>
        (x.key.listPosition as number) - (y.key.listPosition as number) || x.key.id - y.key.id,
    )
  const natural = keyed.filter(({ key }) => key.listPosition == null)
  return [...positioned, ...natural].map(({ item }) => item)
}

/** Id order the backend expects: the visible top-level entries, collapsed included. */
export function displayOrderIds<T extends Pick<Account, 'id'>>(ordered: T[]): number[] {
  return ordered.map((a) => a.id)
}

/** Split a Tout list into shown cards and collapsed ones hiding behind the recap row. */
export function splitCollapsed<T>(items: T[], select: (item: T) => CollapseKey = defaultSelect): {
  shown: T[]
  collapsed: T[]
} {
  return {
    shown: items.filter((item) => !select(item).listCollapsed),
    collapsed: items.filter((item) => select(item).listCollapsed),
  }
}

// Default selector when items already carry the ordering/collapse keys. The cast is safe:
// it is only the default, and every direct caller passes items shaped like the key.
function defaultSelect<T>(item: T): OrderKey & CollapseKey {
  return item as unknown as OrderKey & CollapseKey
}
