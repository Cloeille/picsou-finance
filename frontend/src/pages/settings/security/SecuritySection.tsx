import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Loader2, ShieldCheck, ShieldOff, KeyRound, Download } from 'lucide-react'
import { useMfaStatus } from '@/features/mfa/hooks'
import { MfaEnrollDialog } from './MfaEnrollDialog'
import { MfaDisableDialog } from './MfaDisableDialog'
import { RecoveryCodesDialog } from './RecoveryCodesDialog'
import { SessionsList } from './SessionsList'
import { ExportDataDialog } from './ExportDataDialog'

/**
 * Top-level "Security" panel rendered as the contents of the SectionCard in
 * SettingsPage. Splits into two visual blocks:
 *   - 2FA status + actions (enable/disable/regenerate codes)
 *   - persistent session list (Remember Me devices)
 * Mobile-responsive throughout: dialogs scroll, buttons stack on narrow
 * widths, and session rows reflow into a vertical layout.
 */
export function SecuritySection() {
  const { t } = useTranslation()
  const { data: status, isLoading } = useMfaStatus()
  const [enrollOpen, setEnrollOpen] = useState(false)
  const [disableOpen, setDisableOpen] = useState(false)
  const [regenerateOpen, setRegenerateOpen] = useState(false)
  const [exportOpen, setExportOpen] = useState(false)

  return (
    <div className="space-y-6">
      {/* ── 2FA block ─────────────────────────────────────────────────── */}
      <div className="space-y-3">
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div className="flex items-center gap-2">
            {status?.enabled ? (
              <ShieldCheck className="size-5 text-emerald-600 dark:text-emerald-400" />
            ) : (
              <ShieldOff className="size-5 text-muted-foreground" />
            )}
            <div>
              <p className="text-sm font-medium">{t('settings.mfaStatus')}</p>
              <p className="text-xs text-muted-foreground">
                {isLoading
                  ? '...'
                  : status?.enabled
                    ? t('settings.mfaEnabled')
                    : t('settings.mfaDisabled')}
                {status?.enabled && (
                  <>
                    {' · '}
                    {t('settings.mfaRecoveryCodesRemaining', {
                      count: status.remainingRecoveryCodes,
                    })}
                  </>
                )}
              </p>
            </div>
          </div>

          {isLoading ? (
            <Loader2 size={16} className="animate-spin text-muted-foreground" />
          ) : status?.enabled ? (
            <Button variant="destructive" size="sm" onClick={() => setDisableOpen(true)}>
              {t('settings.mfaDisable')}
            </Button>
          ) : (
            <Button size="sm" onClick={() => setEnrollOpen(true)}>
              {t('settings.mfaEnable')}
            </Button>
          )}
        </div>

        {status?.enabled && (
          <Button
            variant="outline"
            size="sm"
            onClick={() => setRegenerateOpen(true)}
            className="w-full sm:w-auto"
          >
            <KeyRound size={14} className="mr-1.5" />
            {t('settings.mfaRegenerate')}
          </Button>
        )}
      </div>

      {/* ── GDPR data export block ───────────────────────────────────── */}
      <div className="space-y-2 pt-4 border-t">
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div>
            <p className="text-sm font-medium">{t('settings.exportTitle')}</p>
            <p className="text-xs text-muted-foreground">
              {t('settings.exportShortDesc')}
            </p>
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={() => setExportOpen(true)}
            className="w-full sm:w-auto"
          >
            <Download size={14} className="mr-1.5" />
            {t('settings.exportButton')}
          </Button>
        </div>
      </div>

      {/* ── Persistent sessions block ────────────────────────────────── */}
      <div className="space-y-2 pt-4 border-t">
        <div>
          <p className="text-sm font-medium">{t('settings.sessions')}</p>
          <p className="text-xs text-muted-foreground">
            {t('settings.sessionsDescription')}
          </p>
        </div>
        <SessionsList />
      </div>

      <MfaEnrollDialog open={enrollOpen} onOpenChange={setEnrollOpen} />
      <MfaDisableDialog open={disableOpen} onOpenChange={setDisableOpen} />
      <RecoveryCodesDialog open={regenerateOpen} onOpenChange={setRegenerateOpen} />
      <ExportDataDialog open={exportOpen} onOpenChange={setExportOpen} />
    </div>
  )
}
