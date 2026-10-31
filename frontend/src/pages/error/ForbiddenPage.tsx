import { useTranslation } from 'react-i18next'
import { ShieldX } from 'lucide-react'
import { ErrorScreen } from './ErrorScreen'

export function ForbiddenPage() {
  const { t } = useTranslation()
  return (
    <ErrorScreen
      icon={<ShieldX className="size-12 text-destructive" />}
      code="403"
      message={t('error.forbidden')}
    />
  )
}
