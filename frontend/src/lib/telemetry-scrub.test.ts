import { describe, expect, it } from 'vitest'
import { scrubEvent, scrubString, templatePath } from './telemetry-scrub'

const IBAN = 'FR7630006000011234567890189'
const EMAIL = 'jane.doe@example.com'
const JWT = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJl'

describe('scrubString', () => {
  it.each([
    [`Invalid IBAN ${IBAN}`, '[iban]', IBAN],
    ['Spaced FR76 3000 6000 0112 3456 7890 189 rejected', '[iban]', '3000 6000'],
    ['Balance is 1 234,56 € now', '[amount]', '1 234,56'],
    ['Paid €12.50 today', '[amount]', '12.50'],
    ['Charged 12.50 EUR', '[amount]', '12.50'],
    [`Mail ${EMAIL} failed`, '[email]', EMAIL],
    ['Authorization: Bearer abc.def-123_xyz', '[token]', 'abc.def-123'],
    [`Token ${JWT} expired`, '[token]', 'eyJhbGci'],
    ['login failed password=hunter2 for user', '[token]', 'hunter2'],
    ['GET /x?token=s3cr3t&a=1 failed', '/x failed', 's3cr3t'],
    ['card 4111 1111 1111 1111 declined', '[card]', '4111'],
    ['ref 987654 not found', '[n]', '987654'],
    ['upstream 192.168.1.20 refused', '[ip]', '192.168'],
    ['owes 1500 euros', '[amount]', '1500'],
    ['header Authorization: abc123secret sent', '[token]', 'abc123secret'],
  ])('%s', (input, marker, leaked) => {
    const out = scrubString(input)
    expect(out).toContain(marker)
    expect(out).not.toContain(leaked)
  })

  it('templates and strips URLs', () => {
    const out = scrubString('fetch https://picsou.local/api/accounts/123/transactions/9f1c2d3e-aaaa-bbbb-cccc-1234567890ab?x=1#frag failed')
    expect(out).toContain('/api/accounts/:id/transactions/:id')
    expect(out).not.toContain('x=1')
    expect(out).not.toContain('frag')
  })

  it('leaves harmless text alone', () => {
    expect(scrubString('Cannot read properties of undefined (reading map)')).toBe(
      'Cannot read properties of undefined (reading map)',
    )
  })
})

describe('templatePath', () => {
  it('templates ids and drops the query', () => {
    expect(templatePath('/accounts/123?x=1')).toBe('/accounts/:id')
    expect(templatePath('/goals/42/calendar#top')).toBe('/goals/:id/calendar')
    expect(templatePath('/')).toBe('/')
  })

  it('uses router param names when known', () => {
    expect(templatePath('/budget/spending/groceries', { categoryId: 'groceries' })).toBe(
      '/budget/spending/:categoryId',
    )
  })
})

describe('scrubEvent', () => {
  const raw = {
    event_id: 'abc123',
    timestamp: 1700000000,
    platform: 'javascript',
    level: 'error',
    release: '1.1.0',
    environment: 'production',
    server_name: 'host.local',
    message: 'Key (name)=(Livret A Jean Dupont) already exists',
    request: { url: 'https://x/accounts/1?token=abc', headers: { Cookie: 'sid=1' } },
    user: { id: '1', email: EMAIL, ip_address: '1.2.3.4' },
    breadcrumbs: [{ message: IBAN }],
    extra: { iban: IBAN },
    modules: { a: '1' },
    debug_meta: { images: [] },
    tags: { route: '/accounts/123', feature: 'sync_triggered', user: EMAIL, 'ab.variant': `v-${IBAN}` },
    contexts: {
      os: { name: 'Linux', version: '6.1', kernel_version: 'x' },
      browser: { name: 'Firefox', version: '130' },
      runtime: { name: 'browser', version: '130' },
      device: { model: 'Pixel' },
    },
    fingerprint: ['page_view', '/accounts/:id'],
    exception: {
      values: [
        {
          type: 'Error',
          module: 'com.picsou.errors',
          value: 'Key (name)=(Livret A Jean Dupont) already exists',
          mechanism: { type: 'onerror', handled: false, data: { secret: 'x' } },
          stacktrace: {
            frames: [
              {
                filename: 'https://picsou.local/assets/accounts/123/index-abc.js?token=zzz',
                function: 'run',
                module: 'm',
                lineno: 10,
                colno: 4,
                in_app: true,
                vars: { iban: IBAN },
                context_line: `const iban = "${IBAN}"`,
                pre_context: ['a'],
              },
            ],
          },
        },
      ],
    },
  }

  const out = scrubEvent(raw) as Record<string, unknown>
  const json = JSON.stringify(out)

  it('drops everything outside the allowlist', () => {
    for (const k of ['request', 'user', 'breadcrumbs', 'extra', 'server_name', 'modules', 'debug_meta']) {
      expect(out).not.toHaveProperty(k)
    }
    expect(json).not.toContain('vars')
    expect(json).not.toContain('context_line')
    expect(json).not.toContain('Pixel')
    expect(json).not.toContain('kernel_version')
  })

  it('contains no sensitive literal anywhere', () => {
    for (const leaked of [IBAN, EMAIL, '1 234,56', 'abc.def', 'eyJhbGci', 'token=zzz', 'Livret A Jean Dupont']) {
      expect(json).not.toContain(leaked)
    }
  })

  it('keeps the useful, scrubbed fields', () => {
    expect(out.event_id).toBe('abc123')
    expect(out.level).toBe('error')
    expect(out.release).toBe('1.1.0')
    expect(out).not.toHaveProperty('message')
    expect(out.tags).toEqual({ route: '/accounts/:id', feature: 'sync_triggered', 'ab.variant': 'v-[iban]' })
    expect(out.contexts).toEqual({
      os: { name: 'Linux' },
      browser: { name: 'Firefox' },
      runtime: { name: 'browser', version: '130' },
    })
    expect(out.fingerprint).toEqual(['page_view', '/accounts/:id'])
    const ex = (out.exception as { values: Record<string, unknown>[] }).values[0]
    expect(ex).not.toHaveProperty('value')
    expect(ex).toMatchObject({ type: 'Error', module: 'com.picsou.errors' })
    expect(ex.mechanism).toEqual({ type: 'onerror', handled: false })
    const frame = (ex.stacktrace as { frames: Record<string, unknown>[] }).frames[0]
    expect(frame.filename).toBe('/assets/accounts/:id/index-abc.js')
    expect(frame).toMatchObject({ function: 'run', module: 'm', lineno: 10, colno: 4, in_app: true })
  })

  it('caps tag values at 64 chars and drops non-allowlisted tags', () => {
    const e = scrubEvent({ tags: { feature: 'a'.repeat(100), other: 'x' } }) as { tags: Record<string, string> }
    expect(e.tags.feature).toHaveLength(64)
    expect(e.tags).not.toHaveProperty('other')
  })

  it('keeps only fixed usage messages and drops free-text message and logentry', () => {
    expect(scrubEvent({ message: 'page_view' })).toHaveProperty('message', 'page_view')
    expect(scrubEvent({ message: 'feature_used' })).toHaveProperty('message', 'feature_used')
    expect(scrubEvent({ message: 'Key (name)=(Livret A Jean Dupont) already exists' }))
      .not.toHaveProperty('message')
    expect(scrubEvent({ logentry: { formatted: 'Jean Dupont opened Livret A' } }))
      .not.toHaveProperty('message')
  })
})
