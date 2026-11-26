import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Bank } from '@phosphor-icons/react'
import type { AccountType } from '@/types/api'
import { Input } from '@/components/ui/input'
import { Avatar, AvatarImage, AvatarFallback } from '@/components/ui/avatar'
import { BankCountrySelect, DEFAULT_BANK_COUNTRY } from '@/components/shared/BankCountrySelect'
import { useBankCountries, useSearchInstitutions } from '@/features/sync/hooks'
import { INSURER_ACCOUNT_TYPES, matchInsurerProviders } from '@/lib/provider-logos'

interface BankPickerProps {
  id?: string
  /** The bank's name — what is stored on the account as `provider`. */
  value: string
  placeholder?: string
  /**
   * Account type being created or edited. When it is a life-insurance or retirement wrapper
   * (`ASSURANCE_VIE` or `PER`), the picker also proposes the curated French insurers list
   * alongside Enable Banking's catalog, available even when bank sync is not configured.
   */
  accountType?: AccountType
  /**
   * `institutionId` is the catalog's own id for the bank that was picked, and is undefined
   * whenever the name was typed or chosen from the curated insurers list. The backend consumes
   * it to resolve Enable Banking logos and never stores it.
   */
  onChange: (bankName: string, institutionId?: string) => void
}

interface PickerSuggestion {
  key: string
  name: string
  logoUrl: string | null
  badge: string
  institutionId?: string
}

function normalizeName(value: string): string {
  return value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim()
}

/**
 * The bank field of the manual account form: free text that also searches the institution
 * catalog, so picking a bank from the list is what gives a hand-entered account a real logo.
 * For life-insurance and PER accounts, it also surfaces the curated French insurers list so
 * manual contracts can pick their insurer name even when Enable Banking is not configured.
 *
 * <p>Free text first, list second — deliberately. The catalog only exists when Enable Banking
 * is configured, and a bank Picsou cannot sync (a foreign account, a broker, the local credit
 * union) still deserves a name on its card. So the input is never blocked on the search: an
 * empty, failed or unconfigured catalog simply means no catalog suggestions, exactly the
 * behaviour this field had before. See `docs/features/bank-logos.md`.
 */
export function BankPicker({ id, value, placeholder, accountType, onChange }: BankPickerProps) {
  const { t } = useTranslation()
  const [country, setCountry] = useState(DEFAULT_BANK_COUNTRY)
  // Suggestions are dismissed on pick, and re-armed on the next keystroke (or focus for insurers):
  // without this, choosing "Crédit Agricole" leaves a list still matching "Crédit Agricole" open.
  const [showSuggestions, setShowSuggestions] = useState(false)

  const offersInsurers = accountType !== undefined && INSURER_ACCOUNT_TYPES.has(accountType)
  const { data: countries } = useBankCountries()
  const showCountryPicker = Array.isArray(countries) && countries.length > 1
  const { data: institutions } = useSearchInstitutions(value.trim(), country)

  const suggestions = useMemo<PickerSuggestion[]>(() => {
    if (!showSuggestions) return []
    const items: PickerSuggestion[] = []
    const seen = new Set<string>()

    if (offersInsurers) {
      const badge = t('accounts.insurerBadge')
      for (const insurer of matchInsurerProviders(value)) {
        seen.add(normalizeName(insurer.name))
        items.push({
          key: `insurer:${insurer.name}`,
          name: insurer.name,
          logoUrl: null,
          badge,
        })
      }
    }

    for (const institution of institutions ?? []) {
      if (seen.has(normalizeName(institution.name))) continue
      items.push({
        key: institution.id,
        name: institution.name,
        logoUrl: institution.logoUrl,
        badge: institution.country,
        institutionId: institution.id,
      })
    }

    return items
  }, [showSuggestions, offersInsurers, value, institutions, t])

  return (
    <div
      className="min-w-0 space-y-2"
      onBlur={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) {
          setShowSuggestions(false)
        }
      }}
    >
      <div className="flex min-w-0 items-start gap-2">
        <Input
          id={id}
          value={value}
          placeholder={placeholder}
          autoComplete="off"
          className="min-w-0 flex-1"
          onFocus={() => {
            if (offersInsurers) setShowSuggestions(true)
          }}
          onKeyDown={(e) => {
            if (e.key === 'Escape') setShowSuggestions(false)
          }}
          onChange={(e) => {
            setShowSuggestions(true)
            // The id belongs to the bank that was picked; the moment the text diverges from it
            // the account would otherwise be saved under one bank's name and another's logo.
            onChange(e.target.value, undefined)
          }}
        />
        {showCountryPicker && (
          <BankCountrySelect value={country} onChange={setCountry} silentFallback />
        )}
      </div>

      {suggestions.length > 0 && (
        <ul className="max-h-40 space-y-1 overflow-y-auto rounded-xl border p-1">
          {suggestions.map((item) => (
            <li key={item.key}>
              <button
                type="button"
                className="flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-sm hover:bg-muted"
                onClick={() => {
                  setShowSuggestions(false)
                  onChange(item.name, item.institutionId)
                }}
              >
                <Avatar className="size-4 shrink-0 rounded-sm after:rounded-sm">
                  {item.logoUrl && (
                    <AvatarImage src={item.logoUrl} alt="" className="rounded-sm object-contain" />
                  )}
                  <AvatarFallback className="rounded-sm bg-transparent">
                    <Bank className="size-4 text-muted-foreground" />
                  </AvatarFallback>
                </Avatar>
                <span className="min-w-0 truncate">{item.name}</span>
                <span className="ml-auto shrink-0 text-xs text-muted-foreground">{item.badge}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
