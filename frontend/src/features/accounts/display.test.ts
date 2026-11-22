import { describe, expect, it } from 'vitest'
import { displayOrderIds, orderAccountsForList, splitCollapsed } from './display'

function account(id: number, overrides: Partial<{ listPosition: number | null; listCollapsed: boolean }> = {}) {
  return {
    id,
    listPosition: overrides.listPosition ?? null,
    listCollapsed: overrides.listCollapsed ?? false,
  }
}

describe('orderAccountsForList', () => {
  it('keeps natural order when nothing is positioned', () => {
    const list = [account(3), account(1), account(2)]
    expect(orderAccountsForList(list).map((a) => a.id)).toEqual([3, 1, 2])
  })

  it('puts positioned accounts first, then the rest in natural order', () => {
    const list = [account(1), account(2, { listPosition: 1 }), account(3, { listPosition: 0 }), account(4)]
    expect(orderAccountsForList(list).map((a) => a.id)).toEqual([3, 2, 1, 4])
  })

  it('breaks position ties by id', () => {
    const list = [account(9, { listPosition: 0 }), account(4, { listPosition: 0 })]
    expect(orderAccountsForList(list).map((a) => a.id)).toEqual([4, 9])
  })

  it('never drops collapsed accounts: ordering is display-only', () => {
    const list = [account(1, { listCollapsed: true }), account(2)]
    expect(orderAccountsForList(list)).toHaveLength(2)
  })
})

describe('displayOrderIds', () => {
  it('returns ids in the given order, collapsed included', () => {
    expect(displayOrderIds([account(3), account(1, { listCollapsed: true })])).toEqual([3, 1])
  })
})

describe('splitCollapsed', () => {
  it('separates shown cards from the recap row', () => {
    const { shown, collapsed } = splitCollapsed([
      account(1),
      account(2, { listCollapsed: true }),
      account(3, { listCollapsed: true }),
    ])
    expect(shown.map((a) => a.id)).toEqual([1])
    expect(collapsed.map((a) => a.id)).toEqual([2, 3])
  })
})
