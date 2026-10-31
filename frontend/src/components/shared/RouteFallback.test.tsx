import '@testing-library/jest-dom'
import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouteFallback, RouteSpinner } from './RouteFallback'

describe.each([
  ['RouteFallback', RouteFallback],
  ['RouteSpinner', RouteSpinner],
])('%s', (_, Fallback) => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('shows nothing while a load is still fast, then a loading status', () => {
    const { container } = render(<Fallback />)

    expect(container).toBeEmptyDOMElement()

    act(() => vi.advanceTimersByTime(199))
    expect(container).toBeEmptyDOMElement()

    act(() => vi.advanceTimersByTime(1))
    expect(screen.getByRole('status')).toHaveAccessibleName('common.loading')
  })
})
