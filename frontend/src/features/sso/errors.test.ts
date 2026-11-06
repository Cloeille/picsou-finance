import { describe, expect, it } from 'vitest'
import de from '@/i18n/locales/de.json'
import en from '@/i18n/locales/en.json'
import es from '@/i18n/locales/es.json'
import fr from '@/i18n/locales/fr.json'
import { SSO_ERROR_CODES, formatSsoApiError, ssoErrorMessage } from './errors'

const locales = { en, fr, de, es } as Record<string, { ssoErrors?: Record<string, unknown> }>
const t = (key: string) => key

describe('ssoErrors locale entries', () => {
  for (const [name, locale] of Object.entries(locales)) {
    it(`${name} has a non-empty message for every code and generic`, () => {
      for (const code of [...SSO_ERROR_CODES, 'generic']) {
        const message = locale.ssoErrors?.[code]
        expect(typeof message === 'string' && message.trim().length > 0, `${name}.ssoErrors.${code}`).toBe(true)
      }
    })
  }
})

describe('ssoErrorMessage', () => {
  it('maps a listed code to its key', () => {
    expect(ssoErrorMessage(t, 'NOT_LINKED')).toBe('ssoErrors.NOT_LINKED')
  })

  it('maps an unknown or missing code to generic', () => {
    expect(ssoErrorMessage(t, 'NOPE')).toBe('ssoErrors.generic')
    expect(ssoErrorMessage(t, undefined)).toBe('ssoErrors.generic')
  })
})

describe('formatSsoApiError', () => {
  const apiError = (status: number, code?: string) => ({ response: { status, data: { code } } })

  it('translates a listed API code', () => {
    expect(formatSsoApiError(apiError(409, 'LAST_SIGN_IN_METHOD'), t)).toBe('ssoErrors.LAST_SIGN_IN_METHOD')
  })

  it('translates the refusal to unlink your own identities from the member list', () => {
    expect(formatSsoApiError(apiError(403, 'SELF_UNLINK_FORBIDDEN'), t)).toBe('ssoErrors.SELF_UNLINK_FORBIDDEN')
  })

  it('uses the rate-limit message for 429', () => {
    expect(formatSsoApiError(apiError(429), t)).toBe('common.errors.tooManyRequests')
  })

  it('falls back to the generic API formatter', () => {
    expect(formatSsoApiError(apiError(500), t)).toBe('common.errors.serverError')
  })

  it('falls back to the caller key when the API gives no usable reason', () => {
    expect(formatSsoApiError(apiError(400), t, 'setup.admin.submitError')).toBe('setup.admin.submitError')
  })
})
