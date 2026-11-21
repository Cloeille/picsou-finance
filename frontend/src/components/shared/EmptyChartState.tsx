import { useTranslation } from 'react-i18next'
import { Moon } from '@phosphor-icons/react'

interface EmptyChartStateProps {
  title?: string
  description?: string
}

export function EmptyChartState({ title, description }: EmptyChartStateProps) {
  const { t } = useTranslation()

  return (
    <div className="flex h-[250px] flex-col items-center justify-center gap-3 rounded-lg border border-dashed border-border/50 bg-muted/20">
      <div className="flex size-12 items-center justify-center rounded-full bg-muted">
        <Moon className="size-5 text-muted-foreground" weight="light" />
      </div>
      <div className="text-center">
        <p className="text-sm font-medium text-muted-foreground">
          {title ?? t('dashboard.marketsClosed')}
        </p>
        {description && (
          <p className="mt-1 text-sm text-muted-foreground/70">{description}</p>
        )}
      </div>
    </div>
  )
}
