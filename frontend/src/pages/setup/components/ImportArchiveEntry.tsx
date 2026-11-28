import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { UploadSimple } from '@phosphor-icons/react'
import { Button } from '@/components/ui/button'
import { canImportArchive } from '@/features/setup/archive-import'
import { useSetupStatus } from '@/features/setup/hooks'
import { useAppStore } from '@/stores/app-store'
import { ImportArchiveDialog } from './ImportArchiveDialog'

/**
 * The secondary action of the first setup screen, Docker and desktop alike: restore an existing
 * Picsou instance from an archive. Hidden once an import is no longer possible (an account exists
 * or setup is past its first step) and in demo mode.
 */
export function ImportArchiveEntry() {
  const { t } = useTranslation()
  const demoMode = useAppStore(s => s.demoMode)
  const { data: status } = useSetupStatus(!demoMode)
  const [open, setOpen] = useState(false)
  // Reload during the restart, or an import that failed at start: the server still reports it.
  const reported = status?.archiveImport?.state !== 'done' ? status?.archiveImport : undefined
  const [dismissed, setDismissed] = useState(false)

  if (demoMode) return null
  const allowed = canImportArchive(status)
  const showDialog = open || (allowed && reported !== undefined && !dismissed)
  if (!allowed && !showDialog) return null

  return (
    <>
      {/* The dialog outlives the button: a status refetch during the restart must not close it. */}
      {allowed && (
        <Button
          type="button"
          variant="ghost"
          onClick={() => setOpen(true)}
          className="h-10 rounded-lg px-4 text-sm font-medium text-muted-foreground hover:text-foreground"
        >
          <UploadSimple className="size-4" aria-hidden="true" />
          {t('setup.archive.entry')}
        </Button>
      )}
      <ImportArchiveDialog
        open={showDialog}
        onOpenChange={next => {
          setOpen(next)
          if (!next) setDismissed(true)
        }}
        desktop={Boolean(status?.desktopMode)}
        reported={dismissed ? undefined : reported}
      />
    </>
  )
}
