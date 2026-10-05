import '@testing-library/jest-dom'
import { fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

// Desktop branch: the formatted text field, which is the one with the typing behaviour.
vi.mock('@/hooks/use-touch-device', () => ({ useIsTouchDevice: () => false }))

const { DateInput } = await import('./DateInput')

// getLocale() reads <html lang>, which jsdom leaves empty — it would otherwise fall back to
// navigator.language (en-US) and read dates month-first. The e2e helpers pin the language the
// same way, and day-first is the ordering this component's users actually type in.
beforeEach(() => {
  document.documentElement.lang = 'fr'
})

/** The real wiring: a parent that stores what the field emits and feeds it straight back. */
function Controlled({ initial = '' }: { initial?: string }) {
  const [value, setValue] = useState(initial)
  return (
    <>
      <DateInput value={value} onChange={setValue} />
      <output data-testid="iso">{value}</output>
    </>
  )
}

function field() {
  return screen.getByRole('textbox') as HTMLInputElement
}

/** Type a string one character at a time, as a person does. */
function typeInto(input: HTMLInputElement, value: string) {
  for (let i = 1; i <= value.length; i++) {
    fireEvent.change(input, { target: { value: value.slice(0, i) } })
  }
}

describe('DateInput', () => {
  // The bug this guards: every keystroke that happens to parse was echoed back as a new
  // `value` and rewrote the text. Typing a 1990s birth year meant watching "19" become "2019"
  // mid-word — parseDate expands a two-digit year to the 2000s — and then fighting the caret.
  it('does not rewrite the text while a year is being typed', () => {
    render(<Controlled />)
    const input = field()

    // The load-bearing moment: "20/09/19" is a complete date to parseDate, which expands the
    // year to 2019 and propagates it. The field must keep showing what was typed — asserting
    // only the final value would pass even with the resync, because each further keystroke
    // overwrites the rewritten text anyway.
    typeInto(input, '20/09/19')
    expect(input.value).toBe('20/09/19')

    typeInto(input, '20/09/1998')
    expect(input.value).toBe('20/09/1998')
    expect(screen.getByTestId('iso')).toHaveTextContent('1998-09-20')
  })

  it('accepts a year far outside the two-digit expansion', () => {
    render(<Controlled />)
    const input = field()

    typeInto(input, '01/01/1901')

    expect(input.value).toBe('01/01/1901')
    expect(screen.getByTestId('iso')).toHaveTextContent('1901-01-01')
  })

  it('settles the text into the display format on blur', () => {
    render(<Controlled />)
    const input = field()

    typeInto(input, '14.6.1990')
    expect(screen.getByTestId('iso')).toHaveTextContent('1990-06-14')

    fireEvent.blur(input)
    expect(input.value).toBe('14/06/1990')
  })

  it('leaves unparseable text alone on blur and flags it', () => {
    render(<Controlled />)
    const input = field()

    fireEvent.change(input, { target: { value: '31/02/2026' } })
    fireEvent.blur(input)

    expect(input.value).toBe('31/02/2026')
    expect(input).toHaveAttribute('aria-invalid', 'true')
  })

  // The resync still has to work for a change that did not come from this field — a form reset,
  // or the key-remount modals seeding a different entity.
  it('still follows an external value change', () => {
    const { rerender } = render(<DateInput value="1998-09-20" onChange={() => {}} />)
    expect(field().value).toBe('20/09/1998')

    rerender(<DateInput value="2001-03-04" onChange={() => {}} />)
    expect(field().value).toBe('04/03/2001')
  })

  it('emits an empty string when cleared', () => {
    render(<Controlled initial="1998-09-20" />)
    const input = field()

    fireEvent.change(input, { target: { value: '' } })

    expect(input.value).toBe('')
    expect(screen.getByTestId('iso')).toBeEmptyDOMElement()
  })
})
