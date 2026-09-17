import { describe, it, expect } from 'vitest'

/**
 * A lexical guard against the next amount that forgets to be maskable.
 *
 * Privacy mode is only worth having if it is total: one figure left legible during a demo defeats
 * it. Deleting `formatCurrency` from `lib/utils.ts` makes the common mistake a compile error, but
 * two ways round it stay open — building an `Intl` currency format by hand, or printing a raw
 * number straight into JSX — and both are exactly how the two bypasses this feature had to clean
 * up (`formatCompactEur`, `formatCompact`) came into being.
 *
 * What it cannot catch, and what the rendering tests are for instead:
 * - an amount printed as `{String(x)}` or `{x.toFixed(2)}`, or through a variable whose name the
 *   heuristic below does not match (`{row.v}`, `{total}`);
 * - anything in `components/ui/**`, vendored shadcn, excluded by policy;
 * - an amount reaching the DOM through `aria-label`, `title` or a `t()` interpolation computed
 *   elsewhere;
 * - data leaving the app — the GDPR export is a deliberate act of reading;
 * - anything at all about re-rendering. This file is pure text matching. Whether a toggle
 *   actually redraws a chart axis is asserted in `money-axis.test.tsx`.
 */
const sources = import.meta.glob('../**/*.{ts,tsx}', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

/** Vendored shadcn, the locale bundles, and this file's own regexes. */
const EXCLUDED = [
  /\/components\/ui\//,
  /\/i18n\/locales\//,
  /money-guard\.test\.ts$/,
]

/** Vite keys the glob relative to this file, which sits in `src/lib`. */
function normalise(path: string): string {
  return path.startsWith('./') ? `src/lib/${path.slice(2)}` : path.replace('../', 'src/')
}

/** Comments describe the rules; they must not trip them. */
function stripComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
}

function files(predicate: (source: string, path: string) => boolean): string[] {
  return Object.entries(sources)
    .filter(([path]) => !EXCLUDED.some((pattern) => pattern.test(path)))
    .filter(([path, source]) => predicate(stripComments(source), normalise(path)))
    .map(([path]) => normalise(path))
}

describe('nothing bypasses the masked formatter', () => {
  it('keeps the raw formatter to its audited callers', () => {
    // The live total in the transaction form is the one place a member is typing the number
    // themselves, so masking the product of two visible operands would protect nothing.
    const allowed = ['src/lib/money.ts', 'src/lib/money.test.ts', 'src/components/shared/AddTransactionModal.tsx']
    const offenders = files((s) => s.includes('formatCurrencyUnmasked')).filter((f) => !allowed.includes(f))

    expect(offenders, 'use useMoney() instead of the raw formatter').toEqual([])
  })

  it('lets nobody build their own currency format', () => {
    // The highest-value rule of the five: a compact axis label is a legitimate need, and reaching
    // for Intl to satisfy it is what produced two silent leaks. money.ts answers it with
    // `compact()` instead.
    const offenders = files((s) => /style:\s*['"]currency['"]/.test(s))
      .filter((f) => f !== 'src/lib/money.ts')

    expect(offenders, 'use money.amount() or money.compact()').toEqual([])
  })

  it('lets nobody spell out a currency symbol in JSX', () => {
    const allowed = [
      // Two static input adornments, not amounts: the € sits beside the field, never in it.
      'src/pages/goals/GoalCalendarPage.tsx',
    ]
    const offenders = files((s, path) => path.endsWith('.tsx') && s.includes('€'))
      .filter((f) => !allowed.includes(f))

    expect(offenders, 'the symbol comes from the formatter, with the locale that places it').toEqual([])
  })

  it('makes every amount axis go through the formatter', () => {
    const allowed = [
      // Its axis is a share of the portfolio, in percent — there is no magnitude to hide.
      'src/pages/analysis/AllocationTrajectory.tsx',
    ]
    const offenders = files((s) =>
      s.includes('<YAxis') && s.includes('tickFormatter') && !s.includes('useMoney'),
    ).filter((f) => !allowed.includes(f))

    expect(offenders, 'wrap the tick ladder in money.tick()').toEqual([])
  })

  it('lets no amount-shaped value be printed straight into JSX', () => {
    // A naming heuristic, and only that. It exists for the one class the rules above cannot see:
    // `{account.currentBalance}` imports nothing and formats nothing, so it leaked in plain sight
    // for as long as it existed.
    const bare = /\{\s*[\w$]+(?:\.[\w$]+)*(?:Balance|Amount|Eur|Price|Cost|Proceeds|Quantity|BuyIn)[\w$.]*\s*\}/
    const offenders = files((s, path) => {
      if (!path.endsWith('.tsx')) return false
      // Import lists are not renders: `import { parseAmount }` is not an amount on screen.
      return s.split('\n').some((line) => {
        const code = line.trim()
        if (code.startsWith('import') || code.startsWith('export')) return false
        return !code.includes('={') && bare.test(code)
      })
    })

    expect(offenders, 'render it through CurrencyDisplay or money.amount()').toEqual([])
  })
})
