import { useTranslation } from 'react-i18next'
import { AlertTriangle } from 'lucide-react'
import { Button } from '@/components/ui/button'

interface ErrorStateProps {
  /** Heading; defaults to a localized "Error". */
  title?: string
  message?: string
  onRetry?: () => void
}

export function ErrorState({ title, message, onRetry }: ErrorStateProps) {
  const { t } = useTranslation()
  return (
    <div className="flex flex-col items-center justify-center py-12 text-center">
      <AlertTriangle className="size-10 text-destructive mb-4" />
      <h3 className="text-lg font-medium">{title ?? t('common.errorTitle')}</h3>
      {message && (
        <p className="mt-1 text-sm text-muted-foreground">{message}</p>
      )}
      {onRetry && (
        <Button variant="outline" onClick={onRetry} className="mt-4">
          {t('common.retry')}
        </Button>
      )}
    </div>
  )
}
