import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Loader2, Trash2, Smartphone, Monitor, ShieldCheck } from 'lucide-react'
import {
  useSessions,
  useRevokeSession,
  useRevokeAllSessionsExceptCurrent,
} from '@/features/mfa/hooks'
import type { SessionItem } from '@/features/mfa/api'
import { resolveLocale } from '@/i18n/locales'

export function SessionsList() {
  const { t, i18n } = useTranslation()
  const { data: sessions, isLoading } = useSessions()
  const revoke = useRevokeSession()
  const revokeOthers = useRevokeAllSessionsExceptCurrent()

  if (isLoading) {
    return (
      <div className="flex justify-center py-4">
        <Loader2 size={20} className="animate-spin text-muted-foreground" />
      </div>
    )
  }

  if (!Array.isArray(sessions) || sessions.length === 0) {
    return (
      <p className="text-sm text-muted-foreground py-2">{t('settings.sessionsEmpty')}</p>
    )
  }

  const otherCount = sessions.filter(s => !s.current).length

  return (
    <div className="space-y-3">
      <ul className="divide-y rounded-lg border">
        {sessions.map(s => (
          <SessionRow
            key={s.id}
            session={s}
            locale={resolveLocale(i18n.language).intlLocale}
            onRevoke={() => revoke.mutate(s.id)}
            disabled={revoke.isPending && revoke.variables === s.id}
          />
        ))}
      </ul>

      {otherCount > 0 && (
        <div className="flex justify-end">
          <Button
            variant="outline"
            size="sm"
            onClick={() => revokeOthers.mutate()}
            disabled={revokeOthers.isPending}
          >
            {revokeOthers.isPending && <Loader2 size={14} className="mr-1.5 animate-spin" />}
            {t('settings.sessionsRevokeAllOthers')}
          </Button>
        </div>
      )}
    </div>
  )
}

function SessionRow({
  session,
  locale,
  onRevoke,
  disabled,
}: {
  session: SessionItem
  locale: string
  onRevoke: () => void
  disabled: boolean
}) {
  const { t } = useTranslation()
  const isMobile = isProbablyMobile(session.userAgent)
  const Icon = isMobile ? Smartphone : Monitor

  return (
    <li className="flex flex-col sm:flex-row sm:items-center gap-2 sm:gap-3 p-3">
      <Icon className="size-5 text-muted-foreground shrink-0 hidden sm:block" />
      <div className="flex-1 min-w-0 space-y-0.5">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium truncate">
            {prettyUserAgent(session.userAgent) || 'Unknown device'}
          </span>
          {session.current && (
            <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
              {t('settings.sessionsCurrent')}
            </span>
          )}
          {session.trustedFor2fa && (
            <span className="inline-flex items-center gap-1 rounded-full bg-emerald-500/10 px-2 py-0.5 text-xs font-medium text-emerald-700 dark:text-emerald-400">
              <ShieldCheck className="size-3" />
              {t('settings.sessionsTrusted')}
            </span>
          )}
        </div>
        <p className="text-xs text-muted-foreground">
          {session.ipPrefix && <span className="font-mono">{session.ipPrefix}*</span>}
          {session.ipPrefix && ' · '}
          {t('settings.sessionsLastUsed')}: {formatDate(session.lastUsedAt, locale)}
        </p>
      </div>
      {!session.current && (
        <Button
          variant="ghost"
          size="sm"
          onClick={onRevoke}
          disabled={disabled}
          className="self-end sm:self-auto"
        >
          {disabled ? (
            <Loader2 size={14} className="mr-1.5 animate-spin" />
          ) : (
            <Trash2 size={14} className="mr-1.5" />
          )}
          {t('settings.sessionsRevoke')}
        </Button>
      )}
    </li>
  )
}

// ─── helpers ───────────────────────────────────────────────────────────

function isProbablyMobile(ua: string | null): boolean {
  if (!ua) return false
  return /Mobile|Android|iPhone|iPad/i.test(ua)
}

/**
 * Best-effort short label from a User-Agent string. Real UA parsing is a deep
 * rabbit hole — we extract just enough for "is this my phone or my laptop?"
 * recognition. The full UA is intentionally not shown (noisy + low value).
 */
function prettyUserAgent(ua: string | null): string {
  if (!ua) return ''
  const browser =
    /Edg\//.test(ua) ? 'Edge' :
    /Chrome\//.test(ua) && !/Chromium/.test(ua) ? 'Chrome' :
    /Firefox\//.test(ua) ? 'Firefox' :
    /Safari\//.test(ua) && !/Chrome\//.test(ua) ? 'Safari' :
    'Browser'
  const os =
    /Windows/.test(ua) ? 'Windows' :
    /Mac OS X|Macintosh/.test(ua) ? 'macOS' :
    /Android/.test(ua) ? 'Android' :
    /iPhone|iPad|iOS/.test(ua) ? 'iOS' :
    /Linux/.test(ua) ? 'Linux' :
    ''
  return os ? `${browser} · ${os}` : browser
}

function formatDate(iso: string, locale: string): string {
  try {
    return new Date(iso).toLocaleString(locale, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    })
  } catch {
    return iso
  }
}
