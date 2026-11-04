import * as React from "react"

import { Input } from "@/components/ui/input"

/**
 * Sanitize a raw keystroke value to a tolerant numeric string: digits, every
 * "." and "," as typed (parseAmount decides which one is the decimal separator,
 * so "12.500,50" keeps its thousands), and an optional leading minus sign.
 * Everything else is dropped.
 */
function sanitizeNumeric(raw: string): string {
  // Strip anything that is not a digit, separator or minus.
  let cleaned = raw.replace(/[^\d.,-]/g, "")
  // Keep only a leading minus (drop any other "-").
  const negative = cleaned.startsWith("-")
  cleaned = cleaned.replace(/-/g, "")
  return (negative ? "-" : "") + cleaned
}

/**
 * Drop-in numeric input that accepts both "." and "," as separators.
 * Renders a `type="text" inputMode="decimal"` field — unlike `type="number"`,
 * this never rejects a comma in FR locales and shows the numeric mobile keypad.
 *
 * Works with controlled-string forms (reading `e.target.value`) and with
 * react-hook-form `register(name, { setValueAs: parseAmount })` (which also
 * reads the sanitized `e.target.value`).
 */
function NumericInput({ onChange, ...props }: React.ComponentProps<"input">) {
  // `type="text"` / `inputMode="decimal"` are placed AFTER the spread so they
  // always win, even if a caller accidentally passes `type`.
  return (
    <Input
      {...props}
      type="text"
      inputMode="decimal"
      onChange={(e) => {
        const sanitized = sanitizeNumeric(e.target.value)
        if (sanitized !== e.target.value) {
          e.target.value = sanitized
        }
        onChange?.(e)
      }}
    />
  )
}

export { NumericInput }
