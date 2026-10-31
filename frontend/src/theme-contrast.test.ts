import { describe, it, expect } from 'vitest'
import css from './index.css?raw'

type Rgb = [number, number, number]

function themeTokens(selector: string): Record<string, Rgb> {
  const escaped = selector.replace('.', '\\.')
  const block = new RegExp(`^${escaped} \\{([\\s\\S]*?)^\\}`, 'm').exec(css)?.[1]
  if (!block) throw new Error(`no ${selector} block in index.css`)
  const tokens: Record<string, Rgb> = {}
  for (const [, name, l, c, h] of block.matchAll(/--([\w-]+):\s*oklch\(([\d.]+) ([\d.]+) ([\d.]+)\)/g)) {
    tokens[name!] = oklchToLinearSrgb(Number(l), Number(c), Number(h))
  }
  return tokens
}

function oklchToLinearSrgb(L: number, C: number, h: number): Rgb {
  const a = C * Math.cos((h * Math.PI) / 180)
  const b = C * Math.sin((h * Math.PI) / 180)
  const l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3
  const m = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3
  const s = (L - 0.0894841775 * a - 1.291485548 * b) ** 3
  const clip = (v: number) => Math.min(1, Math.max(0, v))
  return [
    clip(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
    clip(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
    clip(-0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s),
  ]
}

const encode = (v: number) => (v <= 0.0031308 ? 12.92 * v : 1.055 * v ** (1 / 2.4) - 0.055)
const decode = (v: number) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4)

/** `bg-x/10` over a surface: browsers composite alpha in gamma-encoded sRGB. */
function tint(fg: Rgb, surface: Rgb, alpha: number): Rgb {
  return fg.map((v, i) => decode(encode(v) * alpha + encode(surface[i]!) * (1 - alpha))) as Rgb
}

function contrast(x: Rgb, y: Rgb): number {
  const luminance = ([r, g, b]: Rgb) => 0.2126 * r + 0.7152 * g + 0.0722 * b
  const [hi, lo] = [luminance(x), luminance(y)].sort((p, q) => q - p)
  return (hi! + 0.05) / (lo! + 0.05)
}

describe.each([':root', '.dark'])('%s primary tokens meet WCAG AA', (selector) => {
  const t = themeTokens(selector)

  it('primary text reads on the canvas, a card and its own 10% tint', () => {
    expect(contrast(t.primary!, t.background!)).toBeGreaterThanOrEqual(4.5)
    expect(contrast(t.primary!, t.card!)).toBeGreaterThanOrEqual(4.5)
    expect(contrast(t.primary!, tint(t.primary!, t.card!, 0.1))).toBeGreaterThanOrEqual(4.5)
  })

  it('primary-foreground reads on a primary fill', () => {
    expect(contrast(t['primary-foreground']!, t.primary!)).toBeGreaterThanOrEqual(4.5)
  })

  it('a primary progress fill stands out from its muted track', () => {
    expect(contrast(t.primary!, t.muted!)).toBeGreaterThanOrEqual(3)
  })
})

describe('.dark chart series', () => {
  const t = themeTokens('.dark')

  it.each(['chart-1', 'chart-2', 'chart-3', 'chart-4', 'chart-5'])('%s stroke stands out from a card', (token) => {
    expect(contrast(t[token]!, t.card!)).toBeGreaterThanOrEqual(3)
  })
})
