import '@testing-library/jest-dom/vitest'
import { act, render } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AMOUNT_MASK, NEGATIVE_AMOUNT_CLASS, POSITIVE_AMOUNT_CLASS } from '@/lib/money'
import { useAppStore } from '@/stores/app-store'
import { CurrencyDisplay } from './CurrencyDisplay'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

describe('CurrencyDisplay', () => {
  beforeEach(() => {
    useAppStore.getState().setHideAmounts(false)
  })

  it('leaves unsigned amounts neutral by default', () => {
    const { container } = render(<CurrencyDisplay value={3884.37} />)
    const el = container.querySelector('span')!
    expect(el.textContent).toContain('3\u00a0884,37')
    expect(el.className).toBe('')
  })

  it('colours positive, negative, and zero signed amounts via colorBySign', () => {
    const { container, rerender } = render(<CurrencyDisplay value={110} showSign colorBySign />)
    const span = () => container.querySelector('span')!

    expect(span().textContent).toMatch(/^\+110/)
    expect(span()).toHaveClass(POSITIVE_AMOUNT_CLASS)

    rerender(<CurrencyDisplay value={-351.92} showSign colorBySign />)
    expect(span().textContent).toMatch(/^-351,92/)
    expect(span()).toHaveClass(NEGATIVE_AMOUNT_CLASS)

    rerender(<CurrencyDisplay value={0} showSign colorBySign />)
    expect(span().textContent).not.toMatch(/^[+-]/)
    expect(span().className).toBe('')
  })

  it('supports inflow mode where positive inflows are green and outflows stay neutral', () => {
    const { container, rerender } = render(
      <CurrencyDisplay value={2500} showSign colorBySign="inflow" className="text-foreground" />,
    )
    const span = () => container.querySelector('span')!
    expect(span()).toHaveClass(POSITIVE_AMOUNT_CLASS)

    rerender(<CurrencyDisplay value={-45} showSign colorBySign="inflow" className="text-foreground" />)
    expect(span()).toHaveClass('text-foreground')
    expect(span()).not.toHaveClass(NEGATIVE_AMOUNT_CLASS)
    expect(span()).not.toHaveClass(POSITIVE_AMOUNT_CLASS)

    rerender(<CurrencyDisplay value={0} showSign colorBySign="inflow" className="text-foreground" />)
    expect(span()).toHaveClass('text-foreground')
    expect(span()).not.toHaveClass(POSITIVE_AMOUNT_CLASS)
  })

  it('masks digits in privacy mode while preserving sign and sign colour consistently for green and red', () => {
    const { container, rerender } = render(<CurrencyDisplay value={110} showSign colorBySign />)
    const span = () => container.querySelector('span')!

    act(() => { useAppStore.getState().setHideAmounts(true) })

    expect(span().textContent).toContain(AMOUNT_MASK)
    expect(span().textContent).not.toMatch(/\d/)
    expect(span().textContent).toMatch(/^\+/)
    expect(span()).toHaveClass(POSITIVE_AMOUNT_CLASS)

    rerender(<CurrencyDisplay value={-351.92} showSign colorBySign />)
    expect(span().textContent).toContain(AMOUNT_MASK)
    expect(span().textContent).not.toMatch(/\d/)
    expect(span().textContent).toMatch(/^-/)
    expect(span()).toHaveClass(NEGATIVE_AMOUNT_CLASS)

    rerender(<CurrencyDisplay value={0} showSign colorBySign />)
    expect(span().textContent).toContain(AMOUNT_MASK)
    expect(span().textContent).not.toMatch(/\d/)
    expect(span().textContent).not.toMatch(/^[+-]/)
    expect(span().className).toBe('')
  })
})
