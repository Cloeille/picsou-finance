import { describe, expect, it } from 'vitest'
import { availableTargets, initialFinaryMappings, typesCompatible } from './finary-mappings'
import type {
  Account,
  FinaryAccountMapping,
  FinaryAccountPreview,
  FinaryPreviewResponse,
} from '@/types/api'

function preview(partial: Partial<FinaryPreviewResponse>): FinaryPreviewResponse {
  return {
    accounts: [],
    existingPicsouAccounts: [],
    totalTransactionCount: 0,
    fileToken: 'tok',
    ...partial,
  }
}

function account(id: number, name: string, type: Account['type'], isManual = true): Account {
  return {
    id,
    name,
    type,
    currency: 'EUR',
    currentBalance: 0,
    provider: 'Finary',
    isManual,
  } as Account
}

function finaryAccount(category: string, id: string, name: string): FinaryAccountPreview {
  return {
    finaryId: id,
    finaryName: name,
    finaryInstitution: 'Bank',
    finaryCategory: category,
    suggestedType: 'CHECKING',
    currentBalance: 0,
    nativeCurrency: 'EUR',
    transactionCount: 0,
  }
}

function mapExisting(
  category: string,
  id: string,
  name: string,
  targetAccountId: number,
): FinaryAccountMapping {
  return { finaryId: id, finaryName: name, finaryCategory: category, action: 'MAP_EXISTING', targetAccountId }
}

describe('initialFinaryMappings', () => {
  it('uses backend MAP_EXISTING suggestions', () => {
    const mappings = initialFinaryMappings(
      preview({
        accounts: [
          {
            finaryId: 'c1',
            finaryName: 'MR CHERRIER CHRISTOPHE',
            finaryInstitution: 'La Banque Postale',
            finaryCategory: 'checkings',
            suggestedType: 'CHECKING',
            currentBalance: 100,
            nativeCurrency: 'EUR',
            transactionCount: 1,
          },
        ],
        suggestedMappings: [
          {
            finaryId: 'c1',
            finaryName: 'MR CHERRIER CHRISTOPHE',
            finaryCategory: 'checkings',
            action: 'MAP_EXISTING',
            targetAccountId: 42,
          },
        ],
      }),
    )
    expect(mappings[0].action).toBe('MAP_EXISTING')
    expect(mappings[0].targetAccountId).toBe(42)
  })

  it('keeps a backend CREATE_NEW over a same-name existing account', () => {
    const mappings = initialFinaryMappings(
      preview({
        accounts: [
          {
            finaryId: 'c1',
            finaryName: 'BOURSORAMA BANQUE',
            finaryInstitution: 'BoursoBank',
            finaryCategory: 'checkings',
            suggestedType: 'CHECKING',
            currentBalance: 50,
            nativeCurrency: 'EUR',
            transactionCount: 0,
          },
        ],
        existingPicsouAccounts: [account(7, 'BOURSORAMA BANQUE', 'CHECKING')],
        suggestedMappings: [
          {
            finaryId: 'c1',
            finaryName: 'BOURSORAMA BANQUE',
            finaryCategory: 'checkings',
            action: 'CREATE_NEW',
          },
        ],
      }),
    )
    expect(mappings[0].action).toBe('CREATE_NEW')
    expect(mappings[0].targetAccountId).toBeUndefined()
  })

  it('does not let an unsuggested row take a later row backend target', () => {
    const mappings = initialFinaryMappings(
      preview({
        accounts: [
          finaryAccount('checkings', 'new', 'Joint'),
          finaryAccount('checkings', 'old', 'Joint'),
        ],
        existingPicsouAccounts: [account(7, 'Joint', 'CHECKING')],
        suggestedMappings: [mapExisting('checkings', 'old', 'Joint', 7)],
      }),
    )
    expect(mappings.map((m) => [m.finaryId, m.action, m.targetAccountId])).toEqual([
      ['new', 'CREATE_NEW', undefined],
      ['old', 'MAP_EXISTING', 7],
    ])
  })

  it('never name-matches an unsuggested row onto a non-manual account', () => {
    const mappings = initialFinaryMappings(
      preview({
        accounts: [finaryAccount('investments', 'tr', 'Trade Republic')],
        existingPicsouAccounts: [account(5, 'Trade Republic', 'COMPTE_TITRES', false)],
      }),
    )
    expect(mappings[0].action).toBe('CREATE_NEW')
  })

  it('does not reset every row to CREATE_NEW when one Finary account is new', () => {
    const mappings = initialFinaryMappings(
      preview({
        accounts: [
          {
            finaryId: 'old',
            finaryName: 'Livret A',
            finaryInstitution: 'LBP',
            finaryCategory: 'savings',
            suggestedType: 'SAVINGS',
            currentBalance: 1,
            nativeCurrency: 'EUR',
            transactionCount: 0,
          },
          {
            finaryId: 'new',
            finaryName: 'Nouveau wallet',
            finaryInstitution: 'Ethereum',
            finaryCategory: 'cryptos',
            suggestedType: 'CRYPTO',
            currentBalance: 1,
            nativeCurrency: 'EUR',
            transactionCount: 0,
          },
        ],
        existingPicsouAccounts: [account(3, 'Livret A', 'LIVRET_A')],
      }),
    )
    expect(mappings[0].action).toBe('MAP_EXISTING')
    expect(mappings[0].targetAccountId).toBe(3)
    expect(mappings[1].action).toBe('CREATE_NEW')
  })

  it('tells apart two Finary accounts that share an id across categories', () => {
    const mappings = initialFinaryMappings(
      preview({
        accounts: [
          finaryAccount('checkings', '1', 'Compte courant'),
          finaryAccount('cryptos', '1', 'Ledger'),
        ],
        suggestedMappings: [
          mapExisting('checkings', '1', 'Compte courant', 10),
          mapExisting('cryptos', '1', 'Ledger', 20),
        ],
      }),
    )
    expect(
      mappings.map((m) => [m.finaryCategory, m.finaryId, m.finaryName, m.targetAccountId]),
    ).toEqual([
      ['checkings', '1', 'Compte courant', 10],
      ['cryptos', '1', 'Ledger', 20],
    ])
  })

  it('never suggests the same Picsou account for two Finary rows', () => {
    const mappings = initialFinaryMappings(
      preview({
        accounts: [
          finaryAccount('checkings', 'a', 'Compte joint'),
          finaryAccount('checkings', 'b', 'Compte joint'),
        ],
        existingPicsouAccounts: [account(10, 'Compte joint', 'CHECKING')],
        suggestedMappings: [
          mapExisting('checkings', 'a', 'Compte joint', 10),
          mapExisting('checkings', 'b', 'Compte joint', 10),
        ],
      }),
    )
    expect(mappings[0]).toMatchObject({ action: 'MAP_EXISTING', targetAccountId: 10 })
    expect(mappings[1].action).toBe('CREATE_NEW')
  })
})

describe('availableTargets', () => {
  const existing = [account(10, 'Courant', 'CHECKING'), account(20, 'Livret', 'LIVRET_A')]
  const mappings: FinaryAccountMapping[] = [
    mapExisting('checkings', 'a', 'Courant', 10),
    { finaryId: 'b', finaryName: 'Autre', finaryCategory: 'checkings', action: 'MAP_EXISTING' },
    { finaryId: 'c', finaryName: 'Ignoré', finaryCategory: 'savings', action: 'SKIP' },
  ]

  it('hides a target another row already maps onto', () => {
    expect(availableTargets(existing, mappings, 1).map((a) => a.id)).toEqual([20])
  })

  it('keeps the row its own target', () => {
    expect(availableTargets(existing, mappings, 0).map((a) => a.id)).toEqual([10, 20])
  })
})

describe('typesCompatible', () => {
  it('maps PEA to investments and Livret A to savings', () => {
    expect(typesCompatible('investments', 'PEA')).toBe(true)
    expect(typesCompatible('savings', 'LIVRET_A')).toBe(true)
    expect(typesCompatible('checkings', 'CRYPTO')).toBe(false)
  })
})
