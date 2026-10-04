import type { Account, AccountType, Category } from '@/types/api'

export interface ActualAccountPreview {
  sourceId: string
  name: string
  offBudget: boolean
  closed: boolean
  suggestedType: AccountType
  balance: number
  transactionCount: number
  /** The Picsou account an earlier import created for this source, if any. */
  importedAccountId: number | null
}

export interface ActualCategoryPreview {
  sourceId: string
  name: string
  groupName: string | null
  income: boolean
  transactionCount: number
}

export type ActualTransactionKind = 'REGULAR' | 'TRANSFER' | 'STARTING_BALANCE'

export interface ActualSampleTransaction {
  sourceId: string
  accountSourceId: string
  date: string
  amount: number
  payee: string | null
  notes: string | null
  categorySourceId: string | null
  kind: ActualTransactionKind
}

export interface ActualPreviewResponse {
  fileToken: string
  /** The budget's own currency, or null when the file does not record one. */
  currency: string | null
  accounts: ActualAccountPreview[]
  categories: ActualCategoryPreview[]
  existingAccounts: Account[]
  existingCategories: Category[]
  sampleTransactions: ActualSampleTransaction[]
  totalTransactions: number
  transferTransactions: number
}

export type ActualAccountMappingAction = 'CREATE_NEW' | 'MAP_EXISTING' | 'SKIP'
export interface ActualAccountMapping {
  sourceId: string
  action: ActualAccountMappingAction
  targetAccountId?: number
  newAccount?: { name: string; type: AccountType; currency: string; color?: string }
}

export type ActualCategoryMappingAction = 'CREATE_NEW' | 'MAP_EXISTING' | 'UNCATEGORIZED'
export interface ActualCategoryMapping {
  sourceId: string
  action: ActualCategoryMappingAction
  targetCategoryId?: number
  name?: string
}

export interface ActualImportRequest {
  fileToken: string
  currency: string
  accountMappings: ActualAccountMapping[]
  categoryMappings: ActualCategoryMapping[]
}

/**
 * Rows a re-import leaves on an account the user created: KEPT_MISSING rows no longer exist in
 * Actual (deleted, or a parent since split), KEPT_MOVED rows now belong to another Actual account.
 */
export type ActualWarningReason = 'KEPT_MISSING' | 'KEPT_MOVED'
export interface ActualImportWarning {
  reason: ActualWarningReason
  count: number
}

/** What an import request would change, computed by the dry run before the user confirms. */
export interface ActualImportPlan {
  transactionsToAdd: number
  transactionsToDelete: number
  transactionsToMove: number
  warnings: ActualImportWarning[]
}

export interface ActualImportResult {
  accountsCreated: number
  accountsMapped: number
  accountsSkipped: number
  categoriesCreated: number
  transactionsImported: number
  transactionsSkipped: number
  transactionsDeleted: number
  transactionsMoved: number
  warnings: ActualImportWarning[]
}
