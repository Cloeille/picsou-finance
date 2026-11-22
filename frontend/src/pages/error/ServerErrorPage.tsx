import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router'
import { WarningOctagon } from '@phosphor-icons/react'
import { ErrorScreen } from './ErrorScreen'

export function ServerErrorPage() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  return (
    <ErrorScreen
      icon={<WarningOctagon className="size-12 text-destructive" />}
      code={searchParams.get('code') || '500'}
      message={t('error.serverError')}
    />
  )
}
