import { useTranslation } from 'react-i18next'
import { ShieldWarning } from '@phosphor-icons/react'
import { ErrorScreen } from './ErrorScreen'

export function ForbiddenPage() {
  const { t } = useTranslation()
  return (
    <ErrorScreen
      icon={<ShieldWarning className="size-12 text-destructive" />}
      code="403"
      message={t('error.forbidden')}
    />
  )
}
