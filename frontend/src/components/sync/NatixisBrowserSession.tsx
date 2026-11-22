import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { CircleNotch, Fingerprint } from '@phosphor-icons/react'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useImportNatixisBrowserSession, useProviderStatus } from '@/features/sync/hooks'
import { extractErrorMessage, getErrorCode, getErrorStatus } from '@/lib/errors'

interface NatixisBrowserSessionProps {
  onConnected?: () => void
}

const SITE = 'epargnants.interepargne.natixis.fr'
const CONSOLE_COMMAND = 'copy(sessionStorage.refresh_token)'

/** Backend codes that have a message of their own in the Natixis panel (`sync.natixis.errors.*`). */
const UPSTREAM_ERROR_SUFFIXES: Record<string, string> = {
  UPSTREAM_UNAVAILABLE: 'serverError',
  UPSTREAM_FORMAT_CHANGED: 'formatChanged',
  INVALID_DATA: 'invalidData',
  RATE_LIMITED: 'tooManyAttempts',
}

/**
 * Passkey sign-in. Natixis now makes a new device register a passkey, which Picsou's connector cannot do:
 * the private key lives in Windows Hello, a password manager or a security key. The saver signs in on the
 * Natixis site instead and hands over the refresh token that site holds.
 */
export function NatixisBrowserSession({ onConnected }: NatixisBrowserSessionProps = {}) {
  const { t } = useTranslation()
  const [token, setToken] = useState('')
  const [error, setError] = useState<string | null>(null)
  const importSession = useImportNatixisBrowserSession()
  const status = useProviderStatus('natixis')
  const syncStatus = status.data?.syncStatus
  // The import only queues the first sync: the panel is "connected" once that sync has succeeded,
  // as for a password login, so a failure of that first sync is seen here and not after the modal closed.
  const notifyConnectedOnSuccess = useRef(false)

  useEffect(() => {
    if (syncStatus !== 'SUCCESS' || !notifyConnectedOnSuccess.current) return
    notifyConnectedOnSuccess.current = false
    onConnected?.()
  }, [onConnected, syncStatus])

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const refreshToken = token.trim()
    if (!refreshToken) return
    importSession.mutate(refreshToken, {
      onSuccess: (result) => {
        setToken('')
        setError(null)
        toast.success(t('sync.natixis.browser.adopted'))
        if (result.syncStatus === 'SUCCESS') onConnected?.()
        else notifyConnectedOnSuccess.current = true
      },
      onError: (err: unknown) => setError(messageFor(err)),
    })
  }

  function messageFor(err: unknown): string {
    const code = getErrorCode(err)
    // Natixis refusing the token is the usual case: a stale or mistyped paste.
    if (code === 'SESSION_EXPIRED') return t('sync.natixis.browser.errors.refused')
    // Every connector failure is a 422 too: only one that carries no code is the validation of the paste.
    if (code === undefined && getErrorStatus(err) === 422) return t('sync.natixis.browser.errors.malformed')
    const suffix = code === undefined ? undefined : UPSTREAM_ERROR_SUFFIXES[code]
    if (suffix) return t(`sync.natixis.errors.${suffix}`)
    return extractErrorMessage(err, t('sync.natixis.browser.errors.failed'))
  }

  return (
    <Card size="sm">
      <CardContent className="space-y-4">
        <div className="space-y-1">
          <p className="flex items-center gap-2 text-sm font-bold">
            <Fingerprint className="size-4" />
            {t('sync.natixis.browser.title')}
          </p>
          <p className="text-sm text-muted-foreground">{t('sync.natixis.browser.intro')}</p>
        </div>

        <ol className="list-decimal space-y-2 pl-5 text-sm text-muted-foreground">
          <li>{t('sync.natixis.browser.step1', { site: SITE })}</li>
          <li>
            {t('sync.natixis.browser.step2')}{' '}
            <code className="rounded bg-muted px-1.5 py-0.5 text-foreground">{CONSOLE_COMMAND}</code>
          </li>
          <li>{t('sync.natixis.browser.step3')}</li>
        </ol>

        <p className="text-xs text-muted-foreground">{t('sync.natixis.browser.warning')}</p>

        <form onSubmit={handleSubmit} className="space-y-3">
          <div className="space-y-2">
            <Label htmlFor="natixis-browser-token">{t('sync.natixis.browser.token')}</Label>
            <Input
              id="natixis-browser-token"
              type="password"
              value={token}
              onChange={(e) => setToken(e.target.value)}
              autoComplete="off"
              spellCheck={false}
              required
            />
          </div>
          {error && (
            <p role="alert" className="text-sm text-destructive">
              {error}
            </p>
          )}
          <Button type="submit" disabled={importSession.isPending || !token.trim()} className="w-full">
            {importSession.isPending && <CircleNotch className="size-4 animate-spin" />}
            {importSession.isPending ? t('sync.natixis.browser.checking') : t('sync.natixis.browser.submit')}
          </Button>
        </form>
      </CardContent>
    </Card>
  )
}
