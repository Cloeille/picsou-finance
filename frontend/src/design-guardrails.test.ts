import { describe, it, expect } from 'vitest'
import indexCss from './index.css?raw'
import fr from './i18n/locales/fr.json'
import { WHATS_NEW_RELEASES } from './lib/whats-new'

const allTsxRaw = import.meta.glob<string>('./**/*.tsx', {
  query: '?raw',
  import: 'default',
  eager: true,
})

const tsxEntries = Object.entries(allTsxRaw)
  .filter(([file]) => !file.includes('.test.'))
  .map(([file, content]) => [file.replace(/^\.\//, ''), content] as const)

function sourceOf(relPath: string): string {
  const found = allTsxRaw[`./${relPath}`]
  if (typeof found !== 'string') {
    throw new Error(`Expected source file ./${relPath} to exist`)
  }
  return found
}

/**
 * Blocking UI & copy guardrails enforced in CI (`make test-frontend`).
 * Every rule below mirrors CONTRIBUTING.md ("UI, Design & Copy Standards") and DESIGN.md
 * so pull requests cannot silently regress typography, native OS widgets, modal layouts,
 * z-index stacking, color restraint, or French copy standards.
 */
describe('UI & design system guardrails (see CONTRIBUTING.md & DESIGN.md)', () => {
  it('keeps Plus Jakarta Sans as the single app font in index.css', () => {
    expect(indexCss, 'Picsou uses Plus Jakarta Sans exclusively (see DESIGN.md §3)').toContain(
      '@fontsource-variable/plus-jakarta-sans',
    )
    expect(indexCss, '--font-sans must stay set to Plus Jakarta Sans Variable').toContain(
      "'Plus Jakarta Sans Variable', sans-serif",
    )
    expect(indexCss, 'Do not re-introduce Geist as the app font').not.toContain('Geist Variable')
  })

  it('forbids raw OS-native <select> tags (use Radix Select or NativeSelect)', () => {
    const violations: string[] = []
    for (const [file, content] of tsxEntries) {
      if (file.endsWith('/NativeSelect.tsx')) continue
      if (/<select\b/.test(content)) violations.push(file)
    }
    expect(
      violations,
      'Raw <select> renders OS-native popups. Use @/components/ui/select or @/components/shared/NativeSelect instead (see CONTRIBUTING.md).',
    ).toEqual([])
  })

  it('forbids raw HTML <details>/<summary> elements and raw Unicode triangle icons', () => {
    const violations: string[] = []
    for (const [file, content] of tsxEntries) {
      if (/<details\b|<summary\b/.test(content)) {
        violations.push(`${file} (<details>/<summary>)`)
      }
      if (/[▶▼▸▾►◄]/.test(content)) {
        violations.push(`${file} (raw Unicode triangle)`)
      }
    }
    expect(
      violations,
      'Do not use native <details>/<summary> or raw Unicode triangles. Build collapsible sections with a button and Lucide ChevronDown (see CONTRIBUTING.md).',
    ).toEqual([])
  })

  it('forbids yellow/amber Tailwind chrome classes outside the narrow semantic status dots', () => {
    const allowlist = new Set([
      'components/loan/LoanMonthlyBreakdownCard.tsx',
      'components/shared/PriceFreshnessDot.tsx',
      'pages/setup/SetupStepAdmin.tsx',
    ])
    const rx = /\b(?:bg|text|border|ring)-(?:amber|yellow)-\d/
    const violations: string[] = []
    for (const [file, content] of tsxEntries) {
      if (allowlist.has(file)) continue
      if (rx.test(content)) violations.push(file)
    }
    expect(
      violations,
      'Yellow/amber UI chrome is forbidden in Picsou. Use neutral muted surfaces, Coffre Blue primary, or destructive red (see DESIGN.md & CONTRIBUTING.md).',
    ).toEqual([])
  })

  it('forbids bare variant="link" buttons', () => {
    const violations: string[] = []
    for (const [file, content] of tsxEntries) {
      if (file.endsWith('/button.tsx')) continue
      if (/variant=["']link["']/.test(content)) violations.push(file)
    }
    expect(
      violations,
      'Do not use bare variant="link" buttons. Use variant="outline" or variant="ghost" buttons instead (see CONTRIBUTING.md).',
    ).toEqual([])
  })

  it('preserves vertical scroll and horizontal grid containment on DialogContent', () => {
    const dialog = sourceOf('components/ui/dialog.tsx')
    expect(dialog, 'DialogContent must constrain CSS grid to 1 column (grid-cols-1)').toContain('grid-cols-1')
    expect(dialog, 'DialogContent must shrink children (*:min-w-0)').toContain('*:min-w-0')
    expect(dialog, 'DialogContent must block horizontal scrollbars (overflow-x-hidden)').toContain('overflow-x-hidden')
    expect(dialog, 'DialogContent must scroll tall forms internally (overflow-y-auto)').toContain('overflow-y-auto')
    expect(dialog, 'DialogContent must stay within the viewport height').toContain('max-h-[calc(100dvh-2rem)]')
  })

  it('preserves the strict z-index hierarchy across navigation, modals, popovers, and tooltips', () => {
    const mobileNav = sourceOf('components/layout/MobileBottomNav.tsx')
    const dialog = sourceOf('components/ui/dialog.tsx')
    const sheet = sourceOf('components/ui/sheet.tsx')
    const dropdown = sourceOf('components/ui/dropdown-menu.tsx')
    const select = sourceOf('components/ui/select.tsx')
    const tooltip = sourceOf('components/ui/tooltip.tsx')

    expect(mobileNav, 'MobileBottomNav must stay on z-40').toContain('z-40')
    expect(dialog, 'Dialog must stay on z-50').toContain('z-50')
    expect(sheet, 'Sheet must stay on z-50').toContain('z-50')
    expect(dropdown, 'DropdownMenu must float above dialogs on z-[60]').toContain('z-[60]')
    expect(select, 'Select popover must float above dialogs on z-[60]').toContain('z-[60]')
    expect(tooltip, 'Tooltip must sit on top at z-[70]').toContain('z-[70]')
  })

  it('forbids floating viewport-tall min-h EmptyState blocks', () => {
    const violations: string[] = []
    for (const [file, content] of tsxEntries) {
      if (/min-h-\[calc\(100vh-14rem\)\]/.test(content)) violations.push(file)
    }
    expect(
      violations,
      'Do not render floating min-h-[calc(100vh-14rem)] empty states. Wrap EmptyState in <Card><CardContent><EmptyState className="py-8" ... /></CardContent></Card> (see CONTRIBUTING.md).',
    ).toEqual([])
  })

  it('forbids double top padding (pt-6) on CardContent (Card already provides symmetric py-4)', () => {
    const violations: string[] = []
    for (const [file, content] of tsxEntries) {
      if (/<CardContent\b[^>]*\bpt-6\b/.test(content)) violations.push(file)
    }
    expect(
      violations,
      'Card already sets py-4; adding pt-6 on CardContent creates an uneven top gap.',
    ).toEqual([])
  })

  it('ensures every static t("...") key used in TSX exists in fr.json', () => {
    function hasKey(key: string): boolean {
      let cur: unknown = fr
      for (const part of key.split('.')) {
        if (cur && typeof cur === 'object' && part in (cur as Record<string, unknown>)) {
          cur = (cur as Record<string, unknown>)[part]
        } else {
          return false
        }
      }
      return typeof cur === 'string' || Array.isArray(cur)
    }

    const missing: string[] = []
    const rx = /(?<![A-Za-z0-9_])t\(\s*['"]([a-zA-Z0-9_.-]+)['"]/g
    for (const [file, content] of tsxEntries) {
      for (const match of content.matchAll(rx)) {
        const key = match[1]
        if (!key.includes('.')) continue
        if (!hasKey(key) && !hasKey(`${key}_one`) && !hasKey(`${key}_other`)) {
          missing.push(`${file}: ${key}`)
        }
      }
    }
    expect(missing, 'Missing translation keys found in TSX — add them to all four locale files.').toEqual([])
  })

  it('enforces French copy rules in fr.json (no em dashes, no arrows, no "IA" abbreviation)', () => {
    const emDashViolations: string[] = []
    const arrowViolations: string[] = []
    const iaViolations: string[] = []

    function walk(node: unknown, prefix: string) {
      if (typeof node === 'string') {
        if (node.includes('—')) emDashViolations.push(`${prefix}: ${node}`)
        if (node.includes('→')) arrowViolations.push(`${prefix}: ${node}`)
        if (/(?<![A-Za-zÀ-ÿ])IA(?![A-Za-zÀ-ÿ])/.test(node)) iaViolations.push(`${prefix}: ${node}`)
      } else if (node && typeof node === 'object') {
        for (const [k, v] of Object.entries(node)) {
          walk(v, prefix ? `${prefix}.${k}` : k)
        }
      }
    }
    walk(fr, '')

    expect(emDashViolations, 'Do not use em dashes (—) in French UI copy (use ":" or natural phrasing).').toEqual([])
    expect(arrowViolations, 'Do not use arrows (→) in French UI prose.').toEqual([])
    expect(iaViolations, 'Do not abbreviate to "IA" in French UI copy; write "intelligence artificielle".').toEqual([])
  })

  it('keeps What’s New releases complete in all four locales and compliant with French copy rules', () => {
    expect(WHATS_NEW_RELEASES.length).toBeGreaterThan(0)
    for (const release of WHATS_NEW_RELEASES) {
      for (const lang of ['fr', 'en', 'de', 'es'] as const) {
        expect(release.summary[lang].trim().length, `${release.version} summary (${lang})`).toBeGreaterThan(0)
        expect(release.highlights[lang].length, `${release.version} highlights (${lang})`).toBeGreaterThan(0)
      }
      for (const text of [release.summary.fr, ...release.highlights.fr]) {
        expect(text, `No em dash in French What's New (${release.version})`).not.toContain('—')
        expect(text, `No arrow in French What's New (${release.version})`).not.toContain('→')
        expect(
          /(?<![A-Za-zÀ-ÿ])IA(?![A-Za-zÀ-ÿ])/.test(text),
          `Write "intelligence artificielle" instead of "IA" in ${release.version}: ${text}`,
        ).toBe(false)
      }
    }
  })
})
