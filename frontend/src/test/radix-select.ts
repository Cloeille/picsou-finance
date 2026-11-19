import { fireEvent, screen } from '@testing-library/react'

function ensureRadixJsdomPolyfills() {
  if (typeof Element !== 'undefined') {
    if (!Element.prototype.hasPointerCapture) {
      Element.prototype.hasPointerCapture = () => false
    }
    if (!Element.prototype.setPointerCapture) {
      Element.prototype.setPointerCapture = () => {}
    }
    if (!Element.prototype.releasePointerCapture) {
      Element.prototype.releasePointerCapture = () => {}
    }
    if (!Element.prototype.scrollIntoView) {
      Element.prototype.scrollIntoView = () => {}
    }
  }
}

/** Open a Radix Select trigger in jsdom. */
export function openSelect(trigger: HTMLElement) {
  ensureRadixJsdomPolyfills()
  fireEvent.pointerDown(trigger, { button: 0, ctrlKey: false })
  fireEvent.click(trigger)
  if (trigger.getAttribute('aria-expanded') !== 'true') {
    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
  }
}

/** Open a Radix Select trigger and return all rendered option elements. */
export function getSelectOptions(trigger: HTMLElement): HTMLElement[] {
  openSelect(trigger)
  return screen.getAllByRole('option')
}

/** Open a Radix Select trigger, read all option values (and labels), then close it cleanly. */
export function getSelectOptionValues(trigger: HTMLElement): string[] {
  const options = getSelectOptions(trigger)
  const values = options.map((opt) => opt.getAttribute('data-option-value') ?? '')
  fireEvent.keyDown(options[0] ?? trigger, { key: 'Escape' })
  return values
}

/** Open a Radix Select trigger and click the option matching `value` (via `data-option-value`) or `label`. */
export function chooseSelectOption(trigger: HTMLElement, valueOrLabel: string) {
  const options = getSelectOptions(trigger)
  const match = options.find(
    (opt) =>
      opt.getAttribute('data-option-value') === valueOrLabel ||
      opt.textContent?.trim() === valueOrLabel,
  )
  if (!match) {
    throw new Error(
      `Option "${valueOrLabel}" not found. Available: ${options
        .map((o) => `${o.getAttribute('data-option-value') ?? ''}:${o.textContent?.trim()}`)
        .join(', ')}`,
    )
  }
  fireEvent.click(match)
}
