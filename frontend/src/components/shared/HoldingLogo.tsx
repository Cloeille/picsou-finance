import { useState } from 'react'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar'
import { useDarkTheme } from '@/hooks/use-dark-theme'
import { cn, deriveMonogram } from '@/lib/utils'

/**
 * A holding's mark, sized to sit in a table's first column beside its ticker.
 *
 * Wraps the same `Avatar` primitive the account cards use, so a broken or absent image degrades
 * to the fallback instead of a torn-image glyph. A crypto mark is a CoinGecko URL; a share or
 * fund mark is Picsou's own `/api/instrument-logos` endpoint, see `docs/features/holding-logos.md`.
 *
 * Four details are deliberate:
 *
 * - The image is sized to the same box as the fallback rather than overflowing it. Coin logos are
 *   circular PNGs with transparent margins, so `object-contain` inside a fixed square keeps the
 *   mark from being cropped at the edges on a table row that is taller than the mark.
 * - The fallback is the **name's** initials, not the ticker. Every caller already renders the ticker
 *   as text right beside this component, so a ticker fallback would print it twice — and that is
 *   not a cosmetic detail: `PositionsByProduct.test.tsx` asserts one `BTC` per product group, and a
 *   duplicate is a real signal that the layout is wrong. Initials read as a mark where an empty disc
 *   read as a missing image. A holding without a name (an exchange coin line), or whose name is only
 *   its ticker, keeps the empty disc, since its only initials would be the ticker's. Either way the
 *   column width stays stable.
 * - The `onError` reset is what makes a *transient* failure recoverable: Radix keeps the image
 *   mounted after a load error, so without flipping back to "no image" a single failed request
 *   would leave the mark blank for that holding. Remounting on the next URL change also picks up
 *   a corrected URL.
 * - `logoUrlDark` is used under the dark palette when the source drew a separate mark for it. A
 *   black logo (Apple's) on the light variant would otherwise vanish against a dark table.
 */
export function HoldingLogo({
  logoUrl,
  logoUrlDark,
  ticker,
  name,
  className,
}: {
  logoUrl: string | null | undefined
  logoUrlDark?: string | null
  /** The image's accessible name, so the asset is read whether or not the mark loads. */
  ticker: string
  /** The holding's name, whose initials stand in for a missing mark. Without one the disc stays empty. */
  name?: string | null
  className?: string
}) {
  const dark = useDarkTheme()
  const url = (dark && logoUrlDark) || logoUrl || null
  // Keyed on the URL: changing it remounts the image, so a previously failed one is retried.
  const [failedUrl, setFailedUrl] = useState<string | null>(null)
  const src = url && url !== failedUrl ? url : null
  // The dividend API substitutes the ticker for a missing name.
  const initialsName = name?.trim()
  const showInitials = !!initialsName && initialsName.toLowerCase() !== ticker.toLowerCase()

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
      <AvatarFallback aria-hidden className="bg-muted text-[0.6rem] font-semibold">
        {showInitials && deriveMonogram(initialsName)}
      </AvatarFallback>
    </Avatar>
  )
}
