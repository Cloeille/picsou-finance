import { useTranslation } from 'react-i18next'
import { ExternalLink } from 'lucide-react'
import { revolutPhaseLabel, revolutViewerUrl } from '@/features/sync/revolut-phase'
import type { SyncProgress } from '@/types/api'

/**
 * Current Revolut sync phase as text. While Revolut asks for a captcha it also offers the way to
 * solve it: a link to the sidecar's remote view (Picsou never solves a captcha itself).
 */
export function RevolutPhaseLine({ progress }: { progress: SyncProgress | undefined }) {
  const { t } = useTranslation()
  const viewerUrl = revolutViewerUrl(progress, window.location.hostname)
  return (
    <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
      <span>{revolutPhaseLabel(t, progress)}</span>
      {viewerUrl && (
        <a
          href={viewerUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-1 font-medium text-foreground underline underline-offset-2"
        >
          <ExternalLink className="size-3.5" />
          {t('sync.revolut.openViewer')}
        </a>
      )}
    </span>
  )
}
