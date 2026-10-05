import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { MapPin, Loader2 } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { useAddressSearch } from '@/features/geocoding/hooks'
import type { GeocodeSuggestion } from '@/types/api'

interface AddressAutocompleteProps {
  value: string
  onChange: (value: string) => void
  /** Fired when a suggestion is picked, so the caller can fill postcode and city too. */
  onSelect: (suggestion: GeocodeSuggestion) => void
  placeholder?: string
  id?: string
}

/**
 * Address field with suggestions from the Base Adresse Nationale.
 *
 * <p>Deliberately not a map. The project has no cartography dependency, and adding one would
 * be the first third-party UI library since the shadcn baseline — for a feature where the
 * resolved address label already tells the user everything they need to confirm.
 */
export function AddressAutocomplete({
  value, onChange, onSelect, placeholder, id,
}: AddressAutocompleteProps) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [touched, setTouched] = useState(false)
  const { data: suggestions = [], isFetching } = useAddressSearch(value, touched)

  const pick = (suggestion: GeocodeSuggestion) => {
    onSelect(suggestion)
    setOpen(false)
  }

  return (
    <div className="relative">
      <div className="relative">
        <Input
          id={id}
          value={value}
          placeholder={placeholder}
          autoComplete="off"
          onChange={e => {
            onChange(e.target.value)
            setTouched(true)
            setOpen(true)
          }}
          // Delayed so a click on a suggestion registers before the list unmounts.
          onBlur={() => setTimeout(() => setOpen(false), 150)}
          onFocus={() => value.trim().length >= 3 && setOpen(true)}
        />
        {isFetching && (
          <Loader2 className="absolute right-3 top-1/2 size-4 -translate-y-1/2 animate-spin text-muted-foreground" />
        )}
      </div>

      {open && suggestions.length > 0 && (
        <ul className="absolute z-50 mt-1 max-h-60 w-full overflow-auto rounded-lg border bg-popover p-1 shadow-md">
          {suggestions.map(suggestion => (
            <li key={`${suggestion.label}-${suggestion.inseeCode}`}>
              <button
                type="button"
                className="flex w-full items-start gap-2 rounded-lg px-3 py-2 text-left text-sm hover:bg-accent"
                onMouseDown={e => e.preventDefault()}
                onClick={() => pick(suggestion)}
              >
                <MapPin className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                <span>{suggestion.label}</span>
              </button>
            </li>
          ))}
        </ul>
      )}

      {open && !isFetching && value.trim().length >= 3 && suggestions.length === 0 && (
        <p className="mt-1 text-xs text-muted-foreground">{t('property.address.noMatch')}</p>
      )}
    </div>
  )
}
