import { CircleNotch, DownloadSimple } from '@phosphor-icons/react'
import { useTranslation } from 'react-i18next'
import { AlertBanner } from '@/components/shared/AlertBanner'
import { Button } from '@/components/ui/button'
import { useAppStore } from '@/stores/app-store'
import { useDesktopUpdateBridge, useDesktopUpdateStore } from '@/stores/desktop-update-store'

export function DesktopUpdateBanner() {
  useDesktopUpdateBridge()

  const { t } = useTranslation()
  const enabled = useDesktopUpdateStore((s) => s.enabled)
  const availableVersion = useDesktopUpdateStore((s) => s.availableVersion)
  const checking = useDesktopUpdateStore((s) => s.checking)
  const installing = useDesktopUpdateStore((s) => s.installing)
  const installUpdate = useDesktopUpdateStore((s) => s.installUpdate)
  const dismissedVersion = useAppStore((s) => s.dismissedDesktopUpdateVersion)
  const setDismissedVersion = useAppStore((s) => s.setDismissedDesktopUpdateVersion)

  if (!enabled || !availableVersion || dismissedVersion === availableVersion) {
    return null
  }

  return (
    <AlertBanner
      variant="neutral"
      role="status"
      className="mb-4"
      icon={<DownloadSimple className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />}
      action={
        <Button
          type="button"
          size="sm"
          disabled={installing || checking}
          onClick={() => void installUpdate()}
        >
          {installing && <CircleNotch className="size-3.5 animate-spin" aria-hidden="true" />}
          {installing ? t('desktop.update.installing') : t('desktop.update.install')}
        </Button>
      }
      onDismiss={() => setDismissedVersion(availableVersion)}
      dismissDisabled={installing}
    >
      <span className="font-medium tabular-nums">
        {t('desktop.update.bannerTitle', { version: availableVersion })}
      </span>
    </AlertBanner>
  )
}
