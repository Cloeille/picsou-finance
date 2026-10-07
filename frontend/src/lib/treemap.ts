/**
 * Slice-and-dice treemap layout: proportional rectangles in a 0–100 coordinate space.
 *
 * Extracted from `DistributionPie`, where it was written against that component's own item
 * shape, once a second chart needed it. Generic over anything carrying a value, so the account
 * distribution and the diversification breakdown lay out through one implementation rather than
 * two that drift.
 *
 * Percentage units rather than pixels: the caller positions absolutely and the layout stays
 * correct at any container size, with no measurement and no resize observer.
 */
export interface TreemapRect<T> {
  x: number
  y: number
  w: number
  h: number
  item: T
}

/**
 * Lays items out largest-first, alternating split direction.
 *
 * At each step it picks the split point giving the least-elongated rectangles, which is what
 * keeps small items readable instead of reducing them to slivers.
 */
export function squarify<T>(items: T[], valueOf: (item: T) => number): TreemapRect<T>[] {
  if (items.length === 0) return []

  const sorted = [...items].sort((a, b) => valueOf(b) - valueOf(a))
  const total = sorted.reduce((s, i) => s + valueOf(i), 0)
  if (total <= 0) return []

  const rects: TreemapRect<T>[] = []

  function sliceAndDice(
    slice: T[],
    x: number,
    y: number,
    w: number,
    h: number,
    horizontal: boolean,
  ) {
    if (slice.length === 0 || w <= 0 || h <= 0) return
    if (slice.length === 1) {
      rects.push({ x, y, w, h, item: slice[0] })
      return
    }

    const sliceTotal = slice.reduce((s, i) => s + valueOf(i), 0)
    if (sliceTotal <= 0) return

    let bestSplit = 1
    let bestRatio = Infinity
    let runningSum = 0
    for (let i = 0; i < slice.length - 1; i++) {
      runningSum += valueOf(slice[i])
      const ratio = runningSum / sliceTotal
      const rw = horizontal ? w * ratio : w
      const rh = horizontal ? h : h * ratio
      const r = Math.max(rw / rh, rh / rw)
      if (r < bestRatio) {
        bestRatio = r
        bestSplit = i + 1
      }
    }

    const head = slice.slice(0, bestSplit)
    const tail = slice.slice(bestSplit)
    const headRatio = head.reduce((s, i) => s + valueOf(i), 0) / sliceTotal

    if (horizontal) {
      const headW = w * headRatio
      sliceAndDice(head, x, y, headW, h, !horizontal)
      sliceAndDice(tail, x + headW, y, w - headW, h, !horizontal)
    } else {
      const headH = h * headRatio
      sliceAndDice(head, x, y, w, headH, !horizontal)
      sliceAndDice(tail, x, y + headH, w, h - headH, !horizontal)
    }
  }

  sliceAndDice(sorted, 0, 0, 100, 100, true)
  return rects
}
