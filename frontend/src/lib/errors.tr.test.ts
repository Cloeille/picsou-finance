import { describe, expect, it } from 'vitest'
import { formatTrAuthError, isTrAttemptOverError, isTrSessionDeadError } from './errors'

const t = (key: string) => key
const coded = (code: string, status = 422) => ({ response: { status, data: { code } } })

describe('formatTrAuthError with a sidecar error code', () => {
  it.each([
    ['APP_VALIDATION_TIMEOUT', 'sync.tr.errors.appValidationTimeout'],
    ['APP_VALIDATION_CANCELLED', 'sync.tr.errors.appValidationCancelled'],
    ['AUTH_ATTEMPT_EXPIRED', 'sync.tr.errors.authAttemptExpired'],
    ['INVALID_CREDENTIALS', 'sync.tr.errors.invalidCredentials'],
    ['INVALID_OTP', 'sync.tr.errors.invalidTan'],
    ['RATE_LIMITED', 'sync.tr.errors.tooManyAttempts'],
    ['SESSION_EXPIRED', 'sync.tr.errors.sessionExpired'],
    ['UPSTREAM_UNAVAILABLE', 'sync.tr.errors.serverError'],
  ])('maps %s', (code, message) => {
    expect(formatTrAuthError(coded(code), t)).toBe(message)
  })

  it('lets the code win over the HTTP status', () => {
    expect(formatTrAuthError(coded('APP_VALIDATION_TIMEOUT', 408), t)).toBe('sync.tr.errors.appValidationTimeout')
  })

  it('still reads the legacy detail strings when there is no code', () => {
    expect(
      formatTrAuthError({ response: { status: 422, data: { detail: 'PIN_INVALID' } } }, t),
    ).toBe('sync.tr.errors.invalidPin')
  })
})

describe('Trade Republic attempt classification', () => {
  it('treats a mistyped code as retryable and a refused app confirmation as over', () => {
    expect(isTrAttemptOverError(coded('INVALID_OTP'))).toBe(false)
    expect(isTrAttemptOverError(coded('APP_VALIDATION_CANCELLED'))).toBe(true)
  })

  it('reads SESSION_EXPIRED as a dead session', () => {
    expect(isTrSessionDeadError(coded('SESSION_EXPIRED'))).toBe(true)
  })
})
