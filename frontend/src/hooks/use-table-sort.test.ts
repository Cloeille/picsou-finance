import { act, renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { useTableSort, type SortColumns } from './use-table-sort'

interface Row {
  ticker: string
  name: string
  value: number | null
}

type Key = 'ticker' | 'name' | 'value'

const COLUMNS: SortColumns<Row, Key> = {
  ticker: { kind: 'text', value: r => r.ticker },
  name: { kind: 'text', value: r => r.name },
  value: { kind: 'number', value: r => r.value },
}

const ROWS: Row[] = [
  { ticker: 'CW8', name: 'Élan Monde', value: 300 },
  { ticker: 'AAPL', name: 'Apple', value: 100 },
  { ticker: 'ZZZ', name: 'Zeta', value: null },
  { ticker: 'ESE', name: 'Emerging', value: 200 },
]

function tickers(rows: Row[]) {
  return rows.map(r => r.ticker)
}

function setup(initialKey: Key = 'value', direction: 'asc' | 'desc' = 'desc') {
  return renderHook(() => useTableSort(ROWS, COLUMNS, { key: initialKey, direction }))
}

describe('useTableSort', () => {
  it('sorts numerically on the initial column and direction', () => {
    const { result } = setup()
    expect(tickers(result.current.rows)).toEqual(['CW8', 'ESE', 'AAPL', 'ZZZ'])
  })

  it('flips direction when the same column is toggled', () => {
    const { result } = setup()
    act(() => result.current.toggle('value'))

    expect(result.current.sort).toEqual({ key: 'value', direction: 'asc' })
    // ZZZ has no value and stays last even ascending -- see the null rule below.
    expect(tickers(result.current.rows)).toEqual(['AAPL', 'ESE', 'CW8', 'ZZZ'])
  })

  it('starts a new column at its natural direction', () => {
    const { result } = setup()

    act(() => result.current.toggle('ticker'))
    expect(result.current.sort).toEqual({ key: 'ticker', direction: 'asc' })

    act(() => result.current.toggle('value'))
    expect(result.current.sort).toEqual({ key: 'value', direction: 'desc' })
  })

  // The load-bearing rule: a dash means "unknown", not "zero". Ordering null as the smallest
  // value would float the lines we know least about to the top of an ascending sort.
  it('keeps nulls at the bottom in both directions', () => {
    const { result } = setup()
    expect(tickers(result.current.rows).at(-1)).toBe('ZZZ')

    act(() => result.current.toggle('value'))
    expect(tickers(result.current.rows).at(-1)).toBe('ZZZ')
  })

  // "Élan" starts at U+00C9, above 'Z' in code-point order: a raw `<` comparison would file it
  // after "Zeta". The collator folds the accent, so it lands between Apple and Emerging.
  it('compares text through a collator, so an accent does not sort past Z', () => {
    const { result } = setup('name', 'asc')
    expect(tickers(result.current.rows)).toEqual(['AAPL', 'CW8', 'ESE', 'ZZZ'])
  })

  it('is stable: rows that tie keep the order the API sent them in', () => {
    const tied: Row[] = [
      { ticker: 'B', name: 'b', value: null },
      { ticker: 'A', name: 'a', value: null },
      { ticker: 'C', name: 'c', value: null },
    ]
    const { result } = renderHook(() =>
      useTableSort(tied, COLUMNS, { key: 'value', direction: 'desc' }),
    )
    expect(tickers(result.current.rows)).toEqual(['B', 'A', 'C'])
  })

  it('never sorts the caller array in place', () => {
    const source = [...ROWS]
    renderHook(() => useTableSort(source, COLUMNS, { key: 'value', direction: 'asc' }))
    expect(tickers(source)).toEqual(['CW8', 'AAPL', 'ZZZ', 'ESE'])
  })
})
