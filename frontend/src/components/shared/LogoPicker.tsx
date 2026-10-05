import { useTranslation } from 'react-i18next'
import { cn } from '@/lib/utils'
import { LOGO_KEYS, WALLET_LOGO_CHOICES } from '@/lib/provider-logos'

interface LogoPickerProps {
  value: string
  onChange: (logoKey: string) => void
}

/**
 * Picks the bundled asset an account shows, the way `ColorPicker` picks its color.
 *
 * Only on-chain wallets get this today (see `AccountForm`): every other account derives its
 * logo from the connector or from its provider name, with nothing for the user to decide.
 * A wallet is the exception — the chain can't tell us whether the address lives on a Ledger.
 */
export function LogoPicker({ value, onChange }: LogoPickerProps) {
  const { t } = useTranslation()

  return (
    <div className="flex flex-wrap gap-2">
      {WALLET_LOGO_CHOICES.map(({ key, labelKey }) => (
        <button
          key={key}
          type="button"
          aria-pressed={value === key}
          aria-label={t(labelKey)}
          title={t(labelKey)}
          className={cn(
            'size-10 rounded-full border-2 bg-white transition-[border-color,box-shadow]',
            value === key ? 'border-background ring-2 ring-foreground' : 'border-transparent'
          )}
          onClick={() => onChange(key)}
        >
          <img src={LOGO_KEYS[key]} alt="" className="size-full object-contain p-2" />
        </button>
      ))}
    </div>
  )
}
