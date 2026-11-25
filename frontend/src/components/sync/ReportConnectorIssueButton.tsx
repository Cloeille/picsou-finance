import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ArrowSquareOut, Bug } from '@phosphor-icons/react'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { APP_VERSION } from '@/lib/app-version'
import {
  buildConnectorReport,
  type BankConnectorId,
  type ConnectorRuntime,
} from '@/lib/connector-report'
import { isInsideDesktopShell } from '@/features/desktop/shell'
import { useAppStore } from '@/stores/app-store'

export interface ReportConnectorIssueButtonProps {
  connectorId: BankConnectorId
  errorCode?: string | null
  /** Compact icon-only trigger for dense connection rows (e.g. Enable Banking failed rows). */
  iconOnly?: boolean
}

/**
 * Lets the user report a broken bank connector via a prefilled GitHub issue
 * link. Shows a confirmation dialog with the exact text that will be prefilled
 * before opening GitHub in a new tab; Picsou itself sends nothing.
 */
export function ReportConnectorIssueButton({
  connectorId,
  errorCode,
  iconOnly = false,
}: ReportConnectorIssueButtonProps) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const demoMode = useAppStore((s) => s.demoMode)
  const desktopMode = useAppStore((s) => s.desktopMode) || isInsideDesktopShell()
  const runtime: ConnectorRuntime = desktopMode ? 'desktop' : 'web'

  const report = useMemo(
    () =>
      buildConnectorReport({
        connectorId,
        errorCode,
        appVersion: APP_VERSION,
        runtime,
      }),
    [connectorId, errorCode, runtime],
  )

  if (demoMode) return null

  return (
    <>
      {iconOnly ? (
        <Button
          type="button"
          size="icon-sm"
          variant="ghost"
          title={t('sync.reportProblem.action')}
          aria-label={t('sync.reportProblem.action')}
          onClick={() => setOpen(true)}
        >
          <Bug className="size-4" />
        </Button>
      ) : (
        <Button
          type="button"
          size="sm"
          variant="outline"
          className="bg-background text-foreground"
          onClick={() => setOpen(true)}
        >
          <Bug className="size-4" />
          {t('sync.reportProblem.action')}
        </Button>
      )}

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>{t('sync.reportProblem.dialogTitle')}</DialogTitle>
            <DialogDescription>{t('sync.reportProblem.dialogDescription')}</DialogDescription>
          </DialogHeader>

          <div className="space-y-3 rounded-lg border border-border/60 bg-muted/40 p-3">
            <div className="space-y-1">
              <p className="text-sm font-medium text-muted-foreground">
                {t('common.labelColon', { label: t('sync.reportProblem.titleLabel') })}
              </p>
              <p data-testid="connector-report-title" className="break-words text-sm text-foreground">
                {report.title}
              </p>
            </div>
            <div className="space-y-1">
              <p className="text-sm font-medium text-muted-foreground">
                {t('common.labelColon', { label: t('sync.reportProblem.bodyLabel') })}
              </p>
              <pre
                data-testid="connector-report-body"
                className="whitespace-pre-wrap break-words font-sans text-sm text-foreground"
              >
                {report.body}
              </pre>
            </div>
          </div>

          <p className="text-sm text-muted-foreground">{t('sync.reportProblem.privacyNote')}</p>

          <DialogFooter className="gap-2">
            <Button type="button" variant="outline" onClick={() => setOpen(false)}>
              {t('common.cancel')}
            </Button>
            <Button asChild>
              <a
                href={report.url}
                target="_blank"
                rel="noopener noreferrer"
              >
                <ArrowSquareOut className="size-4" />
                {t('sync.reportProblem.openGithub')}
              </a>
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}
