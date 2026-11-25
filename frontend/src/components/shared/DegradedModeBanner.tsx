import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppStore } from '@/stores/app-store'
import { useConnectivityStore } from '@/stores/connectivity-store'
import { WifiSlash, X } from '@phosphor-icons/react'

/**
 * Gate: render the banner only while we're genuinely offline. When the
 * connection comes back this returns null, which unmounts `OfflineBanner`
 * and discards its `dismissed` state — so the next outage shows a fresh
 * banner without any effect or render-phase state reset.
 */
export function DegradedModeBanner() {
  const { isConnected, isChecking } = useConnectivityStore()

  if (isConnected || isChecking) return null

  return <OfflineBanner />
}

function OfflineBanner() {
  const { t } = useTranslation()
  const desktopMode = useAppStore((s) => s.desktopMode)
  const [dismissed, setDismissed] = useState(false)

  if (dismissed) return null

  return (
    <div className="bg-destructive/10 border-b border-destructive/20 px-4 py-2 flex items-center justify-center gap-3">
      <WifiSlash className="size-4 text-destructive shrink-0" />
      <p className="text-sm text-destructive font-medium">
        {desktopMode ? t('error.degradedModeDesktop') : t('error.degradedMode')}
      </p>
      <button
        onClick={() => setDismissed(true)}
        className="text-muted-foreground hover:text-foreground transition-colors"
        aria-label={t('common.close')}
      >
        <X className="size-4" />
      </button>
    </div>
  )
}
