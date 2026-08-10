import { useEffect, useRef, useState } from "react"
import type { UseMutationResult, UseQueryResult } from "@tanstack/react-query"
import {
  AlertTriangle,
  Lock,
  LogOut,
  RefreshCw,
  ShieldCheck,
  Smartphone,
  type LucideIcon,
} from "lucide-react"
import { useTranslation } from "react-i18next"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { extractErrorMessage, getErrorCode, getErrorStatus } from "@/lib/errors"

/**
 * `AWAITING_APP` has no form: the provider pushes a notification to its mobile
 * app and the completion request simply stays open until the user approves it.
 * Only reachable when the panel declares {@link SidecarSessionPanelProps.appPush}.
 */
type AuthState = "IDLE" | "AWAITING_OTP" | "AWAITING_APP" | "ERROR"

/** Shape shared by every sidecar session status DTO. */
export interface SidecarSessionStatus {
  isActive: boolean
  syncStatus: "IDLE" | "QUEUED" | "RUNNING" | "SUCCESS" | "FAILED"
  lastSyncCompletedAt: string | null
  lastSyncError: string | null
}

/** Shape shared by every sidecar `auth/initiate` response. */
export interface SidecarAuthInitResponse {
  processId: string | null
  mfaRequired: boolean
  mfaType: string | null
}

interface Credentials {
  login: string
  password: string
}

interface SecondFactor {
  processId: string
  code?: string
}

/**
 * Backend error code -> `<prefix>.errors.<suffix>` translation suffix. The union
 * of every sidecar provider's error enum: a panel only ever sees the codes its
 * own backend enum can produce, so a suffix it has no translation for is
 * unreachable.
 */
const ERROR_MESSAGE_SUFFIXES: Record<string, string> = {
  INVALID_CREDENTIALS: "invalidCredentials",
  CAPTCHA_BLOCKED: "captchaBlocked",
  INVALID_OTP: "invalidCode",
  APP_VALIDATION_TIMEOUT: "appValidationTimeout",
  AUTH_ATTEMPT_EXPIRED: "authAttemptExpired",
  SESSION_EXPIRED: "sessionExpired",
  PORTFOLIO_INCOMPLETE: "portfolioIncomplete",
  UPSTREAM_FORMAT_CHANGED: "formatChanged",
  INVALID_DATA: "invalidData",
  UPSTREAM_UNAVAILABLE: "serverError",
  INTERNAL_ERROR: "serverError",
}

export interface SidecarSessionPanelProps<
  TStatus extends SidecarSessionStatus,
  TInit extends SidecarAuthInitResponse,
> {
  /** i18n namespace holding the panel's copy, e.g. `sync.amundi`. */
  translationPrefix: string
  /** Prefix for the form control ids, e.g. `amundi` -> `amundi-login`. */
  fieldIdPrefix: string
  /** Icon shown next to the login field — the provider's own metaphor. */
  loginIcon: LucideIcon
  /** Whether the provider can answer the second factor with a mobile app push. */
  appPush?: boolean
  useStatus: () => UseQueryResult<TStatus>
  useInitiateAuth: () => UseMutationResult<TInit, unknown, Credentials>
  useCompleteAuth: () => UseMutationResult<TStatus, unknown, SecondFactor>
  useSync: () => UseMutationResult<TStatus, unknown, void>
  useClearSession: () => UseMutationResult<unknown, unknown, void>
  onConnected?: () => void
}

/**
 * Login + second factor + sync/disconnect for a browser-sidecar provider
 * (Amundi, Bourse Direct): the backend only queues the import, so the panel
 * polls the session status and surfaces a background failure the same way it
 * surfaces a failed request.
 */
export function SidecarSessionPanel<
  TStatus extends SidecarSessionStatus,
  TInit extends SidecarAuthInitResponse,
>({
  translationPrefix,
  fieldIdPrefix,
  loginIcon: LoginIcon,
  appPush = false,
  useStatus,
  useInitiateAuth,
  useCompleteAuth,
  useSync,
  useClearSession,
  onConnected,
}: SidecarSessionPanelProps<TStatus, TInit>) {
  const { t } = useTranslation()
  const [authState, setAuthState] = useState<AuthState>("IDLE")
  const [login, setLogin] = useState("")
  const [password, setPassword] = useState("")
  const [code, setCode] = useState("")
  const [processId, setProcessId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const notifyConnectedOnSuccess = useRef(false)

  const status = useStatus()
  const initiate = useInitiateAuth()
  const complete = useCompleteAuth()
  const sync = useSync()
  const logout = useClearSession()
  const { mutate: completeMutate } = complete

  const key = (suffix: string) => `${translationPrefix}.${suffix}`

  const messageForCode = (value: string | null | undefined) => {
    const suffix = value ? ERROR_MESSAGE_SUFFIXES[value] : undefined
    return suffix ? t(key(`errors.${suffix}`)) : null
  }

  const formatError = (value: unknown) => {
    const httpStatus = getErrorStatus(value)
    if (httpStatus === 429) return t(key("errors.tooManyAttempts"))
    const codedMessage = messageForCode(getErrorCode(value))
    if (codedMessage) return codedMessage
    return extractErrorMessage(value, t(key("errors.serverError")))
  }

  const resetAuth = () => {
    setAuthState("IDLE")
    setProcessId(null)
    setCode("")
  }

  const submitSecondFactor = (id: string, otp?: string) => {
    setError(null)
    completeMutate(
      { processId: id, code: otp },
      {
        onSuccess: (result) => {
          notifyConnectedOnSuccess.current = true
          resetAuth()
          setLogin("")
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
          setProcessId(null)
          setCode("")
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
        t(key("errors.serverError")))
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
            {connected ? t(key("sessionActive")) : t(key("noSession"))}
          </Badge>
          <p className="mt-2 text-xs text-muted-foreground">
            {t(key("scope"))}
          </p>
          {isSyncing && (
            <p className="mt-2 text-sm text-muted-foreground">
              {syncStatus === "QUEUED" ? t(key("queued")) : t(key("syncing"))}
            </p>
          )}
          {syncStatus === "SUCCESS" && status.data?.lastSyncCompletedAt && (
            <p className="mt-2 text-sm text-emerald-600 dark:text-emerald-400">
              {t(key("syncSuccess"))}
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
            {requestingSync ? t(key("syncing")) : t(key("sync"))}
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
            {t(key("clearSession"))}
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
                      setError(t(key("errors.formatChanged")))
                      setAuthState("ERROR")
                      return
                    }
                    setProcessId(result.processId)
                    if (appPush && result.mfaType === "APP_PUSH") {
                      // Nothing to type: hold the completion request open
                      // while the user approves the push on their phone.
                      setAuthState("AWAITING_APP")
                      submitSecondFactor(result.processId)
                      return
                    }
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
                <Label htmlFor={`${fieldIdPrefix}-login`}>
                  <LoginIcon className="mr-1 inline-block size-4" />
                  {t(key("login"))}
                </Label>
                <Input
                  id={`${fieldIdPrefix}-login`}
                  autoComplete="username"
                  value={login}
                  onChange={(event) => setLogin(event.target.value)}
                  required
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor={`${fieldIdPrefix}-password`}>
                  <Lock className="mr-1 inline-block size-4" />
                  {t(key("password"))}
                </Label>
                <Input
                  id={`${fieldIdPrefix}-password`}
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
                  ? t(key("connecting"))
                  : t(key("connect"))}
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
              <p className="text-sm">{t(key("appValidationPrompt"))}</p>
              <p className="text-xs text-muted-foreground">
                {t(key("appValidationHint"))}
              </p>
            </div>
            <RefreshCw className="size-4 shrink-0 animate-spin text-muted-foreground" />
          </CardContent>
        </Card>
      )}

      {!connected && authState === "AWAITING_OTP" && (
        <form
          onSubmit={(event) => {
            event.preventDefault()
            if (!processId) return
            submitSecondFactor(processId, code)
          }}
        >
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              <p className="text-sm text-muted-foreground">
                {t(key("otpPrompt"))}
              </p>
              <div className="space-y-2">
                <Label htmlFor={`${fieldIdPrefix}-otp`}>
                  <ShieldCheck className="mr-1 inline-block size-4" />
                  {t(key("otpCode"))}
                </Label>
                <Input
                  id={`${fieldIdPrefix}-otp`}
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
                  ? t(key("validating"))
                  : t(key("validate"))}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}
    </div>
  )
}
