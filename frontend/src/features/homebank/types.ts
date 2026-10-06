import type { Account, AccountType, Category } from '@/types/api'

export interface HomeBankAccountPreview {
  sourceId: string
  name: string
  institution: string
  sourceType: string
  suggestedType: AccountType
  currency: string
  initialBalance: number
  balance: number
  transactionCount: number
  closed: boolean
}

export interface HomeBankCategoryPreview {
  sourceId: string
  name: string
  parentSourceId?: string
  income: boolean
  /** Kind guessed from amount signs (QIF without I/E flags): either income or expense target is accepted. */
  kindInferred?: boolean
  transactionCount: number
}

export interface HomeBankSampleTransaction {
  sourceId: string
  accountSourceId: string
  date: string
  amount: number
  currency: string
  payee: string
  notes: string
  categorySourceId?: string
  transfer: boolean
}

export interface HomeBankPreviewResponse {
  fileToken: string
  accounts: HomeBankAccountPreview[]
  categories: HomeBankCategoryPreview[]
  existingAccounts: Account[]
  existingCategories: Category[]
  sampleTransactions: HomeBankSampleTransaction[]
  totalTransactions: number
  forecastTransactions: number
}

export type HomeBankAccountMappingAction = 'CREATE_NEW' | 'MAP_EXISTING' | 'SKIP'
export interface HomeBankAccountMapping {
  sourceId: string
  action: HomeBankAccountMappingAction
  targetAccountId?: number
  newAccount?: { name: string; type: AccountType; provider?: string; currency: string; color?: string }
}

export type HomeBankCategoryMappingAction = 'CREATE_NEW' | 'MAP_EXISTING' | 'UNCATEGORIZED'
export interface HomeBankCategoryMapping {
  sourceId: string
  action: HomeBankCategoryMappingAction
  targetCategoryId?: number
  name?: string
}

export interface HomeBankImportRequest {
  fileToken: string
  accountMappings: HomeBankAccountMapping[]
  categoryMappings: HomeBankCategoryMapping[]
}

export interface HomeBankImportResult {
  accountsCreated: number
  accountsMapped: number
  accountsSkipped: number
  categoriesCreated: number
  transactionsImported: number
  transactionsSkipped: number
}

export type HomeBankExistingAccount = Account
export type HomeBankExistingCategory = Category
