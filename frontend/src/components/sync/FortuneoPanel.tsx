import { useEffect, useRef, useState } from "react"
import {
  AlertTriangle,
  PiggyBank,
  Lock,
  LogOut,
  RefreshCw,
  ShieldCheck,
} from "lucide-react"
import { useTranslation } from "react-i18next"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  useFortuneoStatus,
  useClearFortuneoSession,
  useCompleteFortuneoAuth,
  useInitiateFortuneoAuth,
  useSyncFortuneo,
} from "@/features/sync/hooks"
import { formatFortuneoError, fortuneoErrorMessage } from "@/lib/errors"
import type { FortuneoErrorCode } from "@/types/api"

type AuthState = "IDLE" | "AWAITING_OTP" | "ERROR"

interface FortuneoPanelProps {
  onConnected?: () => void
}
export function FortuneoPanel({ onConnected }: FortuneoPanelProps = {}) {
  const { t } = useTranslation()
  const [authState, setAuthState] = useState<AuthState>("IDLE")
  const [login, setLogin] = useState("")
  const [password, setPassword] = useState("")
  const [code, setCode] = useState("")
  const [processId, setProcessId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const notifyConnectedOnSuccess = useRef(false)

  const status = useFortuneoStatus()
  const initiate = useInitiateFortuneoAuth()
  const complete = useCompleteFortuneoAuth()
  const sync = useSyncFortuneo()
  const logout = useClearFortuneoSession()

  const messageForCode = (code: FortuneoErrorCode | string | null | undefined) =>
    fortuneoErrorMessage(t, code)

  const formatError = (value: unknown) => formatFortuneoError(value, t)

  const connected = status.data?.isActive === true
  const syncStatus = status.data?.syncStatus ?? "IDLE"
  const isSyncing = syncStatus === "QUEUED" || syncStatus === "RUNNING"
  const requestingSync = sync.isPending || isSyncing
  const backgroundError =
    syncStatus === "FAILED"
      ? (messageForCode(status.data?.lastSyncError) ??
        t("sync.fortuneo.errors.serverError"))
      : null
  const statusError = status.isError ? formatError(status.error) : null
  const visibleError = error ?? backgroundError ?? statusError

  useEffect(() => {
    if (syncStatus !== "SUCCESS" || !notifyConnectedOnSuccess.current) return
    notifyConnectedOnSuccess.current = false
    onConnected?.()
  }, [onConnected, syncStatus])

  if (status.isLoading)
    return (
      <p className="text-sm text-muted-foreground">{t("common.loading")}</p>
    )

  return (
    <div className="space-y-6">
      <Card size="sm">
        <CardContent className="py-4">
          <Badge
            className={
              connected
                ? "bg-green-500/10 text-green-600 dark:text-green-400"
                : undefined
            }
            variant={connected ? "default" : "outline"}
          >
            {connected
              ? t("sync.fortuneo.sessionActive")
              : t("sync.fortuneo.noSession")}
          </Badge>
          <p className="mt-2 text-xs text-muted-foreground">
            {t("sync.fortuneo.scope")}
          </p>
          {isSyncing && (
            <p className="mt-2 text-sm text-muted-foreground">
              {syncStatus === "QUEUED"
                ? t("sync.fortuneo.queued")
                : t("sync.fortuneo.syncing")}
            </p>
          )}
          {syncStatus === "SUCCESS" && status.data?.lastSyncCompletedAt && (
            <p className="mt-2 text-sm text-emerald-600 dark:text-emerald-400">
              {t("sync.fortuneo.syncSuccess")}
            </p>
          )}
        </CardContent>
      </Card>

      {visibleError && (
        <Card size="sm" className="border-destructive/30">
          <CardContent className="flex items-center gap-3 py-4">
            <AlertTriangle className="size-5 shrink-0 text-destructive" />
            <p className="flex-1 text-sm text-destructive">{visibleError}</p>
            {(error || statusError) && (
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  setError(null)
                  setAuthState("IDLE")
                  setProcessId(null)
                  setCode("")
                  if (statusError) void status.refetch()
                }}
              >
                {t("common.retry")}
              </Button>
            )}
          </CardContent>
        </Card>
      )}

      {connected && (
        <div className="flex flex-wrap gap-3">
          <Button
            onClick={() => {
              setError(null)
              sync.mutate(undefined, {
                onError: (value) => setError(formatError(value)),
              })
            }}
            disabled={requestingSync}
          >
            <RefreshCw
              className={requestingSync ? "animate-spin" : undefined}
            />
            {requestingSync
              ? t("sync.fortuneo.syncing")
              : t("sync.fortuneo.sync")}
          </Button>
          <Button
            variant="destructive"
            onClick={() => {
              setError(null)
              logout.mutate(undefined, {
                onSuccess: () => {
                  notifyConnectedOnSuccess.current = false
                  setAuthState("IDLE")
                  setProcessId(null)
                  setCode("")
                },
                onError: (value) => setError(formatError(value)),
              })
            }}
            disabled={logout.isPending}
          >
            <LogOut />
            {t("sync.fortuneo.clearSession")}
          </Button>
        </div>
      )}

      {!connected && authState === "IDLE" && (
        <form
          className="space-y-4"
          onSubmit={(event) => {
            event.preventDefault()
            setError(null)
            initiate.mutate(
              { login, password },
              {
                onSuccess: (result) => {
                  setPassword("")
                  if (result.mfaRequired) {
                    if (!result.processId) {
                      setError(t("sync.fortuneo.errors.formatChanged"))
                      setAuthState("ERROR")
                      return
                    }
                    setProcessId(result.processId)
                    setAuthState("AWAITING_OTP")
                    return
                  }
                  notifyConnectedOnSuccess.current = true
                  setLogin("")
                  setAuthState("IDLE")
                  void status.refetch()
                },
                onError: (value) => {
                  setPassword("")
                  setError(formatError(value))
                  setAuthState("ERROR")
                },
              }
            )
          }}
        >
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              <div className="space-y-2">
                <Label htmlFor="fortuneo-login">
                  <PiggyBank className="mr-1 inline-block size-4" />
                  {t("sync.fortuneo.login")}
                </Label>
                <Input
                  id="fortuneo-login"
                  autoComplete="username"
                  value={login}
                  onChange={(event) => setLogin(event.target.value)}
                  required
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="fortuneo-password">
                  <Lock className="mr-1 inline-block size-4" />
                  {t("sync.fortuneo.password")}
                </Label>
                <Input
                  id="fortuneo-password"
                  type="password"
                  autoComplete="current-password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  required
                />
              </div>
              <Button type="submit" disabled={initiate.isPending}>
                {initiate.isPending && <RefreshCw className="animate-spin" />}
                {initiate.isPending
                  ? t("sync.fortuneo.connecting")
                  : t("sync.fortuneo.connect")}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}

      {!connected && authState === "AWAITING_OTP" && (
        <form
          onSubmit={(event) => {
            event.preventDefault()
            if (!processId) return
            setError(null)
            complete.mutate(
              { processId, code },
              {
                onSuccess: (result) => {
                  notifyConnectedOnSuccess.current = true
                  setAuthState("IDLE")
                  setLogin("")
                  setPassword("")
                  setProcessId(null)
                  setCode("")
                  if (result.syncStatus === "SUCCESS") {
                    notifyConnectedOnSuccess.current = false
                    onConnected?.()
                  } else {
                    void status.refetch()
                  }
                },
                onError: (value) => {
                  setError(formatError(value))
                  setAuthState("ERROR")
                  setProcessId(null)
                  setCode("")
                },
              }
            )
          }}
        >
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              <p className="text-sm text-muted-foreground">
                {t("sync.fortuneo.otpPrompt")}
              </p>
              <div className="space-y-2">
                <Label htmlFor="fortuneo-otp">
                  <ShieldCheck className="mr-1 inline-block size-4" />
                  {t("sync.fortuneo.otpCode")}
                </Label>
                <Input
                  id="fortuneo-otp"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  pattern="[0-9]{6}"
                  maxLength={6}
                  value={code}
                  onChange={(event) =>
                    setCode(event.target.value.replace(/\D/g, ""))
                  }
                  required
                />
              </div>
              <Button
                type="submit"
                disabled={complete.isPending || code.length !== 6}
              >
                {complete.isPending && <RefreshCw className="animate-spin" />}
                {complete.isPending
                  ? t("sync.fortuneo.validating")
                  : t("sync.fortuneo.validate")}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}
    </div>
  )
}
