import { describe, it, expect } from 'vitest'
import {
  extractErrorMessage,
  safeBackendMessage,
  formatApiError,
  formatFortuneoError,
  formatTrAuthError,
  fortuneoErrorMessage,
  getErrorCode,
} from './errors'

/** Minimal translator stub: echoes the key so assertions can check which key fired. */
const t = (key: string, fallback?: string) => fallback ?? key

describe('extractErrorMessage', () => {
  it('extracts Spring ProblemDetail detail field', () => {
    const err = { response: { data: { detail: 'CORS origin not allowed' } } }
    expect(extractErrorMessage(err)).toBe('CORS origin not allowed')
  })

  it('extracts message from JSON blob embedded in detail', () => {
    const err = {
      response: {
        data: {
          detail: 'Enable Banking auth failed: {"code":400,"message":"Redirect URI not allowed","error":"REDIRECT_URI_NOT_ALLOWED","detail":null}',
        },
      },
    }
    expect(extractErrorMessage(err)).toBe('Redirect URI not allowed')
  })

  it('extracts response data message field when detail absent', () => {
    const err = { response: { data: { message: 'Invalid credentials' } } }
    expect(extractErrorMessage(err)).toBe('Invalid credentials')
  })

  it('extracts message from JSON string in err.message', () => {
    const err = new Error('{"code":400,"message":"Bad redirect URI","error":"ERR"}')
    expect(extractErrorMessage(err)).toBe('Bad redirect URI')
  })

  it('rejects raw browser network errors', () => {
    const err = new Error('Network error')
    expect(extractErrorMessage(err, 'Custom fallback')).toBe('Custom fallback')
    expect(extractErrorMessage(new Error('AxiosError'))).toBe('Une erreur est survenue')
    expect(extractErrorMessage(new Error('Failed to fetch'))).toBe('Une erreur est survenue')
  })

  it('skips Axios boilerplate and returns fallback', () => {
    const err = new Error('Request failed with status code 400')
    expect(extractErrorMessage(err, 'Custom fallback')).toBe('Custom fallback')
  })

  it('returns provided fallback for unknown error shape', () => {
    expect(extractErrorMessage({}, 'Fallback')).toBe('Fallback')
  })

  it('returns default fallback when no fallback provided', () => {
    expect(extractErrorMessage({})).toBe('Une erreur est survenue')
  })
})

describe('getErrorCode', () => {
  it('extracts a stable ProblemDetail code', () => {
    expect(getErrorCode({ response: { data: { code: 'SESSION_EXPIRED' } } })).toBe('SESSION_EXPIRED')
  })

  it('ignores non-string codes', () => {
    expect(getErrorCode({ response: { data: { code: 42 } } })).toBeUndefined()
  })
})

describe('fortuneoErrorMessage', () => {
  it('explains how to unblock an investor-profile gate', () => {
    expect(fortuneoErrorMessage(t, 'INVESTOR_PROFILE_REQUIRED')).toBe(
      'sync.fortuneo.errors.investorProfileRequired'
    )
  })

  it('returns null for an unknown code so callers can fall back', () => {
    expect(fortuneoErrorMessage(t, 'SOMETHING_NEW')).toBeNull()
    expect(fortuneoErrorMessage(t, null)).toBeNull()
  })
})

describe('formatFortuneoError', () => {
  it('maps rate limiting to the dedicated message', () => {
    expect(formatFortuneoError({ response: { status: 429 } }, t)).toBe(
      'sync.fortuneo.errors.tooManyAttempts'
    )
  })
})

describe('safeBackendMessage — leak guard', () => {
  it('rejects messages that leak a Java exception class', () => {
    const err = { response: { data: { detail: 'org.hibernate.TransientObjectException: ...' } } }
    expect(safeBackendMessage(err)).toBeNull()
  })

  it('rejects messages mentioning a .java source or package', () => {
    expect(safeBackendMessage({ response: { data: { detail: 'NPE at FamilyService.java:123' } } })).toBeNull()
    expect(safeBackendMessage({ response: { data: { detail: 'com.picsou.service.FamilyService failed' } } })).toBeNull()
  })

  it('rejects the axios boilerplate and stack traces', () => {
    expect(safeBackendMessage(new Error('Request failed with status code 500'))).toBeNull()
    expect(safeBackendMessage({ response: { data: { detail: 'see stack trace below' } } })).toBeNull()
  })

  it('accepts a genuine user-facing backend message', () => {
    const err = { response: { data: { detail: 'Cannot delete the last administrator' } } }
    expect(safeBackendMessage(err)).toBe('Cannot delete the last administrator')
  })

  it('returns null when nothing safe is present', () => {
    expect(safeBackendMessage({})).toBeNull()
  })
})

describe('formatApiError — status-aware translated output', () => {
  it('maps 401/429/5xx to generic translated keys, ignoring any backend body', () => {
    expect(formatApiError({ response: { status: 401, data: { detail: 'x' } } }, t)).toBe('common.errors.unauthorized')
    expect(formatApiError({ response: { status: 429 } }, t)).toBe('common.errors.tooManyRequests')
    expect(formatApiError({ response: { status: 503, data: { detail: 'org.foo.Boom' } } }, t)).toBe('common.errors.serverError')
  })

  it('passes through a user-safe 4xx reason (so guard messages survive)', () => {
    const err = { response: { status: 403, data: { detail: 'Cannot delete the last administrator' } } }
    expect(formatApiError(err, t)).toBe('Cannot delete the last administrator')
  })

  it('falls back to the forbidden key on a 403 with no safe message', () => {
    const err = { response: { status: 403, data: { detail: 'AccessDeniedException' } } }
    expect(formatApiError(err, t)).toBe('common.errors.forbidden')
  })

  it('uses the provided fallback key when status/message give nothing safe', () => {
    expect(formatApiError({ response: { status: 400 } }, t, 'auth.error')).toBe('auth.error')
    expect(formatApiError({}, t)).toBe('common.error')
  })

  it('never leaks internals even on a 400', () => {
    const err = { response: { status: 400, data: { detail: 'java.lang.IllegalStateException: boom' } } }
    expect(formatApiError(err, t, 'auth.error')).toBe('auth.error')
  })
})

describe('formatTrAuthError — TR error codes', () => {
  const trErr = (status: number, detail?: string, errors?: Record<string, unknown>) => ({
    response: { status, data: { detail, errors } },
  })

  it('maps TR codes carried by a 422 (SyncException → ProblemDetail)', () => {
    expect(formatTrAuthError(trErr(422, 'PIN_INVALID'), t)).toBe('sync.tr.errors.invalidPin')
    expect(formatTrAuthError(trErr(422, 'NUMBER_INVALID'), t)).toBe('sync.tr.errors.invalidPhoneNumber')
    expect(formatTrAuthError(trErr(422, 'VALIDATION_CODE_INVALID'), t)).toBe('sync.tr.errors.invalidTan')
    expect(formatTrAuthError(trErr(422, 'AUTHENTICATION_ERROR'), t)).toBe('sync.tr.errors.authenticationFailed')
  })

  it('maps the sidecar-down message on a 422', () => {
    const detail = 'Trade Republic authentication service is unavailable. Please make sure tr-auth is running on port 8001.'
    expect(formatTrAuthError(trErr(422, detail), t)).toBe('sync.tr.errors.serviceUnavailable')
  })

  it('maps session-expiry wording on a 422', () => {
    const detail = 'Your Trade Republic session has expired and could not be refreshed. Please reconnect.'
    expect(formatTrAuthError(trErr(422, detail), t)).toBe('sync.tr.errors.authenticationFailed')
  })

  it('keeps the field-validation fallback on a 422 without TR codes', () => {
    expect(formatTrAuthError(trErr(422, undefined, { pin: 'required' }), t)).toBe('sync.tr.errors.pinRequired')
    expect(formatTrAuthError(trErr(422, undefined, { phoneNumber: 'required' }), t)).toBe('sync.tr.errors.phoneNumberRequired')
    expect(formatTrAuthError(trErr(422, 'something else'), t)).toBe('sync.tr.errors.validationFailed')
  })

  it('still maps TR codes on 5xx (proxy/unmapped failures)', () => {
    expect(formatTrAuthError(trErr(502, 'PIN_INVALID'), t)).toBe('sync.tr.errors.invalidPin')
    expect(formatTrAuthError(trErr(500, 'boom'), t)).toBe('sync.tr.errors.serverError')
  })

  it('maps 429 to the rate-limit message', () => {
    expect(formatTrAuthError(trErr(429), t)).toBe('sync.tr.errors.tooManyAttempts')
  })
})
