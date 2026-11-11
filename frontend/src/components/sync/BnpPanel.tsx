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
import { bnpApi } from "@/features/sync/api"
import {
  useProviderClear,
  useProviderComplete,
  useProviderStatus,
  useProviderSync,
} from "@/features/sync/hooks"
import { getErrorCode, getErrorDetail, getErrorStatus } from "@/lib/errors"
import type { BnpBrand } from "@/types/api"

/**
 * BNP Paribas and Hello bank! share one connector (one session per member, the brand picks
 * the bank's site).
 *
 * `KEYPAD` is a pop-up showing the bank's virtual keypad (ten keys in the bank's shuffled
 * order): the member clicks the keys showing their own digits. Picsou never stores the
 * password, and keeps only the clicked positions (password-equivalent) in component state until
 * Validate. The second factor then depends on the member's bank settings and the bank decides:
 * `AWAITING_APP` (approve in the bank's app, the completion request stays open) or
 * `AWAITING_CODE` (type the SMS code, which is sent once and dropped).
 *
 * One attempt only. A wrong password spends a bank attempt and can lock the access, so nothing
 * here replays `initiate` or `keypad` after a failure. A mistyped SMS code may be typed again.
 *
 * UNVERIFIED against a live account: the keypad, both second factors and the product coverage
 * follow the open-source woob module (docs/features/bnp-paribas.md, "Verification boundary").
 */
type AuthState =
  | "IDLE"
  | "SUBMITTING"
  | "KEYPAD"
  | "SENDING_KEYPAD"
  | "AWAITING_APP"
  | "AWAITING_CODE"
  | "SENDING_CODE"
  | "ERROR"

/** The bank's password is exactly 6 digits; the sidecar and backend require exactly 6. */
const POSITIONS = 6
const PAD_SIZE = 10
const MIN_IDENTIFIER = 7
const MAX_IDENTIFIER = 10
const CODE_PATTERN = /^\d{4,10}$/
const DEFAULT_COLUMNS = 5
const BRANDS: BnpBrand[] = ["bnp", "hellobank"]

/** The sidecar waits this long for the human to approve in the app (APP_APPROVAL_SECONDS). */
const HUMAN_WAIT_SECONDS = 180
/** The backend's own window (200 s) and a margin. */
const LOST_COMPLETION_WAIT_MS = 210_000
const LOST_COMPLETION_POLL_MS = 3_000
/** Each poll tick is its own bounded status read; reads may overlap, the first live answer wins. */
const LOST_COMPLETION_READ_TIMEOUT_MS = 10_000

/** No answer from the backend (iOS drops requests while Safari is in the background). */
function isLostRequest(value: unknown) {
  const httpStatus = getErrorStatus(value)
  return httpStatus === undefined || httpStatus === 502 || httpStatus === 504
}

const KNOWN_CODES = new Set([
  "INVALID_CREDENTIALS",
  "INVALID_OTP",
  "KEYPAD_CHANGED",
  "KEYPAD_EXPIRED",
  "INVALID_POSITIONS",
  "MFA_TYPE_UNSUPPORTED",
  "ACTION_REQUIRED",
  "RATE_LIMITED",
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

/** Ten distinct single digits, or nothing is clickable. */
function safeKeys(keys: unknown): keys is string[] {
  return (
    Array.isArray(keys) &&
    keys.length === PAD_SIZE &&
    new Set(keys).size === PAD_SIZE &&
    keys.every((key) => typeof key === "string" && /^[0-9]$/.test(key))
  )
}

interface KeypadPad {
  processId: string
  keys: string[]
  columns: number
}

interface BnpPanelProps {
  onConnected?: () => void
}

export function BnpPanel({ onConnected }: BnpPanelProps = {}) {
  const { t } = useTranslation()
  const [authState, setAuthState] = useState<AuthState>("IDLE")
  const [customerId, setCustomerId] = useState("")
  const [brand, setBrand] = useState<BnpBrand>("bnp")
  const [pad, setPad] = useState<KeypadPad | null>(null)
  // Key positions only (reading order), never digits. Component state only: no query, no
  // mutation cache, no storage, no log.
  const [positions, setPositions] = useState<number[]>([])
  const [keypadSecondsLeft, setKeypadSecondsLeft] = useState(0)
  const [keypadDeadlineAt, setKeypadDeadlineAt] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [secondsLeft, setSecondsLeft] = useState(0)
  const [confirmingDisconnect, setConfirmingDisconnect] = useState(false)
  // The attempt waiting for the SMS code, and the code being typed (never kept past sending).
  const [codeProcessId, setCodeProcessId] = useState<string | null>(null)
  const [code, setCode] = useState("")
  const [lostCompletionAt, setLostCompletionAt] = useState<number | null>(null)
  const submitting = useRef(false)
  const notifyConnectedOnSuccess = useRef(false)
  const firstSyncStarted = useRef(false)
  const authAttempt = useRef(0)

  const status = useProviderStatus("bnp")
  const complete = useProviderComplete("bnp")
  const sync = useProviderSync("bnp")
  const logout = useProviderClear("bnp")
  const { mutate: completeMutate } = complete
  const { mutate: syncMutate } = sync
  const { refetch: refetchStatus } = status

  const messageForCode = useCallback(
    (value: string | null | undefined) => {
      switch (value) {
        case "INVALID_CREDENTIALS":
          return t("sync.bnp.errors.invalidCredentials")
        case "INVALID_OTP":
          return t("sync.bnp.errors.invalidCode")
        case "KEYPAD_CHANGED":
          return t("sync.bnp.errors.keypadChanged")
        case "KEYPAD_EXPIRED":
          return t("sync.bnp.errors.keypadExpired")
        case "INVALID_POSITIONS":
          return t("sync.bnp.errors.invalidPositions")
        case "MFA_TYPE_UNSUPPORTED":
          return t("sync.bnp.errors.mfaUnsupported")
        case "ACTION_REQUIRED":
          return t("sync.bnp.errors.actionRequired")
        case "RATE_LIMITED":
          return t("sync.bnp.errors.rateLimited")
        case "APP_VALIDATION_TIMEOUT":
          return t("sync.bnp.errors.appValidationTimeout")
        case "AUTH_ATTEMPT_EXPIRED":
          return t("sync.bnp.errors.authAttemptExpired")
        case "SESSION_EXPIRED":
          return t("sync.bnp.errors.sessionExpired")
        case "UPSTREAM_FORMAT_CHANGED":
          return t("sync.bnp.errors.formatChanged")
        case "INVALID_SESSION_STATE":
          return t("sync.bnp.errors.invalidSessionState")
        case "UPSTREAM_UNAVAILABLE":
        case "INTERNAL_ERROR":
          return t("sync.bnp.errors.serverError")
        default:
          return null
      }
    },
    [t],
  )

  // Never shows a raw backend message: an unknown failure gets the generic text.
  const formatError = useCallback(
    (value: unknown) => {
      if (getErrorStatus(value) === 429) {
        const seconds = retryAfterSeconds(value)
        return seconds
          ? t("sync.bnp.errors.rateLimitedRetryAfter", { minutes: Math.ceil(seconds / 60) })
          : t("sync.bnp.errors.rateLimited")
      }
      return messageForCode(errorCodeOf(value)) ?? t("sync.bnp.errors.serverError")
    },
    [messageForCode, t],
  )

  const formatSyncError = useCallback(
    (value: unknown) => {
      if (getErrorStatus(value) === 429) {
        const seconds = retryAfterSeconds(value)
        return seconds
          ? t("sync.bnp.errors.tooManySyncsRetryAfter", { minutes: Math.ceil(seconds / 60) })
          : t("sync.bnp.errors.tooManySyncs")
      }
      return messageForCode(errorCodeOf(value)) ?? t("sync.bnp.errors.serverError")
    },
    [messageForCode, t],
  )

  const startFirstSync = useCallback(() => {
    if (firstSyncStarted.current) return
    firstSyncStarted.current = true
    setLostCompletionAt(null)
    setAuthState("IDLE")
    setCustomerId("")
    setCode("")
    setCodeProcessId(null)
    setConfirmingDisconnect(false)
    void refetchStatus()
    // Armed before the call: a manual retry after a failed first import must still tell the
    // caller "connected" once it succeeds.
    notifyConnectedOnSuccess.current = true
    syncMutate(undefined, { onError: (value) => setError(formatSyncError(value)) })
  }, [formatSyncError, refetchStatus, syncMutate])

  useEffect(() => {
    if (lostCompletionAt === null) return
    let cancelled = false
    const attempt = authAttempt.current
    const timer = setInterval(() => {
      if (Date.now() - lostCompletionAt >= LOST_COMPLETION_WAIT_MS) {
        clearInterval(timer)
        setError(t("sync.bnp.errors.appValidationTimeout"))
        setAuthState("ERROR")
        return
      }
      const readStartedAt = Date.now()
      void bnpApi
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

  // A login that finished while the panel was unmounted still needs its first sync queued:
  // the stored session holds no accounts until then. Decided only from a settled, fresh
  // status; a FAILED first sync waits for the member's explicit retry.
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
      ? (messageForCode(status.data?.lastSyncError) ?? t("sync.bnp.errors.serverError"))
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

  // The pad is stable for a short time only: once out of date nothing may be sent. The
  // countdown comes from an absolute deadline, so a suspended tab shows the true remaining time.
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

  const resetKeypad = () => {
    setPad(null)
    setPositions([])
    setKeypadSecondsLeft(0)
    setKeypadDeadlineAt(null)
  }

  const expired = authState === "KEYPAD" && keypadSecondsLeft <= 0
  if (expired) {
    // Derived during render (React's "adjust state on change" pattern), not in an effect.
    resetKeypad()
    setError(t("sync.bnp.errors.keypadExpired"))
    setAuthState("ERROR")
  }

  const cancelKeypad = () => {
    if (authState !== "KEYPAD") return
    resetKeypad()
    setError(null)
    setAuthState("IDLE")
  }

  const awaitApproval = (processId: string) => {
    // One completion call: only session status may be polled if its answer is lost.
    completeMutate(
      { processId },
      {
        onSuccess: startFirstSync,
        onError: (value) => {
          if (isLostRequest(value)) {
            // Keep the waiting card: the session status tells whether it went through.
            setSecondsLeft(LOST_COMPLETION_WAIT_MS / 1_000)
            setLostCompletionAt(Date.now())
            return
          }
          setError(formatError(value))
          setAuthState("ERROR")
        },
      },
    )
  }

  const submit = async () => {
    if (submitting.current) return
    if (
      customerId.length < MIN_IDENTIFIER ||
      customerId.length > MAX_IDENTIFIER ||
      !/^\d+$/.test(customerId)
    ) {
      setError(t("sync.bnp.errors.invalidIdentifier"))
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
      const result = await bnpApi.initiateAuth(customerId, brand)
      const keys = result.keypad?.keys
      if (!result.processId || !safeKeys(keys)) {
        setError(t("sync.bnp.errors.formatChanged"))
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

  const pressTile = (index: number) => {
    if (authState !== "KEYPAD") return
    setPositions((current) => (current.length >= POSITIONS ? current : [...current, index]))
  }

  const validateKeypad = async () => {
    if (!pad || authState !== "KEYPAD" || submitting.current) return
    if (positions.length !== POSITIONS) return
    if (keypadDeadlineAt !== null && Date.now() > keypadDeadlineAt) {
      resetKeypad()
      setError(t("sync.bnp.errors.keypadExpired"))
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
      const ack = await bnpApi.sendKeypad(processId, sent)
      if (ack.status === "CODE_REQUIRED") {
        setCode("")
        setCodeProcessId(processId)
        setAuthState("AWAITING_CODE")
      } else if (ack.status === "APPROVAL_PENDING") {
        setSecondsLeft(HUMAN_WAIT_SECONDS)
        setAuthState("AWAITING_APP")
        awaitApproval(processId)
      } else {
        // An answer this panel does not know is never guessed at.
        setError(t("sync.bnp.errors.mfaUnsupported"))
        setAuthState("ERROR")
      }
    } catch (value) {
      setError(formatError(value))
      setAuthState("ERROR")
    } finally {
      submitting.current = false
    }
  }

  const submitCode = () => {
    if (!codeProcessId || authState !== "AWAITING_CODE" || submitting.current) return
    if (!CODE_PATTERN.test(code)) {
      setError(t("sync.bnp.errors.invalidCode"))
      return
    }
    submitting.current = true
    const processId = codeProcessId
    const sent = code
    setError(null)
    setCode("")
    setAuthState("SENDING_CODE")
    completeMutate(
      { processId, code: sent },
      {
        onSuccess: () => {
          submitting.current = false
          startFirstSync()
        },
        onError: (value) => {
          submitting.current = false
          if (errorCodeOf(value) === "INVALID_OTP") {
            // The attempt is still open on the bank's side: the code may be typed again.
            setError(t("sync.bnp.errors.invalidCode"))
            setAuthState("AWAITING_CODE")
            return
          }
          setCodeProcessId(null)
          setError(formatError(value))
          setAuthState("ERROR")
        },
      },
    )
  }

  if (status.isLoading) return <p className="text-sm text-muted-foreground">{t("common.loading")}</p>

  const waiting = authState === "AWAITING_APP" || authState === "AWAITING_CODE" || authState === "SENDING_CODE"
  const showForm = !connected && !waiting
  const pending =
    authState === "SUBMITTING" || authState === "KEYPAD" || authState === "SENDING_KEYPAD"

  return (
    <div className="space-y-6">
      <Card size="sm">
        <CardContent className="py-4">
          <Badge
            className={connected ? "bg-green-500/10 text-green-600 dark:text-green-400" : undefined}
            variant={connected ? "default" : "outline"}
          >
            {connected ? t("sync.bnp.sessionActive") : t("sync.bnp.noSession")}
          </Badge>
          <p className="mt-2 text-xs text-muted-foreground">{t("sync.bnp.scope")}</p>
          {isSyncing && (
            <p className="mt-2 text-sm text-muted-foreground">
              {syncStatus === "QUEUED" ? t("sync.bnp.queued") : t("sync.bnp.syncing")}
            </p>
          )}
          {syncStatus === "SUCCESS" && status.data?.lastSyncCompletedAt && (
            <p className="mt-2 text-sm text-emerald-600 dark:text-emerald-400">
              {t("sync.bnp.syncSuccess")}
            </p>
          )}
        </CardContent>
      </Card>

      {visibleError && (
        <Card size="sm" className="border-destructive/30">
          <CardContent className="flex items-center gap-3 py-4">
            <AlertTriangle className="size-5 shrink-0 text-destructive" />
            <p role="alert" className="flex-1 text-sm text-destructive">{visibleError}</p>
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
                sync.mutate(undefined, { onError: (value) => setError(formatSyncError(value)) })
              }}
              disabled={requestingSync}
            >
              <RefreshCw className={requestingSync ? "animate-spin" : undefined} />
              {requestingSync ? t("sync.bnp.syncing") : t("sync.bnp.sync")}
            </Button>
            {!confirmingDisconnect && (
              <Button
                variant="destructive"
                onClick={() => setConfirmingDisconnect(true)}
                disabled={logout.isPending}
              >
                <LogOut />
                {t("sync.bnp.clearSession")}
              </Button>
            )}
          </div>

          {confirmingDisconnect && (
            <Card size="sm" className="border-destructive/30">
              <CardContent className="space-y-3 py-4">
                <p className="text-sm">{t("sync.bnp.disconnectConfirm")}</p>
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
                    {t("sync.bnp.disconnectConfirmAction")}
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
                <p className="text-sm font-medium">{t("sync.bnp.unsupportedTitle")}</p>
                <p className="text-xs text-muted-foreground">{t("sync.bnp.unsupportedHint")}</p>
                {/* Family code only: the contract id is never shown. */}
                <ul className="flex flex-wrap gap-2">
                  {status.data?.unsupported.map((product, index) => (
                    <li key={`${product.familyCode}-${index}`}>
                      <Badge variant="outline">
                        <span>{product.familyCode}</span>
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
                <li>{t("sync.bnp.notice.neverStored")}</li>
                <li>{t("sync.bnp.notice.noRetry")}</li>
                <li>{t("sync.bnp.notice.lockRisk")}</li>
                <li>{t("sync.bnp.notice.unverified")}</li>
              </ul>
            </CardContent>
          </Card>
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              <fieldset className="space-y-2">
                <legend className="text-sm font-medium">{t("sync.bnp.brand")}</legend>
                <div className="flex flex-wrap gap-4">
                  {BRANDS.map((value) => (
                    <label key={value} className="flex items-center gap-2 text-sm">
                      <input
                        type="radio"
                        name="bnp-brand"
                        value={value}
                        checked={brand === value}
                        onChange={() => setBrand(value)}
                        className="size-4 accent-primary"
                      />
                      {t(`sync.bnp.brands.${value}`)}
                    </label>
                  ))}
                </div>
              </fieldset>
              <div className="space-y-2">
                <Label htmlFor="bnp-customer-id">
                  <Landmark className="mr-1 inline-block size-4" />
                  {t("sync.bnp.customerId")}
                </Label>
                <Input
                  id="bnp-customer-id"
                  inputMode="numeric"
                  autoComplete="off"
                  minLength={MIN_IDENTIFIER}
                  maxLength={MAX_IDENTIFIER}
                  value={customerId}
                  onChange={(event) => setCustomerId(event.target.value.replace(/\D/g, ""))}
                  required
                />
              </div>
              <Button type="submit" disabled={pending}>
                {pending && <RefreshCw className="animate-spin" />}
                {pending ? t("sync.bnp.connecting") : t("sync.bnp.connect")}
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
              <p className="text-sm">{t("sync.bnp.approvalPrompt")}</p>
              <p className="text-xs text-muted-foreground">{t("sync.bnp.approvalHint")}</p>
              <p className="text-xs text-muted-foreground">
                {t("sync.bnp.countdownLabel")}{" "}
                <span data-testid="bnp-countdown" className="font-mono">
                  {formatCountdown(secondsLeft)}
                </span>
              </p>
            </div>
            <RefreshCw className="size-4 shrink-0 animate-spin text-muted-foreground" />
          </CardContent>
        </Card>
      )}

      {!connected && (authState === "AWAITING_CODE" || authState === "SENDING_CODE") && (
        <form
          onSubmit={(event) => {
            event.preventDefault()
            submitCode()
          }}
        >
          <Card size="sm">
            <CardContent className="space-y-3 py-4">
              <p className="text-sm">{t("sync.bnp.codePrompt")}</p>
              <p className="text-xs text-muted-foreground">{t("sync.bnp.codeHint")}</p>
              <div className="space-y-2">
                <Label htmlFor="bnp-code">{t("sync.bnp.code")}</Label>
                <Input
                  id="bnp-code"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  maxLength={10}
                  value={code}
                  disabled={authState === "SENDING_CODE"}
                  onChange={(event) => setCode(event.target.value.replace(/\D/g, ""))}
                />
              </div>
              <Button type="submit" disabled={authState === "SENDING_CODE" || !CODE_PATTERN.test(code)}>
                {authState === "SENDING_CODE" && <RefreshCw className="animate-spin" />}
                {authState === "SENDING_CODE" ? t("sync.bnp.sendingCode") : t("sync.bnp.sendCode")}
              </Button>
            </CardContent>
          </Card>
        </form>
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
            <DialogTitle>{t("sync.bnp.keypad.title")}</DialogTitle>
            <DialogDescription>{t("sync.bnp.keypad.description")}</DialogDescription>
          </DialogHeader>
          {pad && (
            <>
              {/* The ten shuffled keys: the member clicks the keys showing their own digits. Only the clicked positions leave this component. */}
              <div
                data-testid="bnp-keypad-grid"
                className="grid gap-2"
                style={{ gridTemplateColumns: `repeat(${pad.columns}, minmax(0, 1fr))` }}
              >
                {pad.keys.map((key, index) => (
                  <button
                    key={index}
                    type="button"
                    aria-label={t("sync.bnp.keypad.key", { position: index + 1 })}
                    className="flex aspect-square items-center justify-center rounded-lg border bg-background text-lg font-semibold hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring"
                    onClick={() => pressTile(index)}
                  >
                    <span aria-hidden="true">{key}</span>
                  </button>
                ))}
              </div>
              <div className="flex items-center justify-between gap-3">
                <div
                  data-testid="bnp-keypad-dots"
                  role="img"
                  aria-label={t("sync.bnp.keypad.entered", { count: positions.length })}
                  className="flex min-h-4 flex-wrap items-center gap-1.5"
                >
                  {positions.map((_, index) => (
                    <span key={index} data-dot className="size-2.5 rounded-full bg-foreground" />
                  ))}
                </div>
                <p className="text-xs text-muted-foreground">
                  {t("sync.bnp.keypad.countdownLabel")}{" "}
                  <span data-testid="bnp-keypad-countdown" className="font-mono">
                    {formatCountdown(keypadSecondsLeft)}
                  </span>
                </p>
              </div>
              <DialogFooter className="gap-2">
                <Button type="button" variant="outline" onClick={cancelKeypad}>
                  {t("sync.bnp.keypad.cancel")}
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  disabled={positions.length === 0}
                  onClick={() => setPositions([])}
                >
                  {t("sync.bnp.keypad.clear")}
                </Button>
                <Button
                  type="button"
                  disabled={positions.length !== POSITIONS}
                  onClick={() => void validateKeypad()}
                >
                  {t("sync.bnp.keypad.validate")}
                </Button>
              </DialogFooter>
            </>
          )}
        </DialogContent>
      </Dialog>
    </div>
  )
}
