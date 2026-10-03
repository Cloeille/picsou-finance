import { useState } from 'react'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar'
import { cn } from '@/lib/utils'

/**
 * A holding's mark, sized to sit in a table's first column beside its ticker.
 *
 * Wraps the same `Avatar` primitive the account cards use, so a broken or absent image degrades
 * to the fallback instead of a torn-image glyph. Only crypto resolves a URL today — an equity has
 * no logo source, so it renders as the empty disc, see `docs/features/holding-logos.md`.
 *
 * Three details are deliberate:
 *
 * - The image is sized to the same box as the fallback rather than overflowing it. Coin logos are
 *   circular PNGs with transparent margins, so `object-contain` inside a fixed square keeps the
 *   mark from being cropped at the edges on a table row that is taller than the mark.
 * - The fallback is an **empty** mark, not the ticker. Every caller already renders the ticker as
 *   text right beside this component, so a text fallback would print it twice — and that is not a
 *   cosmetic detail: `PositionsByProduct.test.tsx` asserts one `BTC` per product group, and a
 *   duplicate is a real signal that the layout is wrong. An empty disc keeps the column width
 *   stable when an image is absent, exactly as the account cards' colour circle does.
 * - The `onError` reset is what makes a *transient* failure recoverable: Radix keeps the image
 *   mounted after a load error, so without flipping back to "no image" a single failed request
 *   would leave the mark blank for that holding. Remounting on the next `logoUrl` change also
 *   picks up a corrected URL.
 */
export function HoldingLogo({
  logoUrl,
  ticker,
  className,
}: {
  logoUrl: string | null
  /** The image's accessible name, so the asset is read whether or not the mark loads. */
  ticker: string
  className?: string
}) {
  // Keyed on the URL: changing it remounts the image, so a previously failed one is retried.
  const [failedUrl, setFailedUrl] = useState<string | null>(null)
  const src = logoUrl && logoUrl !== failedUrl ? logoUrl : null

  return (
    <Avatar className={cn('size-6 shrink-0', className)}>
      {src && (
        <AvatarImage
          key={src}
          src={src}
          alt={ticker}
          className="object-contain"
          onError={() => setFailedUrl(src)}
        />
      )}
      <AvatarFallback className="bg-muted" />
    </Avatar>
  )
}
