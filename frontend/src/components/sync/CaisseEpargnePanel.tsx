import { useEffect, useRef, useState } from "react"
import {
  AlertTriangle,
  Landmark,
  Lock,
  LogOut,
  RefreshCw,
  ShieldAlert,
  Smartphone,
} from "lucide-react"
import { useTranslation } from "react-i18next"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { caisseEpargneApi } from "@/features/sync/api"
import {
  useCaisseEpargneSessionStatus,
  useClearCaisseEpargneSession,
  useCompleteCaisseEpargneAuth,
  useSyncCaisseEpargne,
} from "@/features/sync/hooks"
import { getErrorCode, getErrorDetail, getErrorStatus } from "@/lib/errors"

/**
 * `AWAITING_APP` has no form: the bank pushes a Sécur'Pass notification and the
 * completion request stays open until the user approves it on their phone.
 *
 * One attempt only. A wrong password spends a bank attempt and can lock the
 * account, so nothing in this panel replays `initiate` after a failure: after an
 * error the form simply comes back empty and the user decides.
 */
type AuthState = "IDLE" | "SUBMITTING" | "AWAITING_APP" | "ERROR"

/** The sidecar waits this long for the human to approve on the phone (COMPLETE_WAIT_SECONDS). */
const HUMAN_WAIT_SECONDS = 150

const KNOWN_CODES = new Set([
  "INVALID_CREDENTIALS",
  "KEYPAD_CHANGED",
  "APP_VALIDATION_TIMEOUT",
  "AUTH_ATTEMPT_EXPIRED",
  "SESSION_EXPIRED",
  "UPSTREAM_UNAVAILABLE",
  "UPSTREAM_FORMAT_CHANGED",
  "INVALID_SESSION_STATE",
  "INTERNAL_ERROR",
])

/** The code is in `code` when the backend sends one, else in the problem `detail`. */
function errorCodeOf(error: unknown): string | undefined {
  const code = getErrorCode(error)
  if (code && KNOWN_CODES.has(code)) return code
  const detail = getErrorDetail(error)
  return detail && KNOWN_CODES.has(detail) ? detail : undefined
}

/** Whole seconds of a `Retry-After` header, from a plain object or AxiosHeaders. */
function retryAfterSeconds(error: unknown): number | null {
  const headers = (error as { response?: { headers?: unknown } })?.response?.headers as
    | { get?: (name: string) => unknown; [key: string]: unknown }
    | undefined
  if (!headers) return null
  const raw =
    typeof headers.get === "function"
      ? headers.get("retry-after")
      : (headers["retry-after"] ?? headers["Retry-After"])
  const seconds = Number(raw)
  return Number.isFinite(seconds) && seconds > 0 ? seconds : null
}

function formatCountdown(totalSeconds: number): string {
  const safe = Math.max(0, totalSeconds)
  const minutes = Math.floor(safe / 60)
  const seconds = safe % 60
  return `${minutes}:${String(seconds).padStart(2, "0")}`
}

interface CaisseEpargnePanelProps {
  onConnected?: () => void
}

export function CaisseEpargnePanel({ onConnected }: CaisseEpargnePanelProps = {}) {
  const { t } = useTranslation()
  const [authState, setAuthState] = useState<AuthState>("IDLE")
  const [customerId, setCustomerId] = useState("")
  const [password, setPassword] = useState("")
  const [error, setError] = useState<string | null>(null)
  const [secondsLeft, setSecondsLeft] = useState(0)
  const [confirmingDisconnect, setConfirmingDisconnect] = useState(false)
  // Synchronous guard: state updates are async, so a fast double click could
  // otherwise reach the bank twice.
  const submitting = useRef(false)

  const status = useCaisseEpargneSessionStatus()
  const complete = useCompleteCaisseEpargneAuth()
  const sync = useSyncCaisseEpargne()
  const logout = useClearCaisseEpargneSession()
  const { mutate: completeMutate } = complete

  const messageForCode = (value: string | null | undefined) => {
    switch (value) {
      case "INVALID_CREDENTIALS":
        return t("sync.caisseEpargne.errors.invalidCredentials")
      case "KEYPAD_CHANGED":
        return t("sync.caisseEpargne.errors.keypadChanged")
      case "APP_VALIDATION_TIMEOUT":
        return t("sync.caisseEpargne.errors.appValidationTimeout")
      case "AUTH_ATTEMPT_EXPIRED":
        return t("sync.caisseEpargne.errors.authAttemptExpired")
      case "SESSION_EXPIRED":
        return t("sync.caisseEpargne.errors.sessionExpired")
      case "UPSTREAM_FORMAT_CHANGED":
        return t("sync.caisseEpargne.errors.formatChanged")
      case "INVALID_SESSION_STATE":
        return t("sync.caisseEpargne.errors.invalidSessionState")
      case "UPSTREAM_UNAVAILABLE":
      case "INTERNAL_ERROR":
        return t("sync.caisseEpargne.errors.serverError")
      default:
        return null
    }
  }

  // Never shows a raw backend message: the bank's own detail can carry
  // internals, and an unknown failure gets the generic text.
  const formatError = (value: unknown) => {
    if (getErrorStatus(value) === 429) {
      const seconds = retryAfterSeconds(value)
      return seconds
        ? t("sync.caisseEpargne.errors.rateLimitedRetryAfter", {
            minutes: Math.ceil(seconds / 60),
          })
        : t("sync.caisseEpargne.errors.rateLimited")
    }
    return (
      messageForCode(errorCodeOf(value)) ?? t("sync.caisseEpargne.errors.serverError")
    )
  }

  const connected = status.data?.isActive === true
  const syncStatus = status.data?.syncStatus ?? "IDLE"
  const isSyncing = syncStatus === "QUEUED" || syncStatus === "RUNNING"
  const requestingSync = sync.isPending || isSyncing
  const backgroundError =
    syncStatus === "FAILED"
      ? (messageForCode(status.data?.lastSyncError) ??
        t("sync.caisseEpargne.errors.serverError"))
      : null
  const statusError = status.isError ? formatError(status.error) : null
  const visibleError = error ?? backgroundError ?? statusError

  useEffect(() => {
    if (authState !== "AWAITING_APP") return
    const timer = setInterval(() => setSecondsLeft((value) => Math.max(0, value - 1)), 1_000)
    return () => clearInterval(timer)
  }, [authState])

  const awaitSecurPass = (processId: string) => {
    // One call, no polling: the backend holds it open while the human approves.
    completeMutate(
      { processId },
      {
        onSuccess: () => {
          setAuthState("IDLE")
          setCustomerId("")
          setPassword("")
          void status.refetch()
          onConnected?.()
        },
        onError: (value) => {
          setPassword("")
          setError(formatError(value))
          setAuthState("ERROR")
        },
      }
    )
  }

  const submit = async () => {
    if (submitting.current) return
    submitting.current = true
    setError(null)
    setAuthState("SUBMITTING")
    const request = caisseEpargneApi.initiateAuth(customerId, password)
    // The request is out: the password has no further use and must not stay in
    // component state, success or not.
    setPassword("")
    try {
      const result = await request
      if (!result.processId) {
        setError(t("sync.caisseEpargne.errors.formatChanged"))
        setAuthState("ERROR")
        return
      }
      // expiresInSeconds is the process lifetime (300 s). The user only has the
      // sidecar's 150 s human wait to approve, so that is what the countdown shows.
      setSecondsLeft(
        Math.min(HUMAN_WAIT_SECONDS, Math.max(0, Math.floor(result.expiresInSeconds ?? 0))),
      )
      setAuthState("AWAITING_APP")
      awaitSecurPass(result.processId)
    } catch (value) {
      setError(formatError(value))
      setAuthState("ERROR")
    } finally {
      submitting.current = false
    }
  }

  if (status.isLoading)
    return <p className="text-sm text-muted-foreground">{t("common.loading")}</p>

  const showForm = !connected && (authState === "IDLE" || authState === "SUBMITTING" || authState === "ERROR")
  const pending = authState === "SUBMITTING"

  return (
    <div className="space-y-6">
      <Card size="sm">
        <CardContent className="py-4">
          <Badge
            className={
              connected ? "bg-green-500/10 text-green-600 dark:text-green-400" : undefined
            }
            variant={connected ? "default" : "outline"}
          >
            {connected
              ? t("sync.caisseEpargne.sessionActive")
              : t("sync.caisseEpargne.noSession")}
          </Badge>
          <p className="mt-2 text-xs text-muted-foreground">{t("sync.caisseEpargne.scope")}</p>
          {isSyncing && (
            <p className="mt-2 text-sm text-muted-foreground">
              {syncStatus === "QUEUED"
                ? t("sync.caisseEpargne.queued")
                : t("sync.caisseEpargne.syncing")}
            </p>
          )}
          {syncStatus === "SUCCESS" && status.data?.lastSyncCompletedAt && (
            <p className="mt-2 text-sm text-emerald-600 dark:text-emerald-400">
              {t("sync.caisseEpargne.syncSuccess")}
            </p>
          )}
        </CardContent>
      </Card>

      {visibleError && (
        <Card size="sm" className="border-destructive/30">
          <CardContent className="flex items-center gap-3 py-4">
            <AlertTriangle className="size-5 shrink-0 text-destructive" />
            <p className="flex-1 text-sm text-destructive">{visibleError}</p>
            {statusError && !error && (
              <Button variant="outline" size="sm" onClick={() => void status.refetch()}>
                {t("common.retry")}
              </Button>
            )}
          </CardContent>
        </Card>
      )}

      {connected && (
        <>
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
              <RefreshCw className={requestingSync ? "animate-spin" : undefined} />
              {requestingSync ? t("sync.caisseEpargne.syncing") : t("sync.caisseEpargne.sync")}
            </Button>
            {!confirmingDisconnect && (
              <Button
                variant="destructive"
                onClick={() => setConfirmingDisconnect(true)}
                disabled={logout.isPending}
              >
                <LogOut />
                {t("sync.caisseEpargne.clearSession")}
              </Button>
            )}
          </div>

          {confirmingDisconnect && (
            <Card size="sm" className="border-destructive/30">
              <CardContent className="space-y-3 py-4">
                <p className="text-sm">{t("sync.caisseEpargne.disconnectConfirm")}</p>
                <div className="flex flex-wrap gap-3">
                  <Button
                    variant="destructive"
                    disabled={logout.isPending}
                    onClick={() => {
                      setError(null)
                      logout.mutate(undefined, {
                        onSuccess: () => {
                          setConfirmingDisconnect(false)
                          setAuthState("IDLE")
                        },
                        onError: (value) => {
                          setConfirmingDisconnect(false)
                          setError(formatError(value))
                        },
                      })
                    }}
                  >
                    <LogOut />
                    {t("sync.caisseEpargne.disconnectConfirmAction")}
                  </Button>
                  <Button variant="outline" onClick={() => setConfirmingDisconnect(false)}>
                    {t("common.cancel")}
                  </Button>
                </div>
              </CardContent>
            </Card>
          )}

          {(status.data?.unsupported.length ?? 0) > 0 && (
            <Card size="sm">
              <CardContent className="space-y-2 py-4">
                <p className="text-sm font-medium">{t("sync.caisseEpargne.unsupportedTitle")}</p>
                <p className="text-xs text-muted-foreground">
                  {t("sync.caisseEpargne.unsupportedHint")}
                </p>
                {/* Family code only: the contract id is never shown. */}
                <ul className="flex flex-wrap gap-2">
                  {status.data?.unsupported.map((contract, index) => (
                    <li key={`${contract.familyCode}-${index}`}>
                      <Badge variant="outline">
                        <span>{contract.familyCode}</span>
                      </Badge>
                    </li>
                  ))}
                </ul>
              </CardContent>
            </Card>
          )}
        </>
      )}

      {showForm && (
        <form
          className="space-y-4"
          onSubmit={(event) => {
            event.preventDefault()
            void submit()
          }}
        >
          <Card size="sm" className="border-amber-500/30">
            <CardContent className="flex items-start gap-3 py-4">
              <ShieldAlert className="mt-0.5 size-5 shrink-0 text-amber-600 dark:text-amber-400" />
              <ul className="space-y-1 text-xs text-muted-foreground">
                <li>{t("sync.caisseEpargne.notice.neverStored")}</li>
                <li>{t("sync.caisseEpargne.notice.noRetry")}</li>
                <li>{t("sync.caisseEpargne.notice.lockRisk")}</li>
              </ul>
            </CardContent>
          </Card>
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              <div className="space-y-2">
                <Label htmlFor="caisse-epargne-customer-id">
                  <Landmark className="mr-1 inline-block size-4" />
                  {t("sync.caisseEpargne.customerId")}
                </Label>
                <Input
                  id="caisse-epargne-customer-id"
                  inputMode="numeric"
                  autoComplete="off"
                  value={customerId}
                  onChange={(event) => setCustomerId(event.target.value.replace(/\D/g, ""))}
                  required
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="caisse-epargne-password">
                  <Lock className="mr-1 inline-block size-4" />
                  {t("sync.caisseEpargne.password")}
                </Label>
                {/* The bank's virtual keypad only has digits; a stray character
                    would spend a login attempt on a password the user never typed. */}
                <Input
                  id="caisse-epargne-password"
                  type="password"
                  inputMode="numeric"
                  autoComplete="off"
                  value={password}
                  onChange={(event) => setPassword(event.target.value.replace(/\D/g, ""))}
                  required
                />
              </div>
              <Button type="submit" disabled={pending}>
                {pending && <RefreshCw className="animate-spin" />}
                {pending ? t("sync.caisseEpargne.connecting") : t("sync.caisseEpargne.connect")}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}

      {!connected && authState === "AWAITING_APP" && (
        <Card size="sm">
          <CardContent className="flex items-center gap-3 py-4">
            <Smartphone className="size-5 shrink-0 text-muted-foreground" />
            <div className="flex-1 space-y-1">
              <p className="text-sm">{t("sync.caisseEpargne.securPassPrompt")}</p>
              <p className="text-xs text-muted-foreground">
                {t("sync.caisseEpargne.securPassHint")}
              </p>
              <p className="text-xs text-muted-foreground">
                {t("sync.caisseEpargne.countdownLabel")}{" "}
                <span data-testid="caisse-epargne-countdown" className="font-mono">
                  {formatCountdown(secondsLeft)}
                </span>
              </p>
            </div>
            <RefreshCw className="size-4 shrink-0 animate-spin text-muted-foreground" />
          </CardContent>
        </Card>
      )}
    </div>
  )
}
