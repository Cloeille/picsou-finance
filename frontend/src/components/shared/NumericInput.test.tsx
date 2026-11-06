import '@testing-library/jest-dom'
import { fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'

import { parseAmount } from '@/lib/utils'
import { NumericInput } from './NumericInput'

/** A controlled-string form, as the amount fields use it: the text is parsed at submit. */
function Controlled() {
  const [value, setValue] = useState('')
  return (
    <>
      <NumericInput value={value} onChange={(e) => setValue(e.target.value)} />
      <output data-testid="parsed">{String(parseAmount(value))}</output>
    </>
  )
}

/** Type a string one character at a time, as a person does. */
function typeInto(input: HTMLElement, value: string) {
  for (let i = 1; i <= value.length; i++) {
    fireEvent.change(input, { target: { value: value.slice(0, i) } })
  }
}

describe('NumericInput', () => {
  it.each([
    ['12.500,50', '12500.5'],
    ['12,500.50', '12500.5'],
    ['1.000.000', '1000000'],
    ['12,50', '12.5'],
  ])('keeps %s as typed and reads it as %s', (typed, parsed) => {
    render(<Controlled />)
    const input = screen.getByRole('textbox')

    typeInto(input, typed)

    expect(input).toHaveValue(typed)
    expect(screen.getByTestId('parsed').textContent).toBe(parsed)
  })

  it('drops the spaces and letters of a pasted amount', () => {
    render(<Controlled />)
    const input = screen.getByRole('textbox')

    fireEvent.change(input, { target: { value: '-1 234,56 EUR' } })

    expect(input).toHaveValue('-1234,56')
    expect(screen.getByTestId('parsed').textContent).toBe('-1234.56')
  })
})
