import '@testing-library/jest-dom'
import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { BudgetPeriodProvider, useBudgetPeriod } from './BudgetPeriodContext'

const SESSION_KEY = 'picsou_budget_anchor'

describe('BudgetPeriodContext', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('default anchor is undefined when sessionStorage is empty', () => {
    const { result } = renderHook(() => useBudgetPeriod(), {
      wrapper: BudgetPeriodProvider,
    })
    expect(result.current.anchor).toBeUndefined()
  })

  it('setAnchor writes the value to sessionStorage and updates anchor', () => {
    const { result } = renderHook(() => useBudgetPeriod(), {
      wrapper: BudgetPeriodProvider,
    })
    act(() => {
      result.current.setAnchor('2024-03-01')
    })
    expect(sessionStorage.getItem(SESSION_KEY)).toBe('2024-03-01')
    expect(result.current.anchor).toBe('2024-03-01')
  })

  it('setAnchor(undefined) removes the value from sessionStorage and resets anchor', () => {
    sessionStorage.setItem(SESSION_KEY, '2024-03-01')
    const { result } = renderHook(() => useBudgetPeriod(), {
      wrapper: BudgetPeriodProvider,
    })
    act(() => {
      result.current.setAnchor(undefined)
    })
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull()
    expect(result.current.anchor).toBeUndefined()
  })

  it('a fresh provider reads the persisted value from sessionStorage', () => {
    sessionStorage.setItem(SESSION_KEY, '2024-06-01')
    const { result } = renderHook(() => useBudgetPeriod(), {
      wrapper: BudgetPeriodProvider,
    })
    expect(result.current.anchor).toBe('2024-06-01')
  })
})
