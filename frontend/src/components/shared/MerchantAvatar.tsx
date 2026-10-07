import { useState } from 'react'

import { cn } from '@/lib/utils'

/**
 * A small monogram tile standing in for a merchant logo. Fully offline by design
 * (the logo ADR forbids network fetches by default): the colour is either the
 * brand's own colour from the knowledge base, or a deterministic OKLCH tint
 * hashed from the label so the *same* merchant always gets the *same* colour
 * across the app — no storage, no flicker.
 *
 * Online logos are an opt-in cosmetic toggle: when the member enables them, callers
 * pass a {@link MerchantAvatarProps.logoUrl} (see `useMerchantLogoUrl`) and the tile
 * renders that image over the monogram, falling back to the monogram if it fails to
 * load. The monogram is therefore always the safe default — a disabled or broken
 * proxy is visually indistinguishable from "this brand has no logo".
 */

interface MerchantAvatarProps {
  /** Canonical merchant label, e.g. "Carrefour". Drives the monogram + fallback colour. */
  label?: string | null
  /** Brand colour from the knowledge base (hex). Takes precedence over the hashed tint. */
  color?: string | null
  /** Explicit monogram from the knowledge base; otherwise derived from the label. */
  monogram?: string | null
  /**
   * Opt-in logo URL (e.g. `/api/merchants/42/logo`). When set, the image renders over the
   * monogram and falls back to it on load error. Leave undefined to stay monogram-only.
   */
  logoUrl?: string | null
  size?: 'sm' | 'md' | 'lg'
  className?: string
}

const SIZE_CLASS: Record<NonNullable<MerchantAvatarProps['size']>, string> = {
  sm: 'size-7 text-[0.65rem]',
  md: 'size-9 text-xs',
  lg: 'size-11 text-sm',
}

/** First letters of up to two words, else the first two characters. Always uppercase. */
function deriveMonogram(label: string | null | undefined): string {
  const clean = (label ?? '').trim()
  if (!clean) return '•'
  const words = clean.split(/\s+/).filter(Boolean)
  const letters =
    words.length >= 2
      ? words[0]!.charAt(0) + words[1]!.charAt(0)
      : clean.slice(0, 2)
  return letters.toUpperCase()
}

/** Stable 32-bit hash (djb2). Same string → same number, every render, no storage. */
function hash(input: string): number {
  let h = 5381
  for (let i = 0; i < input.length; i++) {
    h = (h * 33) ^ input.charCodeAt(i)
  }
  return h >>> 0
}

/** WCAG relative luminance of a #rrggbb colour, used to pick black/white text. */
function hexLuminance(hex: string): number | null {
  const m = /^#?([\da-f]{6})$/i.exec(hex.trim())
  if (!m) return null
  const int = parseInt(m[1]!, 16)
  const channel = (c: number) => {
    const s = c / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  }
  const r = channel((int >> 16) & 0xff)
  const g = channel((int >> 8) & 0xff)
  const b = channel(int & 0xff)
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** Background + readable foreground for a given label/brand colour. */
function pickColors(label: string | null | undefined, color: string | null | undefined): {
  background: string
  foreground: string
} {
  if (color) {
    const lum = hexLuminance(color)
    // Unknown format → trust the colour and default to white text.
    const foreground = lum !== null && lum > 0.6 ? '#1a1a1a' : '#ffffff'
    return { background: color, foreground }
  }
  // Deterministic, muted tint. Fixed L/C keep it Apple-soft and on-brand in both themes;
  // L≈0.62 reads cleanly with white text.
  const hue = hash(label ?? '') % 360
  return { background: `oklch(0.62 0.13 ${hue})`, foreground: '#ffffff' }
}

export function MerchantAvatar({
  label,
  color,
  monogram,
  logoUrl,
  size = 'md',
  className,
}: MerchantAvatarProps) {
  const text = (monogram?.trim() || deriveMonogram(label)).slice(0, 2)
  const { background, foreground } = pickColors(label, color)

  // The monogram is the base layer; the logo (if any) renders on top and is removed
  // on load error, revealing the monogram. Reset the error flag *during render* when the URL
  // changes (the "adjust state on prop change" pattern) so a recycled avatar slot re-attempts
  // the new merchant's logo — no effect needed, and React skips committing the discarded render.
  const [imgFailed, setImgFailed] = useState(false)
  const [lastUrl, setLastUrl] = useState(logoUrl)
  if (logoUrl !== lastUrl) {
    setLastUrl(logoUrl)
    setImgFailed(false)
  }
  const showImage = Boolean(logoUrl) && !imgFailed

  return (
    <span
      aria-hidden="true"
      title={label ?? undefined}
      className={cn(
        'relative inline-flex shrink-0 select-none items-center justify-center overflow-hidden rounded-full font-semibold leading-none',
        SIZE_CLASS[size],
        className,
      )}
      style={{ backgroundColor: background, color: foreground }}
    >
      {text}
      {showImage && (
        <img
          src={logoUrl!}
          alt=""
          loading="lazy"
          onError={() => setImgFailed(true)}
          className="absolute inset-0 size-full rounded-full object-cover"
        />
      )}
    </span>
  )
}
