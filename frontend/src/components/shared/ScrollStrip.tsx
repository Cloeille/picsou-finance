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
 * mount, whenever `activeKey` changes and whenever the strip is resized, so a deep link to
 * the last tab does not land off-screen and a rotation or late font does not push it out.
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

  useLayoutEffect(() => {
    if (ref.current) centerActive(ref.current)
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
    const observer = new ResizeObserver(() => {
      centerActive(strip)
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
      className={cn('overflow-x-auto', className)}
      style={{
        maskImage:
          'linear-gradient(to right, transparent, #000 var(--fade-start, 0px), #000 calc(100% - var(--fade-end, 0px)), transparent)',
      }}
    >
      {children}
    </div>
  )
}
