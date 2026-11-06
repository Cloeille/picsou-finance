import { formatApiError, getErrorCode, getErrorStatus } from '@/lib/errors'

export const SSO_ERROR_CODES = [
  'NOT_LINKED',
  'NOT_IN_ADMIN_GROUP',
  'IDENTITY_IN_USE',
  'REAUTH_REQUIRED',
  'REAUTH_TOO_OLD',
  'REAUTH_WRONG_ACCOUNT',
  'ACCOUNT_INACTIVE',
  'ADMIN_RECOVERY_ACTIVE',
  'ACTIVATION_INVALID',
  'SETUP_ALREADY_CLAIMED',
  'IDP_UNAVAILABLE',
  'IDP_ERROR',
  'SESSION_EXPIRED',
  'RATE_LIMITED',
  'SSO_ADMIN_REQUIRED',
  'IDENTITY_NOT_USED_YET',
  'LAST_SIGN_IN_METHOD',
  'PASSWORD_ALREADY_SET',
  'REAUTH_FAILED',
  'SSO_REVALIDATE',
  'SSO_DISABLED',
  'SELF_UNLINK_FORBIDDEN',
] as const

export type SsoErrorCode = (typeof SSO_ERROR_CODES)[number]

type TFunc = (key: string) => string

function isSsoErrorCode(code: string | null | undefined): code is SsoErrorCode {
  return SSO_ERROR_CODES.some(known => known === code)
}

export function ssoErrorMessage(t: TFunc, code: string | null | undefined): string {
  return t(isSsoErrorCode(code) ? `ssoErrors.${code}` : 'ssoErrors.generic')
}

export function formatSsoApiError(err: unknown, t: TFunc, fallbackKey?: string): string {
  const code = getErrorCode(err)
  if (isSsoErrorCode(code)) return ssoErrorMessage(t, code)
  if (getErrorStatus(err) === 429) return t('common.errors.tooManyRequests')
  return formatApiError(err, t, fallbackKey)
}
