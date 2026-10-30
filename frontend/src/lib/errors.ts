interface ApiErrorBody {
  detail?: string | null
  message?: string | null
  code?: string | null
}

function tryParseJson(s: string): ApiErrorBody | null {
  try { return JSON.parse(s) } catch { return null }
}

// Strings matching this leak server internals or are raw axios noise — they must
// never be shown to the user. Callers fall back to a friendly i18n string instead.
const LEAK_PATTERN = /Exception|\.java\b|\bjava\.|\borg\.|com\.picsou|Request failed with status code|stack ?trace|Network Error|AxiosError|Failed to fetch/i

function isSafeMessage(s: string): boolean {
  return s.trim().length > 0 && !LEAK_PATTERN.test(s)
}

/**
 * Returns a user-safe message the backend explicitly sent (ProblemDetail `detail`,
 * a `message` field, or a JSON-embedded `message`), or `null` when nothing safe is
 * available. Anything that looks like a stack trace / class name / axios default is
 * rejected so it can be replaced by a friendly fallback.
 */
export function safeBackendMessage(err: unknown): string | null {
  const axErr = err as { response?: { data?: ApiErrorBody }; message?: string }

  const detail = axErr?.response?.data?.detail
  if (detail && typeof detail === 'string') {
    const jsonStart = detail.indexOf('{')
    if (jsonStart >= 0) {
      const parsed = tryParseJson(detail.slice(jsonStart))
      if (parsed?.message && isSafeMessage(parsed.message)) return parsed.message
    }
    if (isSafeMessage(detail)) return detail
  }

  const bodyMessage = axErr?.response?.data?.message
  if (bodyMessage && typeof bodyMessage === 'string' && isSafeMessage(bodyMessage)) {
    return bodyMessage
  }

  const msg = axErr?.message
  if (msg && typeof msg === 'string') {
    if (msg.startsWith('{')) {
      const parsed = tryParseJson(msg)
      if (parsed?.message && isSafeMessage(parsed.message)) return parsed.message
    }
    if (isSafeMessage(msg)) return msg
  }

  return null
}

export function extractErrorMessage(err: unknown, fallback = 'Une erreur est survenue'): string {
  return safeBackendMessage(err) ?? fallback
}

/** HTTP status of an axios-style error, or `undefined` when not available. */
export function getErrorStatus(err: unknown): number | undefined {
  return (err as { response?: { status?: number } })?.response?.status
}

/** Backend `detail` string of an axios-style error, or `undefined` when absent. */
export function getErrorDetail(err: unknown): string | undefined {
  const detail = (err as { response?: { data?: ApiErrorBody } })?.response?.data?.detail
  return typeof detail === 'string' ? detail : undefined
}

/** True for a client-side axios timeout (ECONNABORTED) or an explicit 408 from the backend. */
export function isTimeoutError(err: unknown): boolean {
  if (getErrorStatus(err) === 408) return true
  return (err as { code?: string })?.code === 'ECONNABORTED'
}

/** Stable machine-readable code attached to a ProblemDetail response. */
export function getErrorCode(err: unknown): string | undefined {
  const code = (err as { response?: { data?: ApiErrorBody } })?.response?.data?.code
  return typeof code === 'string' ? code : undefined
}

// formatApiError only ever calls t with a single key argument, so we type it as
// such. A looser `(key, fallback?: string)` signature would reject the real
// i18next TFunction, whose second positional arg is an options object / default
// value rather than a plain string — that mismatch is what broke the CI build.
type TFunc = (key: string) => string

/**
 * Maps any API error to a friendly, translated string.
 *
 * - 401 / 429 / 5xx → a generic translated message (the backend detail here is
 *   either absent or the deliberately-vague "An unexpected error occurred").
 * - 4xx → the backend's *specific* reason when it's user-safe (e.g. "Cannot delete
 *   the last administrator", "Username already taken"); otherwise a translated
 *   fallback (403 → "forbidden", others → `fallbackKey`).
 *
 * Never returns raw axios text or leaked internals — those are filtered by
 * {@link safeBackendMessage}.
 */
/**
 * Maps a backend `detail` string to a translated TR error message, or `null`
 * when it carries no known Trade Republic error code. Single source for both
 * the 422 and 5xx branches of {@link formatTrAuthError} so they can't drift.
 */
function matchTrDetail(detail: string, t: TFunc): string | null {
  if (detail.includes('authentication service is unavailable')) return t('sync.tr.errors.serviceUnavailable')
  if (detail.includes('VALIDATION_CODE_INVALID') || detail.includes('verification code is invalid')) return t('sync.tr.errors.invalidTan')
  if (detail.includes('NUMBER_INVALID')) return t('sync.tr.errors.invalidPhoneNumber')
  if (detail.includes('PIN_INVALID')) return t('sync.tr.errors.invalidPin')
  if (detail.includes('AUTHENTICATION_ERROR')) return t('sync.tr.errors.authenticationFailed')
  if (detail.includes('expired')) return t('sync.tr.errors.authenticationFailed')
  return null
}

/**
 * True when a TR sync/auth error means the stored session is genuinely dead
 * (rejected refresh, cleared session) — the caller should offer re-auth.
 * Matches the exact backend messages from TradeRepublicSyncService; kept next
 * to {@link formatTrAuthError} so the two substring taxonomies evolve together.
 */
export function isTrSessionDeadError(err: unknown): boolean {
  const detail = getErrorDetail(err) || ''
  return (
    detail.includes('expired') ||
    detail.includes('reconnect') ||
    detail.includes('No Trade Republic session')
  )
}

/**
 * Maps Trade Republic auth errors (credentials initiation / TAN completion) to
 * translated messages. TR rejections are `SyncException`s, which the backend
 * maps to HTTP 422 with the upstream error code in the ProblemDetail `detail`
 * (5xx only occurs for unmapped/proxy failures, kept for robustness). Shared
 * by AddAccountModal, TradeRepublicTab and SyncAllModal so the mappings can't
 * drift.
 */
export function formatTrAuthError(err: unknown, t: TFunc): string {
  const status = getErrorStatus(err)

  if (status === 429) return t('sync.tr.errors.tooManyAttempts')

  if (status === 500 || status === 502 || status === 503) {
    const detail = getErrorDetail(err) || ''
    return matchTrDetail(detail, t) ?? t('sync.tr.errors.serverError')
  }

  if (status === 422) {
    const detail = getErrorDetail(err) || ''
    const matched = matchTrDetail(detail, t)
    if (matched) return matched
    const errors =
      (err as { response?: { data?: { errors?: Record<string, unknown> } } })?.response?.data
        ?.errors ?? {}
    if (errors.phoneNumber) return t('sync.tr.errors.phoneNumberRequired')
    if (errors.pin) return t('sync.tr.errors.pinRequired')
    return t('sync.tr.errors.validationFailed')
  }

  return extractErrorMessage(err, t('sync.tr.errors.unknownError'))
}

/**
 * Maps a Fortuneo sync/session error code to a translated message. Shared by
 * FortuneoPanel (dedicated connect/status card) and SyncAllModal's reconnect
 * card so a failed background sync shows the same message in both places.
 */
export function fortuneoErrorMessage(
  t: TFunc,
  code: string | null | undefined
): string | null {
  switch (code) {
    case 'INVALID_CREDENTIALS':
      return t('sync.fortuneo.errors.invalidCredentials')
    case 'INVALID_OTP':
      return t('sync.fortuneo.errors.invalidCode')
    case 'AUTH_ATTEMPT_EXPIRED':
      return t('sync.fortuneo.errors.authAttemptExpired')
    case 'SESSION_EXPIRED':
      return t('sync.fortuneo.errors.sessionExpired')
    case 'INVESTOR_PROFILE_REQUIRED':
      return t('sync.fortuneo.errors.investorProfileRequired')
    case 'PORTFOLIO_INCOMPLETE':
      return t('sync.fortuneo.errors.portfolioIncomplete')
    case 'UPSTREAM_FORMAT_CHANGED':
      return t('sync.fortuneo.errors.formatChanged')
    case 'INVALID_DATA':
      return t('sync.fortuneo.errors.invalidData')
    case 'UPSTREAM_UNAVAILABLE':
    case 'INTERNAL_ERROR':
      return t('sync.fortuneo.errors.serverError')
    default:
      return null
  }
}

/**
 * Maps any Fortuneo auth/sync error to a translated message. Shared by
 * FortuneoPanel and SyncAllModal's reconnect card so the two can't drift.
 *
 * The 429 case matters in practice: `/auth/initiate` and `/auth/complete`
 * draw on the *same* per-IP bucket (5 per 15 min), so a couple of retries
 * can exhaust it and reject the OTP submission before it ever leaves the
 * backend. Without this, that surfaces as a button that appears to do
 * nothing at all.
 */
export function formatFortuneoError(err: unknown, t: TFunc): string {
  if (getErrorStatus(err) === 429) return t('sync.fortuneo.errors.tooManyAttempts')
  return (
    fortuneoErrorMessage(t, getErrorCode(err)) ??
    extractErrorMessage(err, t('sync.fortuneo.errors.serverError'))
  )
}

export function formatApiError(err: unknown, t: TFunc, fallbackKey = 'common.error'): string {
  const status = (err as { response?: { status?: number } })?.response?.status

  if (status === 401) return t('common.errors.unauthorized')
  if (status === 429) return t('common.errors.tooManyRequests')
  if (status && status >= 500) return t('common.errors.serverError')

  const safe = safeBackendMessage(err)
  if (safe) return safe

  if (status === 403) return t('common.errors.forbidden')
  return t(fallbackKey)
}
