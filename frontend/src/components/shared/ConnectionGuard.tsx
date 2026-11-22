import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { useAppStore } from '@/stores/app-store'
import { useConnectivityStore } from '@/stores/connectivity-store'
import { useTranslation } from 'react-i18next'
import { CircleNotch, WifiSlash, ArrowsClockwise } from '@phosphor-icons/react'
import { Button } from '@/components/ui/button'
import picsouLogo from '@/assets/horizontal-white-picsou.svg'

export function ConnectionGuard({ children }: { children: ReactNode }) {
  const { t } = useTranslation()
  const demoMode = useAppStore((s) => s.demoMode)
  const desktopMode = useAppStore((s) => s.desktopMode)
  const { setConnected, setChecking } = useConnectivityStore()
  const [status, setStatus] = useState<'checking' | 'connected' | 'failed'>('checking')

  const checkHealth = useCallback(async () => {
    setChecking(true)
    try {
      const res = await fetch('/actuator/health', { signal: AbortSignal.timeout(5000) })
      if (res.ok) {
        setConnected(true)
        setStatus('connected')
      } else {
        setConnected(false)
        setStatus('failed')
      }
    } catch {
      setConnected(false)
      setStatus('failed')
    } finally {
      setChecking(false)
    }
  }, [setConnected, setChecking])

  useEffect(() => {
    // In demo mode the render below already short-circuits to children, so
    // there's no status to set here — just skip the health probe.
    if (demoMode) return
    // checkHealth is an async fetch-on-mount: it probes /actuator/health and
    // synchronizes the result into React + the connectivity store. The lint
    // rule can't see past the useCallback wrapper to know the setState calls
    // are gated behind I/O, so it flags the leading setChecking(true). This is
    // the legitimate "synchronize with an external system on mount" case.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    checkHealth()
  }, [demoMode, checkHealth])

  if (demoMode || status === 'connected') {
    return <>{children}</>
  }

  return (
    <div className="min-h-screen bg-background flex items-center justify-center">
      <div className="flex flex-col items-center gap-6 text-center max-w-sm mx-4">
        <img src={picsouLogo} alt="Picsou" className="h-8 w-auto opacity-90 brightness-0 dark:invert" />
        {status === 'checking' ? (
          <>
            <CircleNotch className="size-8 text-primary animate-spin" />
            <p className="text-sm text-muted-foreground">
              {desktopMode ? t('error.connectingDesktop') : t('error.connecting')}
            </p>
          </>
        ) : (
          <>
            <WifiSlash className="size-10 text-destructive" />
            <h2 className="text-lg font-semibold">{t('error.noConnection')}</h2>
            <p className="text-sm text-muted-foreground">
              {desktopMode ? t('error.noConnectionDescDesktop') : t('error.noConnectionDesc')}
            </p>
            <Button onClick={checkHealth} variant="outline">
              <ArrowsClockwise className="size-4" />
              {t('common.retry')}
            </Button>
          </>
        )}
      </div>
    </div>
  )
}
