import { useTranslation } from 'react-i18next'
import type { CardNature } from '@/types/api'

/** How a bank card is debited. Renders nothing when the bank did not say. */
export function CardNatureField({ nature }: { nature?: CardNature | null }) {
  const { t } = useTranslation()
  if (!nature) return null
  return (
    <div>
      <p className="mb-1 text-xs text-muted-foreground">{t('accounts.cardNature.label')}</p>
      <p className="text-base font-medium">{t(`accounts.cardNature.${nature}`)}</p>
    </div>
  )
}
