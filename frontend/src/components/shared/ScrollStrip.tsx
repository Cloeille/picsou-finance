import { useEffect, useLayoutEffect, useRef, type ReactNode } from 'react'
import { cn } from '@/lib/utils'

const ACTIVE_ITEM = '[aria-current="page"], [aria-selected="true"], [aria-pressed="true"]'
const FADE = '1.5rem'

/** Centers the active item, but only when it is outside the strip, so it never fights a visible one. */
function centerActive(strip: HTMLElement) {
  const active = strip.querySelector<HTMLElement>(ACTIVE_ITEM)
  if (!active) return
  const box = strip.getBoundingClientRect()
  const item = active.getBoundingClientRect()
  if (item.left < box.left || item.right > box.right) {
    strip.scrollLeft += item.left - box.left - (box.width - item.width) / 2
  }
}

/**
 * One-row horizontal scroller for a tab or filter strip that is wider than a phone screen.
 * The side that hides more items fades out, and the active item is scrolled into view on
 * mount, whenever `activeKey` changes and whenever the strip changes width, so a deep link to
 * the last tab does not land off-screen and a rotation does not push it out. When only the
 * content changes size (a late font, a badge count arriving), the active item is revealed
 * again only if the user has not swiped since, so a deliberate swipe is never undone.
 *
 * `activeKey` must change with the selection, so pass the controlled value, not a default.
 * The strip pads itself by the 4px the global focus ring reaches outside a control (and
 * cancels that with a negative margin), so the overflow never cuts a keyboard focus ring.
 */
export function ScrollStrip({
  activeKey,
  className,
  children,
}: {
  activeKey?: string
  className?: string
  children: ReactNode
}) {
  const ref = useRef<HTMLDivElement>(null)
  // Where the last reveal left the strip; any other offset means the user swiped since.
  const revealedAt = useRef(0)

  useLayoutEffect(() => {
    const strip = ref.current
    if (!strip) return
    centerActive(strip)
    revealedAt.current = strip.scrollLeft
  }, [activeKey])

  useEffect(() => {
    const strip = ref.current
    if (!strip) return
    const updateFade = () => {
      const hiddenStart = strip.scrollLeft > 1
      const hiddenEnd = strip.scrollLeft + strip.clientWidth < strip.scrollWidth - 1
      strip.style.setProperty('--fade-start', hiddenStart ? FADE : '0px')
      strip.style.setProperty('--fade-end', hiddenEnd ? FADE : '0px')
    }
    let width = -1
    const observer = new ResizeObserver(() => {
      const resized = strip.clientWidth !== width
      width = strip.clientWidth
      if (resized || strip.scrollLeft === revealedAt.current) {
        centerActive(strip)
        revealedAt.current = strip.scrollLeft
      }
      updateFade()
    })
    observer.observe(strip)
    if (strip.firstElementChild) observer.observe(strip.firstElementChild)
    strip.addEventListener('scroll', updateFade, { passive: true })
    return () => {
      observer.disconnect()
      strip.removeEventListener('scroll', updateFade)
    }
  }, [])

  return (
    <div
      ref={ref}
      className={cn('-m-1 overflow-x-auto p-1', className)}
      style={{
        maskImage:
          'linear-gradient(to right, transparent, #000 var(--fade-start, 0px), #000 calc(100% - var(--fade-end, 0px)), transparent)',
      }}
    >
      {children}
    </div>
  )
}
