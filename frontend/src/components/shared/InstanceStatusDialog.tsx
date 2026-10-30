import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { useInstanceStatus } from '@/features/admin/hooks'
import type { InstanceStatusIssue } from '@/features/admin/api'
import { useTelemetryConsentPrompt } from '@/features/telemetry/hooks'
import { useAppStore } from '@/stores/app-store'
import { useAuthStore } from '@/stores/auth-store'
import { instanceStatusFingerprint } from './instance-status'

function explanationKey(issue: InstanceStatusIssue): string {
  if (issue.code === 'SIDECAR_OUTDATED') return 'admin.instanceStatus.outdated'
  if (issue.expectedBase != null) return 'admin.instanceStatus.unreachableWithBase'
  return 'admin.instanceStatus.unreachable'
}

/**
 * Admin-only alert when a sidecar the household uses is unreachable or still on a pre-base image,
 * or when a newer Picsou release exists. Dismiss persists a fingerprint; the dialog returns only
 * when that fingerprint changes. A clean status clears it, so the same problem coming back alerts again. Stays closed while the telemetry consent dialog is on screen.
 */
export function InstanceStatusDialog() {
  const { t } = useTranslation()
  const isAdmin = useAuthStore((s) => s.user?.role === 'ADMIN')
  const demoMode = useAppStore((s) => s.demoMode)
  const sidebarPromptSeen = useAppStore((s) => s.hasSeenSidebarStylePrompt)
  const dismissedFingerprint = useAppStore((s) => s.dismissedInstanceStatusFingerprint)
  const dismiss = useAppStore((s) => s.setDismissedInstanceStatusFingerprint)
  const enabled = isAdmin && !demoMode
  const { data: status } = useInstanceStatus(enabled)
  const telemetryConsent = useTelemetryConsentPrompt()

  const fingerprint = status ? instanceStatusFingerprint(status) : null
  const hasAlert = (status?.issues.length ?? 0) > 0 || status?.update != null
  const open =
    enabled &&
    sidebarPromptSeen &&
    !telemetryConsent.isLoading &&
    !telemetryConsent.open &&
    hasAlert &&
    fingerprint != null &&
    fingerprint !== dismissedFingerprint

  const clean = status != null && !hasAlert
  useEffect(() => {
    if (clean && dismissedFingerprint != null) dismiss(null)
  }, [clean, dismissedFingerprint, dismiss])

  function remember() {
    if (fingerprint) dismiss(fingerprint)
  }

  return (
    <Dialog open={open} onOpenChange={(next) => { if (!next) remember() }}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{t('admin.instanceStatus.title')}</DialogTitle>
          <DialogDescription>{t('admin.instanceStatus.description')}</DialogDescription>
        </DialogHeader>

        {status && (
          <div className="space-y-4 text-sm">
            {status.issues.length > 0 && (
              <section className="space-y-2">
                <h3 className="font-medium">{t('admin.instanceStatus.issuesTitle')}</h3>
                <ul className="space-y-3">
                  {status.issues.map((issue) => (
                    <li key={`${issue.code}:${issue.sidecar}`}>
                      <p className="font-medium">{issue.sidecar}</p>
                      <p className="text-muted-foreground">{t(explanationKey(issue))}</p>
                      {issue.expectedBase != null && (
                        <p className="text-muted-foreground">
                          {t('admin.instanceStatus.expectedBase')}{' '}
                          <span className="font-medium text-foreground">{issue.expectedBase}</span>
                        </p>
                      )}
                    </li>
                  ))}
                </ul>
                <p className="text-muted-foreground">{t('admin.instanceStatus.fixIntro')}</p>
                <p><code className="font-mono text-xs">docker compose pull</code></p>
                <p><code className="font-mono text-xs">docker compose up -d</code></p>
                <p className="text-muted-foreground">{t('admin.instanceStatus.fixBuildIntro')}</p>
                <p><code className="font-mono text-xs">docker compose up -d --build</code></p>
              </section>
            )}

            {status.update && (
              <section className="space-y-1">
                <h3 className="font-medium">{t('admin.instanceStatus.updateTitle')}</h3>
                <p>
                  <span>{status.currentVersion}</span>
                  {' → '}
                  <span>{status.update.latestVersion}</span>
                </p>
                <a
                  href={status.update.releaseUrl}
                  target="_blank"
                  rel="noreferrer"
                  className="font-medium underline underline-offset-4"
                >
                  {t('admin.instanceStatus.releaseNotes')}
                </a>
              </section>
            )}
          </div>
        )}

        <DialogFooter>
          <Button variant="outline" onClick={remember}>
            {t('admin.instanceStatus.later')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
