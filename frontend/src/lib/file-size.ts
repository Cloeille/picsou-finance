import type { TFunction } from 'i18next'

const UNITS = ['B', 'KB', 'MB', 'GB'] as const

/** "12,3 Mo" / "12.3 MB": binary steps, the unit's wording and the number's format follow the UI language. */
export function formatFileSize(bytes: number, t: TFunction, language: string): string {
  let value = Math.max(0, bytes)
  let unit = 0
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024
    unit += 1
  }
  const formatted = value.toLocaleString(language, { maximumFractionDigits: unit === 0 ? 0 : 1 })
  return t(`common.fileSize.${UNITS[unit]}`, { value: formatted })
}
