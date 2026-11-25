import { getErrorCode, getErrorDetail, getErrorStatus } from '@/lib/errors'
import type { SidecarProviderId } from '@/features/sync/providers'

export const PICSOU_NEW_ISSUE_URL = 'https://github.com/Cloeille/picsou-finance/issues/new'

/**
 * Canonical connector slugs allowed in a broken-connector GitHub issue report.
 * Only static connector identifiers are permitted — never an institution name,
 * account name, IBAN, or user-supplied text.
 */
export const BANK_CONNECTOR_IDS = [
  'enable-banking',
  'amex',
  'amundi',
  'banque-populaire',
  'bnp',
  'bourse-direct',
  'bourso',
  'caisse-epargne',
  'cic',
  'cmes',
  'corum',
  'degiro',
  'fortuneo',
  'lbp',
  'natixis',
  'revolut',
  'sofidy',
  'trade-republic',
] as const

export type BankConnectorId = (typeof BANK_CONNECTOR_IDS)[number]

const BANK_CONNECTOR_ID_SET: ReadonlySet<string> = new Set(BANK_CONNECTOR_IDS)

/** Maps frontend `sidecarProviders` keys to canonical connector report slugs. */
export const SIDECAR_PROVIDER_TO_CONNECTOR_ID: Record<SidecarProviderId, BankConnectorId> = {
  amex: 'amex',
  amundi: 'amundi',
  banquePopulaire: 'banque-populaire',
  bnp: 'bnp',
  bourseDirect: 'bourse-direct',
  bourso: 'bourso',
  caisseEpargne: 'caisse-epargne',
  cic: 'cic',
  cmes: 'cmes',
  corum: 'corum',
  fortuneo: 'fortuneo',
  lbp: 'lbp',
  natixis: 'natixis',
  sofidy: 'sofidy',
}

/**
 * Strict allowlist of typed connector error codes (`SidecarErrorCode` plus the
 * typed Revolut / Enable Banking codes already exposed to the frontend). Any
 * value outside this set is replaced by `UNKNOWN` so free-text bank messages or
 * personal data can never enter the report URL.
 */
export const KNOWN_CONNECTOR_ERROR_CODES: ReadonlySet<string> = new Set([
  'INVALID_CREDENTIALS',
  'INVALID_OTP',
  'MFA_INVALID',
  'AUTH_ATTEMPT_EXPIRED',
  'SESSION_EXPIRED',
  'APP_VALIDATION_TIMEOUT',
  'APP_VALIDATION_CANCELLED',
  'SAFEKEY_TIMEOUT',
  'CAPTCHA_BLOCKED',
  'FRAUD_ACK_REQUIRED',
  'IDENTITY_SELECTION_UNSUPPORTED',
  'MFA_TYPE_UNSUPPORTED',
  'ACTION_REQUIRED',
  'INVESTOR_PROFILE_REQUIRED',
  'FIRST_VISIT_PENDING',
  'EMAIL_UNREACHABLE',
  'ACCOUNT_INACTIVE',
  'RATE_LIMITED',
  'MULTIPLE_CONTRACTS',
  'KEYPAD_CHANGED',
  'KEYPAD_EXPIRED',
  'INVALID_POSITIONS',
  'INVALID_SESSION_STATE',
  'PORTFOLIO_INCOMPLETE',
  'UPSTREAM_FORMAT_CHANGED',
  'UPSTREAM_UNAVAILABLE',
  'INVALID_DATA',
  'INTERNAL_ERROR',
  'HUMAN_VERIFICATION_REQUIRED',
  'ENABLE_BANKING_NOT_CONFIGURED',
  'SYNC_FAILED',
  'UNKNOWN',
])

export type ConnectorRuntime = 'desktop' | 'web'

const SAFE_VERSION_RE = /^[0-9A-Za-z._+-]{1,32}$/

/** Returns the code if it is in the typed allowlist, otherwise `'UNKNOWN'`. */
export function sanitizeConnectorErrorCode(code?: string | null): string {
  if (typeof code === 'string' && KNOWN_CONNECTOR_ERROR_CODES.has(code)) {
    return code
  }
  return 'UNKNOWN'
}

/**
 * Extracts a typed, allowlisted error code from an Axios/API failure, never
 * returning free-text error messages or bank payloads.
 */
export function extractTypedErrorCode(err: unknown): string {
  const code = getErrorCode(err)
  if (code && KNOWN_CONNECTOR_ERROR_CODES.has(code)) return code
  if (getErrorStatus(err) === 429) return 'RATE_LIMITED'
  const detail = getErrorDetail(err)
  if (detail && KNOWN_CONNECTOR_ERROR_CODES.has(detail)) return detail
  return 'UNKNOWN'
}

function sanitizeAppVersion(version?: string | null): string {
  const trimmed = typeof version === 'string' ? version.trim() : ''
  return SAFE_VERSION_RE.test(trimmed) ? trimmed : 'unknown'
}

export interface ConnectorReportInput {
  connectorId: BankConnectorId
  errorCode?: string | null
  appVersion?: string | null
  runtime: ConnectorRuntime
}

export interface ConnectorReport {
  connectorId: string
  errorCode: string
  appVersion: string
  runtime: ConnectorRuntime
  title: string
  body: string
  url: string
}

/**
 * Builds the exact prefilled GitHub issue title, body, and URL for a broken
 * bank connector using ONLY the four allowlisted technical fields.
 */
export function buildConnectorReport(input: ConnectorReportInput): ConnectorReport {
  const connectorId = BANK_CONNECTOR_ID_SET.has(input.connectorId) ? input.connectorId : 'unknown'
  const errorCode = sanitizeConnectorErrorCode(input.errorCode)
  const appVersion = sanitizeAppVersion(input.appVersion)
  const runtime: ConnectorRuntime = input.runtime === 'desktop' ? 'desktop' : 'web'

  const title = `Broken bank connector: ${connectorId} (${errorCode})`
  const body = [
    `- Connector: ${connectorId}`,
    `- Error code: ${errorCode}`,
    `- App version: ${appVersion}`,
    `- Runtime: ${runtime}`,
  ].join('\n')

  const params = new URLSearchParams({ title, body })
  const url = `${PICSOU_NEW_ISSUE_URL}?${params.toString()}`

  return { connectorId, errorCode, appVersion, runtime, title, body, url }
}
