import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Input } from '@/components/ui/input'
import { useIsTouchDevice } from '@/hooks/use-touch-device'
import { formatDate, parseDate, getLocale } from '@/lib/utils'
import { useAppStore, type DateFormat } from '@/stores/app-store'

interface DateInputProps {
  /** ISO `yyyy-MM-dd` (or empty string when unset). */
  value: string
  /** Emits an ISO `yyyy-MM-dd` string, or `''` when cleared. */
  onChange: (iso: string) => void
  id?: string
  min?: string
  required?: boolean
  disabled?: boolean
  className?: string
}

function dateSeparator(locale: string, format: DateFormat): string {
  if (format === 'iso') return '-'
  const sample = new Intl.DateTimeFormat(locale, {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  }).format(new Date(2000, 0, 2))
  const match = sample.match(/[^0-9]/)
  return match ? match[0] : '/'
}

function formatDateTyping(raw: string, prev: string, sep: string): string {
  if (raw === '') return ''
  const escaped = sep.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  if (!new RegExp(`^[0-9${escaped}]*$`).test(raw)) return raw

  const collapsed = raw.replace(new RegExp(`${escaped}{2,}`, 'g'), sep)
  const isDeleting = collapsed.length < prev.length

  const parts = collapsed.split(sep)
  if (parts.length > 1 && parts[0].length === 1) return collapsed
  if (parts.length > 2 && parts[1].length === 1) return collapsed

  const digits = collapsed.replace(/\D/g, '').slice(0, 8)
  if (digits.length === 0) return ''

  if (digits.length < 2) return digits
  if (digits.length === 2) return isDeleting ? digits : `${digits}${sep}`
  if (digits.length < 4) return `${digits.slice(0, 2)}${sep}${digits.slice(2)}`
  if (digits.length === 4) {
    return isDeleting
      ? `${digits.slice(0, 2)}${sep}${digits.slice(2)}`
      : `${digits.slice(0, 2)}${sep}${digits.slice(2)}${sep}`
  }
  return `${digits.slice(0, 2)}${sep}${digits.slice(2, 4)}${sep}${digits.slice(4)}`
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
export function DateInput({ value, onChange, id, min, required, disabled, className }: DateInputProps) {
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
        min={min}
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value)}
        required={required}
        disabled={disabled}
        className={className}
      />
    )
  }

  const handleChange = (raw: string) => {
    const sep = dateSeparator(getLocale(), dateFormat)
    const next = formatDateTyping(raw, text, sep)
    setText(next)
    if (next.trim() === '') {
      setLastEmitted('')
      onChange('')
      return
    }
    const iso = parseDate(next, getLocale(), dateFormat)
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
