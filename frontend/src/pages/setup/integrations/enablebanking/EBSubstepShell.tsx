import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { Progress } from '@/components/ui/progress'
import { Button } from '@/components/ui/button'
import { useSetupFlowStore } from '@/stores/setup-flow-store'
import { nextIntegrationRoute } from '../navigation'

interface EBSubstepShellProps {
  current: number
  total: number
  children: ReactNode
  /** Shown as an outlined button bottom-right. Defaults to navigating
   *  to the next integration. Override to control "Skip" behavior. */
  onSkip?: () => void
}

/**
 * Unified chrome for every EB substep: the "Step X of 5 — Enable Banking"
 * pill, a dedicated sub-progress bar, and the "Skip this integration"
 * escape hatch that routes past EB without flipping any DB flag.
 */
export function EBSubstepShell({ current, total, children, onSkip }: EBSubstepShellProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const selected = useSetupFlowStore((s) => s.selectedIntegrations)

  const pct = Math.round((current / total) * 100)

  const handleSkip = () => {
    if (onSkip) {
      onSkip()
      return
    }
    navigate(nextIntegrationRoute('enablebanking', selected))
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-4">
        <p className="text-sm font-medium text-muted-foreground">
          {t('setup.enablebanking.substep', { current, total })}
        </p>
        <button
          type="button"
          onClick={handleSkip}
          className="text-xs text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
        >
          {t('setup.enablebanking.skip')}
        </button>
      </div>
      <Progress value={pct} className="h-1 rounded-full" />

      <div>{children}</div>

      <div className="sm:hidden">
        <Button
          variant="ghost"
          size="sm"
          onClick={handleSkip}
          className="w-full text-muted-foreground"
        >
          {t('setup.enablebanking.skip')}
        </Button>
      </div>
    </div>
  )
}
