import { useEffect, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { useBankCountries } from '@/features/sync/hooks'
import { formatCountryName } from '@/lib/utils'

interface BankCountrySelectProps {
  value: string
  onChange: (country: string) => void
}

/** Picsou's primary market — the initial/fallback selection everywhere a country picker appears. */
export const DEFAULT_BANK_COUNTRY = 'FR'

const FALLBACK_COUNTRIES = [DEFAULT_BANK_COUNTRY]

/**
 * Country picker for the bank search step (AddAccountModal's BankWizard, BankSyncTab).
 * Options come live from `GET /api/sync/countries` (whatever the active provider actually
 * covers) rather than a hardcoded list, so coverage never drifts from reality. The backend
 * already returns codes sorted; this just pins the default market first for convenience.
 */
export function BankCountrySelect({ value, onChange }: BankCountrySelectProps) {
  const { t } = useTranslation()
  const { data: countries, isError, isSuccess } = useBankCountries()
  const hasCountries = !!countries && countries.length > 0

  // Show the "couldn't load" message whenever we're genuinely stuck on the France-only
  // fallback (the request failed, or it succeeded but came back empty) — but NOT while the
  // query is still loading, and NOT if we still have real (possibly stale) data from a prior
  // success, since the message would then be false ("showing France only" while the full
  // list is actually rendered).
  const showLoadError = !hasCountries && (isError || isSuccess)

  const options = useMemo(() => {
    const codes = hasCountries ? countries : FALLBACK_COUNTRIES
    const ordered = codes.includes(DEFAULT_BANK_COUNTRY)
      ? [DEFAULT_BANK_COUNTRY, ...codes.filter((c) => c !== DEFAULT_BANK_COUNTRY)]
      : codes
    return ordered.map((code) => ({ code, label: formatCountryName(code) }))
  }, [countries, hasCountries])

  // If the current value isn't in the loaded list (e.g. the active provider has no
  // DEFAULT_BANK_COUNTRY coverage), the native <select> would render blank while searches
  // silently kept filtering by the invisible stale value — snap to the first real option.
  // `onChange` is a useState setter from both call sites (referentially stable across
  // renders), so including it here doesn't risk a thrashing loop.
  useEffect(() => {
    if (options.length > 0 && !options.some((o) => o.code === value)) {
      onChange(options[0].code)
    }
  }, [options, value, onChange])

  return (
    <div className="flex shrink-0 flex-col gap-1">
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-label={t('sync.banks.country')}
        className="h-10 rounded-md border border-input bg-background text-foreground px-4 text-sm outline-none [color-scheme:light] dark:[color-scheme:dark]"
      >
        {options.map((o) => (
          <option key={o.code} value={o.code}>{o.label}</option>
        ))}
      </select>
      {showLoadError && (
        <span className="text-xs text-destructive">{t('sync.banks.countriesLoadError')}</span>
      )}
    </div>
  )
}
