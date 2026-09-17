import { useEffect, useRef, useState } from "react"
import {
  AlertTriangle,
  Landmark,
  Lock,
  LogOut,
  RefreshCw,
  Smartphone,
} from "lucide-react"
import { useTranslation } from "react-i18next"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  useBoursoSessionStatus,
  useClearBoursoSession,
  useCompleteBoursoAuth,
  useInitiateBoursoAuth,
  useSyncBourso,
} from "@/features/sync/hooks"
import { extractErrorMessage, getErrorCode, getErrorStatus } from "@/lib/errors"
import type { BoursoErrorCode } from "@/types/api"

/**
 * There is no OTP state: BoursoBank's app validation is the only second factor
 * this connector drives, so the completion request simply stays open while the
 * user approves the push on their phone. An SMS or e-mail prompt comes back as
 * `MFA_TYPE_UNSUPPORTED` instead of a form we cannot honour.
 */
type AuthState = "IDLE" | "AWAITING_APP" | "ERROR"

interface BoursoPanelProps {
  onConnected?: () => void
}
export function BoursoPanel({ onConnected }: BoursoPanelProps = {}) {
  const { t } = useTranslation()
  const [authState, setAuthState] = useState<AuthState>("IDLE")
  const [customerId, setCustomerId] = useState("")
  const [password, setPassword] = useState("")
  const [error, setError] = useState<string | null>(null)
  const notifyConnectedOnSuccess = useRef(false)

  const status = useBoursoSessionStatus()
  const initiate = useInitiateBoursoAuth()
  const complete = useCompleteBoursoAuth()
  const sync = useSyncBourso()
  const logout = useClearBoursoSession()
  const { mutate: completeMutate } = complete

  const messageForCode = (value: BoursoErrorCode | string | null | undefined) => {
    switch (value) {
      case "INVALID_CREDENTIALS":
        return t("sync.bourso.errors.invalidCredentials")
      case "FRAUD_ACK_REQUIRED":
        return t("sync.bourso.errors.fraudAckRequired")
      case "MFA_TYPE_UNSUPPORTED":
        return t("sync.bourso.errors.mfaTypeUnsupported")
      case "APP_VALIDATION_TIMEOUT":
        return t("sync.bourso.errors.appValidationTimeout")
      case "AUTH_ATTEMPT_EXPIRED":
        return t("sync.bourso.errors.authAttemptExpired")
      case "SESSION_EXPIRED":
        return t("sync.bourso.errors.sessionExpired")
      case "PORTFOLIO_INCOMPLETE":
        return t("sync.bourso.errors.portfolioIncomplete")
      case "UPSTREAM_FORMAT_CHANGED":
        return t("sync.bourso.errors.formatChanged")
      case "INVALID_DATA":
        return t("sync.bourso.errors.invalidData")
      case "UPSTREAM_UNAVAILABLE":
      case "INTERNAL_ERROR":
        return t("sync.bourso.errors.serverError")
      default:
        return null
    }
  }

  const formatError = (value: unknown) => {
    const httpStatus = getErrorStatus(value)
    if (httpStatus === 429) return t("sync.bourso.errors.tooManyAttempts")
    const codedMessage = messageForCode(getErrorCode(value))
    if (codedMessage) return codedMessage
    return extractErrorMessage(value, t("sync.bourso.errors.serverError"))
  }

  const resetAuth = () => {
    setAuthState("IDLE")
  }

  const awaitAppValidation = (id: string) => {
    setError(null)
    completeMutate(
      { processId: id },
      {
        onSuccess: (result) => {
          notifyConnectedOnSuccess.current = true
          resetAuth()
          setCustomerId("")
          setPassword("")
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
        },
      }
    )
  }

  const connected = status.data?.isActive === true
  const syncStatus = status.data?.syncStatus ?? "IDLE"
  const isSyncing = syncStatus === "QUEUED" || syncStatus === "RUNNING"
  const requestingSync = sync.isPending || isSyncing
  const backgroundError =
    syncStatus === "FAILED"
      ? (messageForCode(status.data?.lastSyncError) ??
        t("sync.bourso.errors.serverError"))
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
              ? t("sync.bourso.sessionActive")
              : t("sync.bourso.noSession")}
          </Badge>
          <p className="mt-2 text-xs text-muted-foreground">
            {t("sync.bourso.scope")}
          </p>
          {isSyncing && (
            <p className="mt-2 text-sm text-muted-foreground">
              {syncStatus === "QUEUED"
                ? t("sync.bourso.queued")
                : t("sync.bourso.syncing")}
            </p>
          )}
          {syncStatus === "SUCCESS" && status.data?.lastSyncCompletedAt && (
            <p className="mt-2 text-sm text-emerald-600 dark:text-emerald-400">
              {t("sync.bourso.syncSuccess")}
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
                  resetAuth()
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
              ? t("sync.bourso.syncing")
              : t("sync.bourso.sync")}
          </Button>
          <Button
            variant="destructive"
            onClick={() => {
              setError(null)
              logout.mutate(undefined, {
                onSuccess: () => {
                  notifyConnectedOnSuccess.current = false
                  resetAuth()
                },
                onError: (value) => setError(formatError(value)),
              })
            }}
            disabled={logout.isPending}
          >
            <LogOut />
            {t("sync.bourso.clearSession")}
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
              { customerId, password },
              {
                onSuccess: (result) => {
                  setPassword("")
                  if (result.mfaRequired) {
                    if (!result.processId) {
                      setError(t("sync.bourso.errors.formatChanged"))
                      setAuthState("ERROR")
                      return
                    }
                    // Nothing to type: hold the completion request open while
                    // the user approves the push in the BoursoBank app.
                    setAuthState("AWAITING_APP")
                    awaitAppValidation(result.processId)
                    return
                  }
                  notifyConnectedOnSuccess.current = true
                  setCustomerId("")
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
                <Label htmlFor="bourso-customer-id">
                  <Landmark className="mr-1 inline-block size-4" />
                  {t("sync.bourso.customerId")}
                </Label>
                <Input
                  id="bourso-customer-id"
                  inputMode="numeric"
                  autoComplete="username"
                  value={customerId}
                  onChange={(event) =>
                    setCustomerId(event.target.value.replace(/\D/g, ""))
                  }
                  required
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="bourso-password">
                  <Lock className="mr-1 inline-block size-4" />
                  {t("sync.bourso.password")}
                </Label>
                {/* BoursoBank passwords are digits only -- its virtual keyboard
                    cannot encode anything else, and a stray character would spend
                    a login attempt on a password the user never typed. */}
                <Input
                  id="bourso-password"
                  type="password"
                  inputMode="numeric"
                  autoComplete="current-password"
                  value={password}
                  onChange={(event) =>
                    setPassword(event.target.value.replace(/\D/g, ""))
                  }
                  required
                />
              </div>
              <Button type="submit" disabled={initiate.isPending}>
                {initiate.isPending && <RefreshCw className="animate-spin" />}
                {initiate.isPending
                  ? t("sync.bourso.connecting")
                  : t("sync.bourso.connect")}
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
              <p className="text-sm">{t("sync.bourso.appValidationPrompt")}</p>
              <p className="text-xs text-muted-foreground">
                {t("sync.bourso.appValidationHint")}
              </p>
            </div>
            <RefreshCw className="size-4 shrink-0 animate-spin text-muted-foreground" />
          </CardContent>
        </Card>
      )}
    </div>
  )
}
