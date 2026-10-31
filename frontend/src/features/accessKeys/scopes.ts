/**
 * The access-key scope vocabulary, mirroring the backend allowlist
 * `com.picsou.mcp.Scopes#ALL`. The UI must offer exactly these — a scope the
 * backend doesn't recognise would make key creation fail with HTTP 400.
 *
 * Most read scopes end in `:read`; `budget:*` reads end in `-read` instead
 * (e.g. `budget:categories-read`), and the two `oauth2:*` introspection
 * scopes carry no read suffix at all — see `scopeGroup()`.
 */
export const ALL_SCOPES = [
  'accounts:read',
  'transactions:read',
  'goals:read',
  'dashboard:read',
  'prices:read',
  'family:read',
  'sync:read',
  'analysis:read',
  'budget:categories-read',
  'budget:rules-read',
  'budget:transactions-read',
  'budget:recurring-read',
  'budget:envelopes-read',
  'budget:dashboard-read',
  'oauth2:discover',
  'oauth2:session-status',
  'accounts:write',
  'transactions:write',
  'goals:write',
  'sync:trigger',
  'budget:categories-write',
  'budget:rules-write',
  'budget:transactions-write',
  'budget:recurring-write',
  'budget:envelopes-write',
] as const

export type Scope = (typeof ALL_SCOPES)[number]

export type ScopeGroup = 'read' | 'write'

/** Read-only scopes that carry neither a `:read` nor a `-read` suffix. */
const READ_ONLY_OVERRIDES = new Set<string>(['oauth2:discover', 'oauth2:session-status'])

/**
 * Classifies a scope: `:read`/`-read` suffix, or a listed read-only override, → read;
 * everything else (`:write` / `:trigger`) → write.
 */
export function scopeGroup(scope: string): ScopeGroup {
  return scope.endsWith(':read') || scope.endsWith('-read') || READ_ONLY_OVERRIDES.has(scope)
    ? 'read'
    : 'write'
}

/** i18n-safe key for a scope: `accounts:read` → `accounts_read`. */
export function scopeI18nKey(scope: string): string {
  return scope.replace(':', '_')
}

export const READ_SCOPES: Scope[] = ALL_SCOPES.filter((s) => scopeGroup(s) === 'read')
export const WRITE_SCOPES: Scope[] = ALL_SCOPES.filter((s) => scopeGroup(s) === 'write')
