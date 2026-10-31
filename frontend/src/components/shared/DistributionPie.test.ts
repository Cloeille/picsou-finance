import { describe, expect, it, vi } from 'vitest'
import {
  disambiguateDistributionNames,
  type DistributionItem,
} from './distribution-labels'

const item = (
  accountId: number,
  name: string,
  accountType: DistributionItem['accountType'],
): DistributionItem => ({
  accountId,
  name,
  accountType,
  color: '#000000',
  balanceEur: 100,
  percentage: 50,
})

const labels = {
  PEA: 'PEA',
  COMPTE_TITRES: 'Compte titres',
} as const

describe('disambiguateDistributionNames', () => {
  it('detects duplicate names regardless of browser-specific casing', () => {
    const nativeLocaleLowerCase = String.prototype.toLocaleLowerCase
    // Simulate a browser whose locale treats uppercase I differently from lowercase i.
    const localeCasing = vi.spyOn(String.prototype, 'toLocaleLowerCase')
      .mockImplementation(function (this: string, locales) {
        return nativeLocaleLowerCase.call(this, locales ?? 'tr-TR')
      })
    try {
      const result = disambiguateDistributionNames([
        item(10, 'SYNTHETIC', 'PEA'),
        item(11, 'synthetic', 'COMPTE_TITRES'),
      ], type => type)

      expect(result.map(entry => entry.name)).toEqual([
        'SYNTHETIC · PEA',
        'synthetic · COMPTE_TITRES',
      ])
    } finally {
      localeCasing.mockRestore()
    }
  })

  it('adds the account type to broker accounts sharing the same name', () => {
    const result = disambiguateDistributionNames([
      item(10, 'DUPONT Camille', 'PEA'),
      item(11, 'DUPONT Camille', 'COMPTE_TITRES'),
    ], type => labels[type as keyof typeof labels] ?? type)

    expect(result.map(entry => entry.name)).toEqual([
      'DUPONT Camille · PEA',
      'DUPONT Camille · Compte titres',
    ])
  })

  it('keeps an already unique account name unchanged', () => {
    const source = [item(10, 'PEA Bourse Direct', 'PEA')]

    const result = disambiguateDistributionNames(source, type => type)

    expect(result).toEqual(source)
    expect(result[0]).toBe(source[0])
  })

  it('uses the account id when both name and type are identical', () => {
    const result = disambiguateDistributionNames([
      item(10, 'Titres', 'COMPTE_TITRES'),
      item(11, ' titres ', 'COMPTE_TITRES'),
    ], () => 'Compte titres')

    expect(result.map(entry => entry.name)).toEqual([
      'Titres · Compte titres · #10',
      ' titres  · Compte titres · #11',
    ])
  })
})
