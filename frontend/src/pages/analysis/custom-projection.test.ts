import { describe, expect, it } from 'vitest'
import { parseCustomRate, projectCustomRate } from './custom-projection'

describe('parseCustomRate', () => {
  it('parses integers and both dot and comma decimals within 0..100', () => {
    expect(parseCustomRate('12')).toBe(12)
    expect(parseCustomRate('12.5')).toBe(12.5)
    expect(parseCustomRate('12,5')).toBe(12.5)
    expect(parseCustomRate('0')).toBe(0)
    expect(parseCustomRate('100')).toBe(100)
  })

  it('rejects empty, negative, non-numeric, and >100 inputs', () => {
    expect(parseCustomRate('')).toBeNull()
    expect(parseCustomRate('   ')).toBeNull()
    expect(parseCustomRate('-5')).toBeNull()
    expect(parseCustomRate('101')).toBeNull()
    expect(parseCustomRate('abc')).toBeNull()
  })
})

describe('projectCustomRate', () => {
  it('compounds a lump sum at the exact annual rate using geometric monthly compounding', () => {
    const ref = [
      { date: '2026-11-30', valueEur: 10000, contributedEur: 10000 },
      { date: '2027-11-30', valueEur: 10750, contributedEur: 10000 },
      { date: '2028-11-30', valueEur: 11556.25, contributedEur: 10000 },
    ]
    const result = projectCustomRate(ref, 12)
    expect(result).toEqual([
      { date: '2026-11-30', valueEur: 10000, contributedEur: 10000 },
      { date: '2027-11-30', valueEur: 11200, contributedEur: 10000 },
      { date: '2028-11-30', valueEur: 12544, contributedEur: 10000 },
    ])
  })

  it('equals contributedEur at 0% annual return even with monthly inflows', () => {
    const ref = [
      { date: '2026-11-30', valueEur: 10000, contributedEur: 10000 },
      { date: '2027-11-30', valueEur: 17000, contributedEur: 16000 },
    ]
    const result = projectCustomRate(ref, 0)
    expect(result[1]).toEqual({ date: '2027-11-30', valueEur: 16000, contributedEur: 16000 })
  })

  it('compounds monthly contributions over the horizon', () => {
    const ref = [
      { date: '2026-11-30', valueEur: 10000, contributedEur: 10000 },
      { date: '2027-11-30', valueEur: 16800, contributedEur: 16000 },
    ]
    const result = projectCustomRate(ref, 12)
    // Base grows 10000 -> 11200, plus 12 monthly deposits of 500 compounding mid-year (~6317)
    expect(result[1].valueEur).toBeGreaterThan(17500)
    expect(result[1].valueEur).toBeLessThan(17600)
    expect(result[1].contributedEur).toBe(16000)
  })
})
