import type { TFunction } from 'i18next'

/**
 * Every heading the exported workbook prints, in the language the UI is showing.
 *
 * The backend carries no message bundle, so the wording travels with the request and is matched
 * against its {@code LabelKey} enum; a key it does not recognise is ignored and a key we omit
 * falls back to that column's English default. Keep this list in step with `LabelKey.java` —
 * adding a column there without adding it here silently prints an English heading in a French
 * workbook. See `docs/decisions/2026-08-18-client-supplied-labels-for-xlsx-export.md`.
 */
const LABEL_KEYS = [
  'summarySheet', 'exportedAt', 'accountFallbackName',
  'profile', 'age', 'targetRetirementAge', 'marginalTaxRate', 'householdStatus',
  'taxHouseholdParts', 'dependents', 'annualGrossIncome', 'monthlyNetBeforeTax',
  'withholdingTaxRate', 'monthlyNetIncome', 'monthlySavingsCapacity', 'riskProfile',
  'recurringInvestments', 'savingsRate', 'monthlyInvestedTotal', 'planName', 'planAccount',
  'monthlyAmount', 'expectedReturn', 'positionBreakdown', 'unallocated',
  'debtSheet', 'debts', 'noDebt', 'debtScopeNote', 'totalBorrowed', 'totalOutstanding',
  'totalMonthlyPayment', 'loanAccount', 'propertyDebt',
  'accountName', 'accountType', 'provider', 'currency', 'balance', 'balanceEur',
  'cashBalance', 'sharePercent', 'lastSyncedAt', 'createdAt', 'openedAt',
  'positions', 'ticker', 'positionName', 'quantity', 'averageBuyIn', 'currentPrice',
  'quoteCurrency', 'currentValueEur', 'costBasisEur', 'pnlEur', 'pnlPercent',
  'priceAsOf', 'priceStale',
  'property', 'purchasePrice', 'purchaseDate', 'agencyFees', 'notaryFees', 'worksCost',
  'costBasis', 'propertyType', 'propertyCategory', 'address', 'postalCode', 'city', 'country',
  'surfaceArea', 'landArea', 'constructionYear', 'rooms', 'energyClass', 'rentalIncome',
  'valuationMode', 'lastValuedAt',
  'valuationHistory', 'valuedAt', 'estimatedValue', 'lowValue', 'highValue', 'pricePerSqm',
  'valuationProvider', 'confidence', 'sampleSize', 'sourceYear',
  'loan', 'lender', 'borrowedAmount', 'interestRate', 'monthlyPayment', 'insuranceMonthly',
  'fileFees', 'startDate', 'endDate', 'linkedAccount', 'remainingBalance',
  'totalInstallments', 'paidInstallments', 'totalInterestCost', 'totalInsuranceCost',
  'capitalRepaid',
  'amortization', 'installmentNumber', 'installmentDate', 'capital', 'interest', 'insurance',
  'totalPayment',
  'yes', 'no',
] as const

export function sheetLabels(t: TFunction): Record<string, string> {
  return Object.fromEntries(LABEL_KEYS.map(key => [key, t(`export.sheet.${key}`)]))
}
