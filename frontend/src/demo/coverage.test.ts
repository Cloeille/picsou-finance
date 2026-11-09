import { AxiosHeaders } from 'axios'
import { describe, expect, it, vi } from 'vitest'
import { createDemoAdapter } from './index'
import { mockAccounts } from './data/accounts'
import { mockCategories } from './data/budget'
import { mockGoals } from './data/goals'

const sources = import.meta.glob(['../**/*.{ts,tsx}', '!../**/*.test.{ts,tsx}', '!./**'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

// Calls on the demo-adapted axios instance with a literal path, e.g. api.get<T>(`/goals/${id}`).
const CALL = /\bapi\s*\.(get|post|put|delete|patch)\s*(?:<[^()]*?>)?\s*\(\s*([`'"])(\/[^`'"]*)\2/g

const getTemplates = [...new Set(Object.values(sources).flatMap((source) =>
  [...source.matchAll(CALL)]
    .filter(([, method]) => method === 'get')
    .map(([, , , path]) => path),
))].sort()

// A path parameter takes every id the demo can show, keyed by the segment before it, so a
// handler registered for some accounts but not others still fails.
const SAMPLE_IDS: Record<string, (string | number)[]> = {
  accounts: mockAccounts.map((a) => a.id),
  'real-estate': mockAccounts.map((a) => a.id),
  goals: mockGoals.map((g) => g.id),
  category: mockCategories.map((c) => c.id),
}

function concreteUrls(template: string): string[] {
  return template.split('/').reduce<string[]>((urls, segment, i, segments) => {
    const values = /^\$\{.+\}$/.test(segment) ? SAMPLE_IDS[segments[i - 1]] ?? ['DEMO'] : [segment]
    return urls.flatMap((url) => values.map((value) => (i === 0 ? String(value) : `${url}/${value}`)))
  }, [''])
}

describe('demo handler coverage', () => {
  it('finds the GET calls in the source', () => {
    expect(getTemplates).toContain('/dashboard')
    expect(getTemplates).toContain('/accounts/${id}/holdings')
  })

  it('answers every GET the app makes, for every demo id', async () => {
    const adapter = createDemoAdapter()
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    await Promise.all(getTemplates.flatMap(concreteUrls).map((url) =>
      adapter({ method: 'GET', url, headers: new AxiosHeaders() })))
    const unhandled = warn.mock.calls.map(([message]) => String(message).match(/"([^"]+)"/)?.[1]).sort()
    warn.mockRestore()
    expect(unhandled, `no demo handler for:\n${unhandled.join('\n')}`).toEqual([])
  })
})
