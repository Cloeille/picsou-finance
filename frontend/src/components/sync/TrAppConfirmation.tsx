import { useEffect, useState } from 'react'
import { Loader2, Smartphone } from 'lucide-react'
import { useTranslation } from 'react-i18next'

function formatCountdown(totalSeconds: number): string {
  const safe = Math.max(0, totalSeconds)
  return `${Math.floor(safe / 60)}:${String(safe % 60).padStart(2, '0')}`
}

interface TrAppConfirmationProps {
  /** Window the member has to confirm the login in the Trade Republic app. */
  countdownInSeconds: number
  className?: string
}

/**
 * Prompt shown while `auth/complete` stays open for the in-app confirmation. The countdown
 * starts when this mounts, i.e. right after `auth/initiate` answered.
 */
export function TrAppConfirmation({ countdownInSeconds, className }: TrAppConfirmationProps) {
  const { t } = useTranslation()
  const [deadline] = useState(() => Date.now() + countdownInSeconds * 1000)
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])

  const remaining = Math.max(0, Math.ceil((deadline - now) / 1000))

  return (
    <div className={`flex items-center gap-3 ${className ?? ''}`} data-testid="tr-app-confirmation">
      <Smartphone className="size-5 shrink-0 text-muted-foreground" />
      <div className="flex-1 space-y-1">
        <p className="text-sm">{t('sync.tr.appConfirmPrompt')}</p>
        <p className="text-xs text-muted-foreground">
          {t('sync.tr.appConfirmHint')} {t('sync.tr.countdownLabel')}{' '}
          <span data-testid="tr-countdown" className="font-mono">
            {formatCountdown(remaining)}
          </span>
        </p>
      </div>
      <Loader2 className="size-4 shrink-0 animate-spin text-muted-foreground" />
    </div>
  )
}
