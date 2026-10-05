import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Landmark } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { Avatar, AvatarImage, AvatarFallback } from '@/components/ui/avatar'
import { BankCountrySelect, DEFAULT_BANK_COUNTRY } from '@/components/shared/BankCountrySelect'
import { useSearchInstitutions } from '@/features/sync/hooks'

interface BankPickerProps {
  id?: string
  /** The bank's name — what is stored on the account as `provider`. */
  value: string
  placeholder?: string
  /**
   * `institutionId` is the catalog's own id for the bank that was picked, and is undefined
   * whenever the name was typed rather than chosen. The backend consumes it to resolve the
   * logo and never stores it.
   */
  onChange: (bankName: string, institutionId?: string) => void
}

/**
 * The bank field of the manual account form: free text that also searches the institution
 * catalog, so picking a bank from the list is what gives a hand-entered account a real logo.
 *
 * <p>Free text first, list second — deliberately. The catalog only exists when Enable Banking
 * is configured, and a bank Picsou cannot sync (a foreign account, a broker, the local credit
 * union) still deserves a name on its card. So the input is never blocked on the search: an
 * empty, failed or unconfigured catalog simply means no suggestions, exactly the behaviour
 * this field had before. See `docs/features/bank-logos.md`.
 */
export function BankPicker({ id, value, placeholder, onChange }: BankPickerProps) {
  const { t } = useTranslation()
  const [country, setCountry] = useState(DEFAULT_BANK_COUNTRY)
  // Suggestions are dismissed on pick, and re-armed on the next keystroke: without this,
  // choosing "Crédit Agricole" leaves a list still matching "Crédit Agricole" open under it.
  const [showSuggestions, setShowSuggestions] = useState(false)

  const { data: institutions } = useSearchInstitutions(value.trim(), country)
  const suggestions = showSuggestions ? (institutions ?? []) : []

  return (
    <div className="space-y-2">
      <div className="flex items-start gap-2">
        <Input
          id={id}
          value={value}
          placeholder={placeholder}
          autoComplete="off"
          onChange={(e) => {
            setShowSuggestions(true)
            // The id belongs to the bank that was picked; the moment the text diverges from it
            // the account would otherwise be saved under one bank's name and another's logo.
            onChange(e.target.value, undefined)
          }}
        />
        <BankCountrySelect value={country} onChange={setCountry} />
      </div>

      {suggestions.length > 0 && (
        <ul className="max-h-40 space-y-1 overflow-y-auto rounded-xl border p-1">
          {suggestions.map((institution) => (
            <li key={institution.id}>
              <button
                type="button"
                className="flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-sm hover:bg-muted"
                onClick={() => {
                  setShowSuggestions(false)
                  onChange(institution.name, institution.id)
                }}
              >
                <Avatar className="size-4 shrink-0 rounded-sm after:rounded-sm">
                  {institution.logoUrl && (
                    <AvatarImage src={institution.logoUrl} alt="" className="rounded-sm object-contain" />
                  )}
                  <AvatarFallback className="rounded-sm bg-transparent">
                    <Landmark className="size-4 text-muted-foreground" />
                  </AvatarFallback>
                </Avatar>
                <span className="min-w-0 truncate">{institution.name}</span>
                <span className="ml-auto shrink-0 text-xs text-muted-foreground">{institution.country}</span>
              </button>
            </li>
          ))}
        </ul>
      )}

      <p className="text-xs text-muted-foreground">{t('accounts.bankPickerHint')}</p>
    </div>
  )
}
