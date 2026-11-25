import { useCallback, useEffect, useRef, useState } from "react"
import { Warning, Lock, SignOut, ArrowsClockwise, ShieldCheck, User } from '@phosphor-icons/react'
import { useTranslation } from "react-i18next"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { ReportConnectorIssueButton } from "@/components/sync/ReportConnectorIssueButton"
import {
  useClearDegiroSession,
  useCompleteDegiroAuth,
  useDegiroSessionStatus,
  useInitiateDegiroAuth,
  useSyncDegiro,
} from "@/features/sync/hooks"
import { extractTypedErrorCode } from "@/lib/connector-report"
import { extractErrorMessage, getErrorDetail, getErrorStatus } from "@/lib/errors"
import { formatDate } from "@/lib/utils"

type AuthState = "IDLE" | "AWAITING_TOTP" | "ERROR"

interface DegiroPanelProps {
  onConnected?: () => void
}

export function DegiroPanel({ onConnected }: DegiroPanelProps = {}) {
  const { t } = useTranslation()
  const [authState, setAuthState] = useState<AuthState>("IDLE")
  const [username, setUsername] = useState("")
  const [password, setPassword] = useState("")
  const [totpCode, setTotpCode] = useState("")
  const [processId, setProcessId] = useState<string | null>(null)
  const [error, setErrorState] = useState<string | null>(null)
  const [errorCode, setErrorCode] = useState<string | null>(null)
  const setError = useCallback((message: string | null, code?: string | null) => {
    setErrorState(message)
    setErrorCode(message ? (code ?? null) : null)
  }, [])
  const notifyConnectedOnSuccess = useRef(false)

  const status = useDegiroSessionStatus()
  const initiate = useInitiateDegiroAuth()
  const complete = useCompleteDegiroAuth()
  const sync = useSyncDegiro()
  const logout = useClearDegiroSession()

  const formatError = (value: unknown) => {
    const httpStatus = getErrorStatus(value)
    if (httpStatus === 429) return t("sync.degiro.errors.tooManyAttempts")
    if (httpStatus === 401) return t("sync.degiro.errors.invalidCredentials")
    const detail = (getErrorDetail(value) ?? "").toLowerCase()
    if (detail.includes("expired") || detail.includes("reconnect"))
      return t("sync.degiro.errors.sessionExpired")
    if (detail.includes("totp") || detail.includes("code"))
      return t("sync.degiro.errors.invalidTotpCode")
    if (httpStatus && httpStatus >= 500) return t("sync.degiro.errors.serverError")
    return extractErrorMessage(value, t("sync.degiro.errors.serverError"))
  }

  const connected = status.data?.isActive === true
  const needsReauth = status.data?.status === "REAUTH_REQUIRED"
  const statusError = status.isError ? formatError(status.error) : null
  const visibleError = error ?? statusError
  const visibleErrorCode = error
    ? errorCode
    : statusError
      ? extractTypedErrorCode(status.error)
      : null

  // Depends on `connected`, not just `onConnected`: the ref is flipped inside a
  // mutation callback, so without a dep that actually changes when the session
  // goes live this effect never re-runs and the host modal never closes.
  useEffect(() => {
    if (!connected || !notifyConnectedOnSuccess.current) return
    notifyConnectedOnSuccess.current = false
    onConnected?.()
  }, [onConnected, connected])

  if (status.isLoading) {
    return <p className="text-sm text-muted-foreground">{t("common.loading")}</p>
  }

  return (
    <div className="space-y-6">
      <Card size="sm">
        <CardContent>
          <div className="flex flex-wrap items-center gap-3">
            {connected ? (
              <Badge className="bg-green-500/10 text-green-600 dark:text-green-400">
                {t("sync.degiro.sessionActive")}
              </Badge>
            ) : needsReauth ? (
              <Badge variant="secondary">
                {t("sync.degiro.reauthRequired")}
              </Badge>
            ) : (
              <Badge variant="outline">{t("sync.degiro.noSession")}</Badge>
            )}
            {status.data?.lastSyncedAt && (
              <span className="text-sm text-muted-foreground">
                {t("sync.degiro.lastSyncedAt")} {formatDate(status.data.lastSyncedAt)}
              </span>
            )}
          </div>
        </CardContent>
      </Card>

      {visibleError && (
        <Card size="sm" className="border-destructive/30">
          <CardContent className="flex flex-wrap items-center gap-3">
            <Warning className="size-5 shrink-0 text-destructive" />
            <p className="min-w-0 flex-1 text-sm text-destructive">{visibleError}</p>
            <ReportConnectorIssueButton connectorId="degiro" errorCode={visibleErrorCode} />
            <Button
              variant="outline"
              size="sm"
              onClick={() => {
                setError(null)
                setAuthState("IDLE")
                setProcessId(null)
                setTotpCode("")
                if (statusError) void status.refetch()
              }}
            >
              {t("common.retry")}
            </Button>
          </CardContent>
        </Card>
      )}

      {connected && !needsReauth && (
        <div className="flex flex-wrap gap-3">
          <Button
            onClick={() => {
              setError(null)
              sync.mutate(undefined, { onError: value => setError(formatError(value), extractTypedErrorCode(value)) })
            }}
            disabled={sync.isPending}
          >
            <ArrowsClockwise className={sync.isPending ? "animate-spin" : undefined} />
            {t("sync.degiro.sync")}
          </Button>
          <Button
            variant="destructive"
            onClick={() => {
              setError(null)
              logout.mutate(undefined, {
                onSuccess: () => {
                  setAuthState("IDLE")
                  setProcessId(null)
                  setTotpCode("")
                },
                onError: value => setError(formatError(value), extractTypedErrorCode(value)),
              })
            }}
            disabled={logout.isPending}
          >
            <SignOut />
            {t("sync.degiro.clearSession")}
          </Button>
        </div>
      )}

      {(!connected || needsReauth) && authState !== "AWAITING_TOTP" && (
        <form
          className="space-y-4"
          onSubmit={event => {
            event.preventDefault()
            setError(null)
            initiate.mutate(
              { username, password },
              {
                onSuccess: result => {
                  setPassword("")
                  if (result.totpRequired) {
                    if (!result.processId) {
                      // Shouldn't happen — the adapter rejects a blank processId before
                      // reporting totpRequired. Guarded anyway: without it a malformed
                      // response strands the user on a TOTP prompt that can never submit.
                      setError(t("sync.degiro.errors.serverError"), "INTERNAL_ERROR")
                      setAuthState("ERROR")
                      return
                    }
                    setProcessId(result.processId)
                    setAuthState("AWAITING_TOTP")
                    return
                  }
                  notifyConnectedOnSuccess.current = true
                  setUsername("")
                  setAuthState("IDLE")
                  void status.refetch()
                },
                onError: value => {
                  setPassword("")
                  setError(formatError(value), extractTypedErrorCode(value))
                  setAuthState("ERROR")
                },
              },
            )
          }}
        >
          <Card size="sm">
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="degiro-panel-username">
                  <User className="mr-1 inline-block size-4" />
                  {t("sync.degiro.username")}
                </Label>
                <Input
                  id="degiro-panel-username"
                  autoComplete="username"
                  value={username}
                  onChange={event => setUsername(event.target.value)}
                  required
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="degiro-panel-password">
                  <Lock className="mr-1 inline-block size-4" />
                  {t("sync.degiro.password")}
                </Label>
                <Input
                  id="degiro-panel-password"
                  type="password"
                  autoComplete="current-password"
                  value={password}
                  onChange={event => setPassword(event.target.value)}
                  required
                />
              </div>
              <Button type="submit" disabled={initiate.isPending}>
                {initiate.isPending && <ArrowsClockwise className="animate-spin" />}
                {t("sync.degiro.connect")}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}

      {authState === "AWAITING_TOTP" && (
        <form
          onSubmit={event => {
            event.preventDefault()
            if (!processId) return
            setError(null)
            complete.mutate(
              { processId, code: totpCode },
              {
                onSuccess: () => {
                  notifyConnectedOnSuccess.current = true
                  setAuthState("IDLE")
                  setUsername("")
                  setPassword("")
                  setProcessId(null)
                  setTotpCode("")
                },
                onError: value => {
                  setError(formatError(value), extractTypedErrorCode(value))
                  setAuthState("ERROR")
                  setProcessId(null)
                  setTotpCode("")
                },
              },
            )
          }}
        >
          <Card size="sm">
            <CardContent className="space-y-4">
              <p className="text-sm text-muted-foreground">{t("sync.degiro.totpPrompt")}</p>
              <div className="space-y-2">
                <Label htmlFor="degiro-panel-totp">
                  <ShieldCheck className="mr-1 inline-block size-4" />
                  {t("sync.degiro.totpCode")}
                </Label>
                <Input
                  id="degiro-panel-totp"
                  type="text"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  value={totpCode}
                  onChange={event => setTotpCode(event.target.value)}
                  required
                />
              </div>
              <Button type="submit" disabled={complete.isPending}>
                {complete.isPending && <ArrowsClockwise className="animate-spin" />}
                {t("sync.degiro.connect")}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}
    </div>
  )
}
