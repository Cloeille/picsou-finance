import { useTranslation } from 'react-i18next'
import { FileQuestion } from 'lucide-react'
import { ErrorScreen } from './ErrorScreen'

export function NotFoundPage() {
  const { t } = useTranslation()
  return (
    <ErrorScreen
      icon={<FileQuestion className="size-12 text-muted-foreground" />}
      code="404"
      message={t('error.notFound')}
    />
  )
}
