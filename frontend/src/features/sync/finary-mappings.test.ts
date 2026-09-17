import { describe, expect, it } from 'vitest'
import { initialFinaryMappings, typesCompatible } from './finary-mappings'
import type { Account, FinaryPreviewResponse } from '@/types/api'

function preview(partial: Partial<FinaryPreviewResponse>): FinaryPreviewResponse {
  return {
    accounts: [],
    existingPicsouAccounts: [],
    totalTransactionCount: 0,
    fileToken: 'tok',
    ...partial,
  }
}

function account(id: number, name: string, type: Account['type']): Account {
  return {
    id,
    name,
    type,
    currency: 'EUR',
    currentBalance: 0,
    provider: 'Finary',
  } as Account
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

  it('falls back to unique name+type when backend sent CREATE_NEW', () => {
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
    expect(mappings[0].action).toBe('MAP_EXISTING')
    expect(mappings[0].targetAccountId).toBe(7)
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
})

describe('typesCompatible', () => {
  it('maps PEA to investments and Livret A to savings', () => {
    expect(typesCompatible('investments', 'PEA')).toBe(true)
    expect(typesCompatible('savings', 'LIVRET_A')).toBe(true)
    expect(typesCompatible('checkings', 'CRYPTO')).toBe(false)
  })
})
