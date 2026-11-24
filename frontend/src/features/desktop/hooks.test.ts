import { describe, expect, it } from 'vitest'
import { parseUnavailable, sidecarService } from './hooks'

describe('desktop unavailable banks', () => {
  it('maps a UI key to its sidecar service', () => {
    expect(sidecarService('amex')).toBe('amex-auth')
    expect(sidecarService('caisseEpargne')).toBe('caisse-epargne-auth')
    expect(sidecarService('caisse-epargne')).toBe('caisse-epargne-auth')
  })

  it('reads the list only inside the desktop app', () => {
    expect([...parseUnavailable({ app: { desktop: 'true', 'desktop-unavailable': 'amex-auth, bnp-auth,' } })])
      .toEqual(['amex-auth', 'bnp-auth'])
    expect(parseUnavailable({ app: { desktop: 'false', 'desktop-unavailable': 'amex-auth' } }).size).toBe(0)
    expect(parseUnavailable({}).size).toBe(0)
    expect(parseUnavailable(undefined).size).toBe(0)
  })
})
