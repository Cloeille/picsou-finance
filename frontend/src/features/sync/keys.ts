import type { QueryKey } from '@tanstack/react-query'

export const syncKeys = {
  all: ['sync'] as const,
  banks: () => [...syncKeys.all, 'banks'] as const,
  institutions: (q: string, country: string) => [...syncKeys.all, 'institutions', q, country] as const,
  countries: () => [...syncKeys.all, 'countries'] as const,
  tr: () => [...syncKeys.all, 'tr'] as const,
  bourso: () => [...syncKeys.all, 'bourso'] as const,
  caisseEpargne: () => [...syncKeys.all, 'caisse-epargne'] as const,
  revolut: () => [...syncKeys.all, 'revolut'] as const,
  bourseDirect: () => [...syncKeys.all, 'bourse-direct'] as const,
  degiro: () => [...syncKeys.all, 'degiro'] as const,
  amundi: () => [...syncKeys.all, 'amundi'] as const,
  cmes: () => [...syncKeys.all, 'cmes'] as const,
  cic: () => [...syncKeys.all, 'cic'] as const,
  amex: () => [...syncKeys.all, 'amex'] as const,
  fortuneo: () => [...syncKeys.all, 'fortuneo'] as const,
  corum: () => [...syncKeys.all, 'corum'] as const,
  sofidy: () => [...syncKeys.all, 'sofidy'] as const,
  ibkr: () => [...syncKeys.all, 'ibkr'] as const,
  simplefin: () => [...syncKeys.all, 'simplefin'] as const,
  exchanges: () => [...syncKeys.all, 'exchanges'] as const,
  wallets: () => [...syncKeys.all, 'wallets'] as const,
  finary: () => [...syncKeys.all, 'finary'] as const,
}

/** Views fed by the accounts a sync imports. */
export const ACCOUNT_VIEWS = [['accounts'], ['dashboard']] as const satisfies readonly QueryKey[]
