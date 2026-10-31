import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { ServerCrash } from 'lucide-react'
import { ErrorScreen } from './ErrorScreen'

export function ServerErrorPage() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  return (
    <ErrorScreen
      icon={<ServerCrash className="size-12 text-destructive" />}
      code={searchParams.get('code') || '500'}
      message={t('error.serverError')}
    />
  )
}
