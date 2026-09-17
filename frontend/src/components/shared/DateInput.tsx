import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Input } from '@/components/ui/input'
import { useIsTouchDevice } from '@/hooks/use-touch-device'
import { formatDate, parseDate, getLocale } from '@/lib/utils'
import { useAppStore } from '@/stores/app-store'

interface DateInputProps {
  /** ISO `yyyy-MM-dd` (or empty string when unset). */
  value: string
  /** Emits an ISO `yyyy-MM-dd` string, or `''` when cleared. */
  onChange: (iso: string) => void
  id?: string
  required?: boolean
  disabled?: boolean
  className?: string
}

/**
 * Date field whose external contract is always an ISO `yyyy-MM-dd` string,
 * regardless of how it's displayed.
 *
 * - **Touch devices** render a native `<input type="date">` — the OS date picker
 *   is the best mobile experience and already speaks ISO.
 * - **Desktop** renders a text field that *displays* the date in the user's chosen
 *   format ({@link formatDate}) and *parses* what they type back to ISO
 *   ({@link parseDate}). `onChange` only fires once the text parses to a real date,
 *   so a half-typed value never propagates a garbage ISO string upstream.
 *
 * Native `<input type="date">` can't be coerced to a custom display format (it
 * always follows the OS/browser locale), which is exactly why desktop needs the
 * text variant to honor the in-app `dd/mm/yyyy` vs `dd-mm-yyyy` setting.
 */
export function DateInput({ value, onChange, id, required, disabled, className }: DateInputProps) {
  const { t } = useTranslation()
  const isTouch = useIsTouchDevice()
  const dateFormat = useAppStore((s) => s.dateFormat)

  // Desktop text-field state. Hooks must run unconditionally, so they live here
  // even when the touch branch renders.
  const display = value ? formatDate(value, getLocale(), dateFormat) : ''
  const [text, setText] = useState(display)
  const [lastValue, setLastValue] = useState(value)
  const [lastFormat, setLastFormat] = useState(dateFormat)
  const [touched, setTouched] = useState(false)
  // The last ISO this field emitted, so the echo of it can be told apart from a genuine
  // external change. See the resync below.
  const [lastEmitted, setLastEmitted] = useState<string | null>(null)

  // Resync the visible text when the external ISO value or the format changes —
  // derived during render (no effect) to avoid a cascading re-render.
  //
  // But *not* when the incoming value is simply the one we just emitted. Every keystroke that
  // happens to parse propagates upstream and comes straight back as a new `value`, and
  // rewriting the text from it overwrote what the user was still typing: entering a birth year
  // meant typing "19", watching the field turn into "…/2019" (parseDate expands a two-digit
  // year to the 2000s) and then fighting the caret for the remaining digits. While the text is
  // the user's own, the text is the source of truth.
  if (value !== lastValue || dateFormat !== lastFormat) {
    const isOwnEcho = value === lastEmitted && dateFormat === lastFormat
    setLastValue(value)
    setLastFormat(dateFormat)
    if (!isOwnEcho) setText(value ? formatDate(value, getLocale(), dateFormat) : '')
  }

  if (isTouch) {
    return (
      <Input
        id={id}
        type="date"
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value)}
        required={required}
        disabled={disabled}
        className={className}
      />
    )
  }

  const handleChange = (raw: string) => {
    setText(raw)
    if (raw.trim() === '') {
      setLastEmitted('')
      onChange('')
      return
    }
    const iso = parseDate(raw, getLocale(), dateFormat)
    if (iso) {
      setLastEmitted(iso)
      onChange(iso)
    }
  }

  // Settle the text into the canonical display once the user leaves the field. The resync above
  // used to do this incidentally, on every keystroke; doing it on blur is what keeps a value
  // typed with different separators ("14.6.1990") from staying odd on screen, without touching
  // the text while it is still being written.
  const handleBlur = () => {
    setTouched(true)
    const iso = parseDate(text, getLocale(), dateFormat)
    if (iso) setText(formatDate(iso, getLocale(), dateFormat))
  }

  const invalid =
    touched && text.trim() !== '' && parseDate(text, getLocale(), dateFormat) === null

  return (
    <Input
      id={id}
      type="text"
      inputMode="numeric"
      value={text}
      onChange={(e) => handleChange(e.target.value)}
      onBlur={handleBlur}
      placeholder={t('common.dateHint')}
      required={required}
      disabled={disabled}
      aria-invalid={invalid || undefined}
      className={className}
    />
  )
}
