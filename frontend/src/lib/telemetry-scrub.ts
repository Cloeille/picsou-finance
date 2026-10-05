/**
 * Allowlist scrubber for opt-in telemetry events (issue #200).
 *
 * Pure and framework-free: no Sentry import, no DOM. The rules mirror the backend scrubber
 * (second layer, applied again to every tunneled event), so the two must stay in sync.
 * Anything not explicitly kept is dropped; every kept free-text string goes through
 * {@link scrubString}.
 */

const MAX_TAG_VALUE = 64
const ALLOWED_TAG_KEYS = new Set(['route', 'event', 'feature'])
const ALLOWED_TAG_PREFIX = 'ab.'

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** A path segment that identifies one record: numeric, UUID, long hex, or long base64-ish id. */
function isIdSegment(segment: string): boolean {
  if (!segment) return false
  if (/^\d+$/.test(segment)) return true
  if (UUID_RE.test(segment)) return true
  if (/^[0-9a-f]{12,}$/i.test(segment)) return true
  return /^(?=.*\d)(?=.*[A-Za-z])[A-Za-z0-9_-]{20,}$/.test(segment)
}

function safeDecode(value: string): string {
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

/**
 * Turns a concrete path into a route template: query/fragment stripped, record ids → `:id`.
 * When the router's `params` are known, the matching segments are named after the parameter
 * (`/accounts/123` + `{ id: '123' }` → `/accounts/:id`).
 */
export function templatePath(path: string, params?: Record<string, string | undefined>): string {
  const bare = path.split('#')[0].split('?')[0]
  const byValue = new Map<string, string>()
  for (const [name, value] of Object.entries(params ?? {})) {
    if (name !== '*' && value) byValue.set(value, name)
  }
  const templated = bare
    .split('/')
    .map((segment) => {
      const named = byValue.get(safeDecode(segment))
      if (named) return `:${named}`
      return isIdSegment(segment) ? ':id' : segment
    })
    .join('/')
  return templated || '/'
}

/** Drops the origin and query/fragment of a URL-ish string, then templates its path. */
function templateUrlLike(raw: string): string {
  const trailing = /[.,;:!?)\]}'"]+$/.exec(raw)?.[0] ?? ''
  const core = trailing ? raw.slice(0, raw.length - trailing.length) : raw
  const withoutOrigin = core.replace(/^https?:\/\/[^/?#]*/i, '')
  return templatePath(withoutOrigin || '/') + trailing
}

const URL_RE = /\bhttps?:\/\/[^\s"'<>`]+/gi
const REL_PATH_RE = /(?<![\w:/.])\/(?:[\w.~%@+-]+\/?)+(?:\?[^\s"'<>`]*)?(?:#[^\s"'<>`]*)?/g
const BEARER_RE = /\bBearer\s+[A-Za-z0-9._~+/=-]+/gi
const JWT_RE = /\beyJ[\w-]+\.[\w-]+\.[\w-]*/g
const SECRET_KV_RE =
  /\b(api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|authorization|key)(["']?\s*[=:]\s*["']?)(?!\[)[^\s&,;"'}]+/gi
const EMAIL_RE = /[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}/g
const IBAN_RE = /\b[A-Z]{2}\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,3})?\b/g
const CARD_RE = /\b\d(?:[ -]?\d){12,18}\b/g
const IPV4_RE = /(?<![\d.])\d{1,3}(?:\.\d{1,3}){3}(?![\d.])/g

const NUM = String.raw`(?:\d{1,3}(?:[ \u00a0\u202f.,]\d{3})*(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)`
const AMOUNT_RE = new RegExp(
  [
    String.raw`(?:€|\$|£|\b(?:EUR|USD|GBP|CHF)\b)\s?${NUM}`,
    String.raw`(?<![\w.,])${NUM}\s?(?:€|\$|£|(?:EUR|USD|GBP|CHF|[Ee]uros?|[Dd]ollars?)(?![A-Za-z]))`,
    String.raw`(?<![\w.,])\d+(?:[ \u00a0\u202f]\d{3})*[.,]\d{2}(?!\d|[.,]\d)`,
  ].join('|'),
  'g',
)
const LONG_NUMBER_RE = /\b\d{4,}\b/g

/** Removes anything that looks like personal or financial data from a free-text string. */
export function scrubString(input: string): string {
  if (typeof input !== 'string') return ''
  return input
    .replace(URL_RE, templateUrlLike)
    .replace(REL_PATH_RE, templateUrlLike)
    .replace(BEARER_RE, '[token]')
    .replace(JWT_RE, '[token]')
    .replace(SECRET_KV_RE, (_m, name: string, sep: string) => `${name}${sep}[token]`)
    .replace(EMAIL_RE, '[email]')
    .replace(IBAN_RE, '[iban]')
    .replace(CARD_RE, '[card]')
    .replace(IPV4_RE, '[ip]')
    .replace(AMOUNT_RE, '[amount]')
    .replace(LONG_NUMBER_RE, '[n]')
}

type Dict = Record<string, unknown>

function isDict(value: unknown): value is Dict {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function keepString(value: unknown, max = 200): string | undefined {
  return typeof value === 'string' ? value.slice(0, max) : undefined
}

function scrubFrame(frame: unknown): Dict | null {
  if (!isDict(frame)) return null
  const out: Dict = {}
  if (typeof frame.filename === 'string') out.filename = scrubString(frame.filename)
  if (typeof frame.function === 'string') out.function = scrubString(frame.function)
  if (typeof frame.module === 'string') out.module = scrubString(frame.module)
  if (typeof frame.lineno === 'number') out.lineno = frame.lineno
  if (typeof frame.colno === 'number') out.colno = frame.colno
  if (typeof frame.in_app === 'boolean') out.in_app = frame.in_app
  return out
}

function scrubExceptionValue(value: unknown): Dict | null {
  if (!isDict(value)) return null
  const out: Dict = {}
  if (typeof value.type === 'string') out.type = scrubString(value.type)
  if (typeof value.value === 'string') out.value = scrubString(value.value)
  if (isDict(value.mechanism)) {
    const mechanism: Dict = {}
    if (typeof value.mechanism.type === 'string') mechanism.type = value.mechanism.type.slice(0, 64)
    if (typeof value.mechanism.handled === 'boolean') mechanism.handled = value.mechanism.handled
    out.mechanism = mechanism
  }
  if (isDict(value.stacktrace) && Array.isArray(value.stacktrace.frames)) {
    out.stacktrace = {
      frames: value.stacktrace.frames.map(scrubFrame).filter((f): f is Dict => f !== null),
    }
  }
  return out
}

function scrubTags(tags: unknown): Dict | undefined {
  const entries: [string, unknown][] = Array.isArray(tags)
    ? tags.filter((t): t is [string, unknown] => Array.isArray(t) && typeof t[0] === 'string')
    : isDict(tags)
      ? Object.entries(tags)
      : []
  const out: Dict = {}
  for (const [name, raw] of entries) {
    if (!ALLOWED_TAG_KEYS.has(name) && !name.startsWith(ALLOWED_TAG_PREFIX)) continue
    if (raw === null || raw === undefined || typeof raw === 'object') continue
    out[name] = scrubString(String(raw)).slice(0, MAX_TAG_VALUE)
  }
  return Object.keys(out).length > 0 ? out : undefined
}

function scrubContexts(contexts: unknown): Dict | undefined {
  if (!isDict(contexts)) return undefined
  const out: Dict = {}
  if (isDict(contexts.os) && typeof contexts.os.name === 'string') {
    out.os = { name: contexts.os.name.slice(0, 64) }
  }
  if (isDict(contexts.browser) && typeof contexts.browser.name === 'string') {
    out.browser = { name: contexts.browser.name.slice(0, 64) }
  }
  if (isDict(contexts.runtime)) {
    const runtime: Dict = {}
    const name = keepString(contexts.runtime.name, 64)
    const version = keepString(contexts.runtime.version, 32)
    if (name !== undefined) runtime.name = name
    if (version !== undefined) runtime.version = version
    if (Object.keys(runtime).length > 0) out.runtime = runtime
  }
  return Object.keys(out).length > 0 ? out : undefined
}

function scrubMessage(message: unknown): string | undefined {
  if (typeof message === 'string') return scrubString(message)
  if (isDict(message)) {
    const text = message.formatted ?? message.message
    if (typeof text === 'string') return scrubString(text)
  }
  return undefined
}

/**
 * Applies the allowlist to a Sentry-shaped event: keeps only the contract fields and scrubs every
 * string. `request`, `user`, `breadcrumbs`, `extra`, `server_name`, `modules`, `debug_meta`,
 * frame `vars`/context lines and the like are never copied.
 */
export function scrubEvent<T extends object>(event: T): T {
  const src = event as Dict
  const out: Dict = {}

  for (const field of ['event_id', 'platform', 'level', 'logger', 'release', 'environment'] as const) {
    const value = keepString(src[field])
    if (value !== undefined) out[field] = value
  }
  if (typeof src.timestamp === 'number' || typeof src.timestamp === 'string') {
    out.timestamp = src.timestamp
  }

  const message = scrubMessage(src.message ?? src.logentry)
  if (message !== undefined) out.message = message

  if (isDict(src.exception) && Array.isArray(src.exception.values)) {
    out.exception = {
      values: src.exception.values.map(scrubExceptionValue).filter((v): v is Dict => v !== null),
    }
  }

  const tags = scrubTags(src.tags)
  if (tags) out.tags = tags

  const contexts = scrubContexts(src.contexts)
  if (contexts) out.contexts = contexts

  if (Array.isArray(src.fingerprint)) {
    out.fingerprint = src.fingerprint
      .filter((f): f is string => typeof f === 'string')
      .map((f) => scrubString(f))
  }

  if (isDict(src.sdk)) {
    const sdk: Dict = {}
    const name = keepString(src.sdk.name, 64)
    const version = keepString(src.sdk.version, 32)
    if (name !== undefined) sdk.name = name
    if (version !== undefined) sdk.version = version
    out.sdk = sdk
  }

  if (typeof src.transaction === 'string' && src.transaction.startsWith('/')) {
    out.transaction = templatePath(src.transaction)
  }

  return out as T
}
