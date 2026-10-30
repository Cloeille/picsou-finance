import { describe, it, expect } from 'vitest'
import { squarify } from './treemap'

const value = (i: { v: number }) => i.v

describe('squarify', () => {
  it('covers the whole area exactly once', () => {
    const rects = squarify([{ v: 50 }, { v: 30 }, { v: 20 }], value)

    const area = rects.reduce((s, r) => s + r.w * r.h, 0)
    // 100 x 100 space; a gap or an overlap would show up here as a wrong total.
    expect(area).toBeCloseTo(10000, 6)
  })

  it('gives each item area in proportion to its value', () => {
    const rects = squarify([{ v: 60 }, { v: 40 }], value)

    const areaOf = (v: number) => {
      const r = rects.find((r) => r.item.v === v)!
      return (r.w * r.h) / 100
    }
    expect(areaOf(60)).toBeCloseTo(60, 6)
    expect(areaOf(40)).toBeCloseTo(40, 6)
  })

  it('never overlaps two rectangles', () => {
    const rects = squarify([{ v: 40 }, { v: 25 }, { v: 20 }, { v: 10 }, { v: 5 }], value)

    for (let i = 0; i < rects.length; i++) {
      for (let j = i + 1; j < rects.length; j++) {
        const a = rects[i]
        const b = rects[j]
        const disjoint =
          a.x + a.w <= b.x + 1e-9 ||
          b.x + b.w <= a.x + 1e-9 ||
          a.y + a.h <= b.y + 1e-9 ||
          b.y + b.h <= a.y + 1e-9
        expect(disjoint).toBe(true)
      }
    }
  })

  it('orders largest first', () => {
    const rects = squarify([{ v: 10 }, { v: 70 }, { v: 20 }], value)
    expect(rects.map((r) => r.item.v)).toEqual([70, 20, 10])
  })

  it('returns nothing rather than dividing by zero', () => {
    expect(squarify([], value)).toEqual([])
    expect(squarify([{ v: 0 }, { v: 0 }], value)).toEqual([])
  })
})
