import { useCallback, useEffect, useRef, useState } from "react"
import {
  AlertTriangle,
  Landmark,
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
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { lbpApi } from "@/features/sync/api"
import {
  useProviderClear,
  useProviderComplete,
  useProviderStatus,
  useProviderSync,
} from "@/features/sync/hooks"
import { getErrorCode, getErrorDetail, getErrorStatus } from "@/lib/errors"

/**
 * `KEYPAD` is a pop-up showing the bank's virtual keypad: the user reads the
 * pad and clicks their own digits. Picsou never stores the password; it only
 * holds the shuffled labels and the clicked positions in transit (together
 * password-equivalent), and keeps the positions in component state until Validate.
 * `AWAITING_APP` has no form: the bank pushes a Sécur'Pass notification and the
 * completion request stays open until the user approves it on their phone.
 *
 * One attempt only. A wrong password spends a bank attempt and can lock the
 * account, so nothing in this panel replays `initiate` or `keypad` after a
 * failure: after an error the form simply comes back and the user decides.
 */
type AuthState =
  | "IDLE"
  | "SUBMITTING"
  | "KEYPAD"
  | "SENDING_KEYPAD"
  | "AWAITING_APP"
  | "ERROR"

/** The bank's password is exactly 6 digits; the sidecar requires exactly 6, this UI sends 6. */
const MIN_POSITIONS = 6
const MAX_POSITIONS = 6
const MIN_IDENTIFIER = 6
const MAX_IDENTIFIER = 12
const DEFAULT_COLUMNS = 5

interface KeypadPad {
  processId: string
  keys: string[]
  columns: number
}

/** The sidecar waits this long for the human to approve on the phone (COMPLETE_WAIT_SECONDS). */
const HUMAN_WAIT_SECONDS = 150

/**
 * How long the panel keeps polling the session once the completion request is lost:
 * the backend's own window (170 s, the human wait plus collecting the session) and a margin.
 */
const LOST_COMPLETION_WAIT_MS = 180_000
const LOST_COMPLETION_POLL_MS = 3_000
/**
 * Each poll tick sends its own status read, bounded by this, rather than refetching the
 * status query: a refetch every 3 s would cancel a read slower than that, so a slow network
 * would never see the session; and waiting on one read could hang on a request that never
 * returns. Reads may overlap; the first live answer wins.
 */
const LOST_COMPLETION_READ_TIMEOUT_MS = 10_000

/**
 * The completion request got no answer from the backend: iOS drops it while Safari sits
 * in the background, which is exactly when the user approves in the bank's app
 * on the same phone. The backend carries on and stores the session regardless.
 */
function isLostRequest(value: unknown) {
  const httpStatus = getErrorStatus(value)
  return httpStatus === undefined || httpStatus === 502 || httpStatus === 504
}

const KNOWN_CODES = new Set([
  "INVALID_CREDENTIALS",
  "KEYPAD_CHANGED",
  "KEYPAD_EXPIRED",
  "INVALID_POSITIONS",
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

interface LbpPanelProps {
  onConnected?: () => void
}

export function LbpPanel({ onConnected }: LbpPanelProps = {}) {
  const { t } = useTranslation()
  const [authState, setAuthState] = useState<AuthState>("IDLE")
  // `null` means untouched: fall back to the remembered identifier, if any.
  const [customerId, setCustomerId] = useState<string | null>(null)
  const [rememberIdentifier, setRememberIdentifier] = useState<boolean | null>(null)
  const [pad, setPad] = useState<KeypadPad | null>(null)
  // Key positions only (indexes in the pad's DOM order), never digits. Component
  // state only: no query, no mutation cache, no storage, no log.
  const [positions, setPositions] = useState<number[]>([])
  const [keypadSecondsLeft, setKeypadSecondsLeft] = useState(0)
  const [keypadDeadlineAt, setKeypadDeadlineAt] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [secondsLeft, setSecondsLeft] = useState(0)
  const [confirmingDisconnect, setConfirmingDisconnect] = useState(false)
  // When the completion request was lost: the waiting card stays and the session is polled.
  const [lostCompletionAt, setLostCompletionAt] = useState<number | null>(null)
  // Synchronous guard: state updates are async, so a fast double click could
  // otherwise reach the bank twice.
  const submitting = useRef(false)
  // Set once the login completed and its first sync was queued: the caller is told
  // "connected" only when that sync succeeded, so the accounts exist by then.
  const notifyConnectedOnSuccess = useRef(false)
  const firstSyncStarted = useRef(false)
  const authAttempt = useRef(0)

  const status = useProviderStatus("lbp")
  const complete = useProviderComplete("lbp")
  const sync = useProviderSync("lbp")
  const logout = useProviderClear("lbp")
  const { mutate: completeMutate } = complete
  const { mutate: syncMutate } = sync
  const { refetch: refetchStatus } = status

  // The identifier is the only login field Picsou may keep (encrypted at rest).
  // The password and the keypad positions are never stored. `null` means the user
  // has not edited the field yet, so the remembered value is shown and reused.
  const savedIdentifier = status.data?.savedIdentifier ?? null
  const customerIdValue = customerId ?? savedIdentifier ?? ""
  const rememberValue = rememberIdentifier ?? savedIdentifier !== null

  const messageForCode = useCallback((value: string | null | undefined) => {
    switch (value) {
      case "INVALID_CREDENTIALS":
        return t("sync.lbp.errors.invalidCredentials")
      case "KEYPAD_CHANGED":
        return t("sync.lbp.errors.keypadChanged")
      case "KEYPAD_EXPIRED":
        return t("sync.lbp.errors.keypadExpired")
      case "INVALID_POSITIONS":
        return t("sync.lbp.errors.invalidPositions")
      case "APP_VALIDATION_TIMEOUT":
        return t("sync.lbp.errors.appValidationTimeout")
      case "AUTH_ATTEMPT_EXPIRED":
        return t("sync.lbp.errors.authAttemptExpired")
      case "SESSION_EXPIRED":
        return t("sync.lbp.errors.sessionExpired")
      case "UPSTREAM_FORMAT_CHANGED":
        return t("sync.lbp.errors.formatChanged")
      case "INVALID_SESSION_STATE":
        return t("sync.lbp.errors.invalidSessionState")
      case "UPSTREAM_UNAVAILABLE":
      case "INTERNAL_ERROR":
        return t("sync.lbp.errors.serverError")
      default:
        return null
    }
  }, [t])

  // Never shows a raw backend message: the bank's own detail can carry
  // internals, and an unknown failure gets the generic text.
  const formatError = useCallback((value: unknown) => {
    if (getErrorStatus(value) === 429) {
      const seconds = retryAfterSeconds(value)
      return seconds
        ? t("sync.lbp.errors.rateLimitedRetryAfter", {
            minutes: Math.ceil(seconds / 60),
          })
        : t("sync.lbp.errors.rateLimited")
    }
    return (
      messageForCode(errorCodeOf(value)) ?? t("sync.lbp.errors.serverError")
    )
  }, [messageForCode, t])

  // A 429 on the sync itself means the bank throttles imports, not sign-ins:
  // it gets its own copy so the user does not retry the whole login.
  const formatSyncError = useCallback((value: unknown) => {
    if (getErrorStatus(value) === 429) {
      const seconds = retryAfterSeconds(value)
      return seconds
        ? t("sync.lbp.errors.tooManySyncsRetryAfter", {
            minutes: Math.ceil(seconds / 60),
          })
        : t("sync.lbp.errors.tooManySyncs")
    }
    return (
      messageForCode(errorCodeOf(value)) ?? t("sync.lbp.errors.serverError")
    )
  }, [messageForCode, t])

  const startFirstSync = useCallback(() => {
    if (firstSyncStarted.current) return
    firstSyncStarted.current = true
    setLostCompletionAt(null)
    setAuthState("IDLE")
    setCustomerId(null)
    setConfirmingDisconnect(false)
    void refetchStatus()
    // Armed before the call, not in its onSuccess: if the first attempt fails, the
    // manual retry must still tell the caller "connected" once the import succeeds.
    notifyConnectedOnSuccess.current = true
    syncMutate(undefined, {
      onError: (value) => setError(formatSyncError(value)),
    })
  }, [formatSyncError, refetchStatus, syncMutate])

  useEffect(() => {
    if (lostCompletionAt === null) return
    let cancelled = false
    const attempt = authAttempt.current
    const timer = setInterval(() => {
      if (Date.now() - lostCompletionAt >= LOST_COMPLETION_WAIT_MS) {
        clearInterval(timer)
        // Stop sending reads, but let a recent in-flight read confirm the login.
        // Keep its effect alive until success, a new attempt or unmount.
        setError(t("sync.lbp.errors.appValidationTimeout"))
        setAuthState("ERROR")
        return
      }
      const readStartedAt = Date.now()
      void lbpApi
        .getStatusWithin(LOST_COMPLETION_READ_TIMEOUT_MS)
        .then((data) => {
          if (cancelled || attempt !== authAttempt.current) return
          if (Date.now() - readStartedAt >= LOST_COMPLETION_READ_TIMEOUT_MS) return
          if (!data?.isActive) return
          cancelled = true
          clearInterval(timer)
          setError(null)
          startFirstSync()
        })
        .catch(() => {
          // Only status reads are retried. Authentication is never replayed.
        })
    }, LOST_COMPLETION_POLL_MS)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [lostCompletionAt, startFirstSync, t])

  const connected = status.data?.isActive === true
  const syncStatus = status.data?.syncStatus ?? "IDLE"
  const isSyncing = syncStatus === "QUEUED" || syncStatus === "RUNNING"
  const requestingSync = sync.isPending || isSyncing

  // If the panel was unmounted while the login completed in the background (the
  // modal closed during approval), the first sync still needs queueing on the next
  // mount: the stored session holds no accounts until then. Only decide from a
  // settled, fresh status: on remount the query serves cached data first, and a
  // lost sync response can leave a stale IDLE that would queue a second bank read
  // before the refetch lands. Only a session that never synced (IDLE) is recovered;
  // a FAILED first sync waits for the user's explicit retry.
  useEffect(() => {
    if (!status.isFetched || status.isFetching || status.isError) return
    if (!connected || syncStatus !== "IDLE" || status.data?.lastSyncCompletedAt) return
    startFirstSync()
  }, [
    connected,
    syncStatus,
    status.data?.lastSyncCompletedAt,
    status.isError,
    status.isFetched,
    status.isFetching,
    startFirstSync,
  ])

  const backgroundError =
    syncStatus === "FAILED"
      ? (messageForCode(status.data?.lastSyncError) ??
        t("sync.lbp.errors.serverError"))
      : null
  const statusError = status.isError ? formatError(status.error) : null
  const visibleError = error ?? backgroundError ?? statusError

  useEffect(() => {
    if (syncStatus !== "SUCCESS" || !notifyConnectedOnSuccess.current) return
    notifyConnectedOnSuccess.current = false
    onConnected?.()
  }, [onConnected, syncStatus])

  useEffect(() => {
    if (authState !== "AWAITING_APP") return
    const timer = setInterval(() => setSecondsLeft((value) => Math.max(0, value - 1)), 1_000)
    return () => clearInterval(timer)
  }, [authState])

  // The pad is stable for a short time only: once it is out of date nothing may
  // be sent, so the pop-up closes and everything the user clicked is dropped.
  // The countdown is recomputed from an absolute deadline, so a suspended or
  // backgrounded tab shows the true remaining time when it resumes.
  useEffect(() => {
    if (authState !== "KEYPAD") return
    const tick = () =>
      setKeypadSecondsLeft(
        keypadDeadlineAt === null ? 0 : Math.max(0, Math.ceil((keypadDeadlineAt - Date.now()) / 1_000)),
      )
    tick()
    const timer = setInterval(tick, 250)
    return () => clearInterval(timer)
  }, [authState, keypadDeadlineAt])

  const expired = authState === "KEYPAD" && keypadSecondsLeft <= 0
  if (expired) {
    // Derived during render (React's "adjust state on change" pattern), not in an effect.
    setPad(null)
    setPositions([])
    setKeypadSecondsLeft(0)
    setKeypadDeadlineAt(null)
    setError(t("sync.lbp.errors.keypadExpired"))
    setAuthState("ERROR")
  }

  const resetKeypad = () => {
    setPad(null)
    setPositions([])
    setKeypadSecondsLeft(0)
    setKeypadDeadlineAt(null)
  }

  const cancelKeypad = () => {
    if (authState !== "KEYPAD") return
    resetKeypad()
    setError(null)
    setAuthState("IDLE")
  }

  const awaitSecurPass = (processId: string) => {
    // One completion call: only session status may be polled if its answer is lost.
    completeMutate(
      { processId },
      {
        onSuccess: startFirstSync,
        onError: (value) => {
          if (isLostRequest(value)) {
            // Keep the waiting card: the session status tells whether it went through.
            // Recovery gets a full window even if the approval countdown already elapsed.
            setSecondsLeft(LOST_COMPLETION_WAIT_MS / 1_000)
            setLostCompletionAt(Date.now())
            return
          }
          setError(formatError(value))
          setAuthState("ERROR")
        },
      }
    )
  }

  const submit = async () => {
    if (submitting.current) return
    if (customerIdValue.length < MIN_IDENTIFIER || customerIdValue.length > MAX_IDENTIFIER) {
      setError(t("sync.lbp.errors.invalidIdentifier"))
      setAuthState("ERROR")
      return
    }
    submitting.current = true
    authAttempt.current += 1
    firstSyncStarted.current = false
    notifyConnectedOnSuccess.current = false
    setLostCompletionAt(null)
    setError(null)
    setAuthState("SUBMITTING")
    try {
      const result = await lbpApi.initiateAuth(customerIdValue, rememberValue)
      const keys = result.keypad?.keys
      // Mirror the backend's keypad guard: ten distinct digit labels, or nothing is
      // clickable. The backend refuses such a keypad too, but the UI must not render
      // one if it ever arrives.
      const safeKeypad =
        Array.isArray(keys) &&
        keys.length === 10 &&
        new Set(keys).size === 10 &&
        keys.every(key => typeof key === "string" && /^[0-9]$/.test(key))
      if (!result.processId || !safeKeypad) {
        setError(t("sync.lbp.errors.formatChanged"))
        setAuthState("ERROR")
        return
      }
      const columns = Number(result.keypad.columns)
      const expires = Math.max(0, Math.floor(result.expiresInSeconds ?? 0))
      setPad({
        processId: result.processId,
        keys,
        columns: Number.isInteger(columns) && columns >= 1 && columns <= 10 ? columns : DEFAULT_COLUMNS,
      })
      setPositions([])
      setKeypadSecondsLeft(expires)
      setKeypadDeadlineAt(Date.now() + expires * 1_000)
      setAuthState("KEYPAD")
    } catch (value) {
      setError(formatError(value))
      setAuthState("ERROR")
    } finally {
      submitting.current = false
    }
  }

  const pressKey = (index: number) => {
    if (authState !== "KEYPAD") return
    setPositions((current) => (current.length >= MAX_POSITIONS ? current : [...current, index]))
  }

  const validateKeypad = async () => {
    if (!pad || authState !== "KEYPAD" || submitting.current) return
    if (positions.length < MIN_POSITIONS || positions.length > MAX_POSITIONS) return
    if (keypadDeadlineAt !== null && Date.now() > keypadDeadlineAt) {
      // The browser was suspended past the TTL: the backend would refuse it anyway.
      resetKeypad()
      setError(t("sync.lbp.errors.keypadExpired"))
      setAuthState("ERROR")
      return
    }
    submitting.current = true
    const { processId } = pad
    const sent = positions
    setAuthState("SENDING_KEYPAD")
    // The request owns the only copy from here on: nothing of it stays in state.
    resetKeypad()
    try {
      await lbpApi.sendKeypad(processId, sent)
      // The keypad TTL is over: the user now only has the sidecar's 150 s human
      // wait to approve, so that is what the countdown shows.
      setSecondsLeft(HUMAN_WAIT_SECONDS)
      setAuthState("AWAITING_APP")
      awaitSecurPass(processId)
    } catch (value) {
      setError(formatError(value))
      setAuthState("ERROR")
    } finally {
      submitting.current = false
    }
  }

  if (status.isLoading)
    return <p className="text-sm text-muted-foreground">{t("common.loading")}</p>

  const showForm = !connected && authState !== "AWAITING_APP"
  const pending =
    authState === "SUBMITTING" || authState === "KEYPAD" || authState === "SENDING_KEYPAD"

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
              ? t("sync.lbp.sessionActive")
              : t("sync.lbp.noSession")}
          </Badge>
          <p className="mt-2 text-xs text-muted-foreground">{t("sync.lbp.scope")}</p>
          {isSyncing && (
            <p className="mt-2 text-sm text-muted-foreground">
              {syncStatus === "QUEUED"
                ? t("sync.lbp.queued")
                : t("sync.lbp.syncing")}
            </p>
          )}
          {syncStatus === "SUCCESS" && status.data?.lastSyncCompletedAt && (
            <p className="mt-2 text-sm text-emerald-600 dark:text-emerald-400">
              {t("sync.lbp.syncSuccess")}
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

      {(connected || savedIdentifier !== null) && (
        <>
          <div className="flex flex-wrap gap-3">
            {connected && (
              <Button
                onClick={() => {
                  setError(null)
                  sync.mutate(undefined, {
                    onError: (value) => setError(formatSyncError(value)),
                  })
                }}
                disabled={requestingSync}
              >
                <RefreshCw className={requestingSync ? "animate-spin" : undefined} />
                {requestingSync ? t("sync.lbp.syncing") : t("sync.lbp.sync")}
              </Button>
            )}
            {!confirmingDisconnect && (
              <Button
                variant="destructive"
                onClick={() => setConfirmingDisconnect(true)}
                disabled={logout.isPending}
              >
                <LogOut />
                {t("sync.lbp.clearSession")}
              </Button>
            )}
          </div>

          {confirmingDisconnect && (
            <Card size="sm" className="border-destructive/30">
              <CardContent className="space-y-3 py-4">
                <p className="text-sm">{t("sync.lbp.disconnectConfirm")}</p>
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
                    {t("sync.lbp.disconnectConfirmAction")}
                  </Button>
                  <Button variant="outline" onClick={() => setConfirmingDisconnect(false)}>
                    {t("common.cancel")}
                  </Button>
                </div>
              </CardContent>
            </Card>
          )}

          {connected && (status.data?.unsupported.length ?? 0) > 0 && (
            <Card size="sm">
              <CardContent className="space-y-2 py-4">
                <p className="text-sm font-medium">{t("sync.lbp.unsupportedTitle")}</p>
                <p className="text-xs text-muted-foreground">
                  {t("sync.lbp.unsupportedHint")}
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
                <li>{t("sync.lbp.notice.neverStored")}</li>
                <li>{t("sync.lbp.notice.noRetry")}</li>
                <li>{t("sync.lbp.notice.lockRisk")}</li>
              </ul>
            </CardContent>
          </Card>
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              <div className="space-y-2">
                <Label htmlFor="lbp-customer-id">
                  <Landmark className="mr-1 inline-block size-4" />
                  {t("sync.lbp.customerId")}
                </Label>
                <Input
                  id="lbp-customer-id"
                  inputMode="numeric"
                  autoComplete="off"
                  minLength={MIN_IDENTIFIER}
                  maxLength={MAX_IDENTIFIER}
                  value={customerIdValue}
                  onChange={(event) => setCustomerId(event.target.value.replace(/\D/g, ""))}
                  required
                />
              </div>
              <div className="flex items-start gap-2">
                <input
                  id="lbp-remember-identifier"
                  type="checkbox"
                  checked={rememberValue}
                  onChange={(event) => setRememberIdentifier(event.target.checked)}
                  className="mt-1 size-4 shrink-0 accent-primary"
                />
                <div className="space-y-0.5">
                  <Label htmlFor="lbp-remember-identifier" className="text-sm font-normal">
                    {t("sync.lbp.rememberIdentifier")}
                  </Label>
                  <p className="text-xs text-muted-foreground">
                    {t("sync.lbp.rememberIdentifierHint")}
                  </p>
                </div>
              </div>
              <Button type="submit" disabled={pending}>
                {pending && <RefreshCw className="animate-spin" />}
                {pending ? t("sync.lbp.connecting") : t("sync.lbp.connect")}
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
              <p className="text-sm">{t("sync.lbp.securPassPrompt")}</p>
              <p className="text-xs text-muted-foreground">
                {t("sync.lbp.securPassHint")}
              </p>
              <p className="text-xs text-muted-foreground">
                {t("sync.lbp.countdownLabel")}{" "}
                <span data-testid="lbp-countdown" className="font-mono">
                  {formatCountdown(secondsLeft)}
                </span>
              </p>
            </div>
            <RefreshCw className="size-4 shrink-0 animate-spin text-muted-foreground" />
          </CardContent>
        </Card>
      )}
      <Dialog
        open={authState === "KEYPAD"}
        onOpenChange={(open) => {
          if (!open) cancelKeypad()
        }}
      >
        <DialogContent
          showCloseButton={false}
          className="sm:max-w-md"
          onInteractOutside={(event) => event.preventDefault()}
        >
          <DialogHeader>
            <DialogTitle>{t("sync.lbp.keypad.title")}</DialogTitle>
            <DialogDescription>{t("sync.lbp.keypad.description")}</DialogDescription>
          </DialogHeader>
          {pad && (
            <>
              {/* The ten shuffled digit labels. The user clicks his own digits; only
                  the clicked positions leave this component. */}
              <div
                data-testid="lbp-keypad-grid"
                className="grid gap-2"
                style={{ gridTemplateColumns: `repeat(${pad.columns}, minmax(0, 1fr))` }}
              >
                {pad.keys.map((key, index) => (
                  <button
                    key={index}
                    type="button"
                    aria-label={key}
                    className="flex aspect-square items-center justify-center rounded-lg border bg-background text-2xl font-semibold hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring"
                    onClick={() => pressKey(index)}
                  >
                    {key}
                  </button>
                ))}
              </div>
              <div className="flex items-center justify-between gap-3">
                <div
                  data-testid="lbp-keypad-dots"
                  role="img"
                  aria-label={t("sync.lbp.keypad.entered", { count: positions.length })}
                  className="flex min-h-4 flex-wrap items-center gap-1.5"
                >
                  {positions.map((_, index) => (
                    <span key={index} data-dot className="size-2.5 rounded-full bg-foreground" />
                  ))}
                </div>
                <p className="text-xs text-muted-foreground">
                  {t("sync.lbp.keypad.countdownLabel")}{" "}
                  <span data-testid="lbp-keypad-countdown" className="font-mono">
                    {formatCountdown(keypadSecondsLeft)}
                  </span>
                </p>
              </div>
              <DialogFooter className="gap-2">
                <Button type="button" variant="outline" onClick={cancelKeypad}>
                  {t("sync.lbp.keypad.cancel")}
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  disabled={positions.length === 0}
                  onClick={() => setPositions([])}
                >
                  {t("sync.lbp.keypad.clear")}
                </Button>
                <Button
                  type="button"
                  disabled={positions.length < MIN_POSITIONS || positions.length > MAX_POSITIONS}
                  onClick={() => void validateKeypad()}
                >
                  {t("sync.lbp.keypad.validate")}
                </Button>
              </DialogFooter>
            </>
          )}
        </DialogContent>
      </Dialog>
    </div>
  )
}
