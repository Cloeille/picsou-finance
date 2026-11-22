import { useTranslation } from 'react-i18next'
import { FileDashed } from '@phosphor-icons/react'
import { ErrorScreen } from './ErrorScreen'

export function NotFoundPage() {
  const { t } = useTranslation()
  return (
    <ErrorScreen
      icon={<FileDashed className="size-12 text-muted-foreground" />}
      code="404"
      message={t('error.notFound')}
    />
  )
}
