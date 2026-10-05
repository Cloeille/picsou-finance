import type { Transaction } from '@/types/api'

type TransactionType = NonNullable<Transaction['txType']>

const TRANSACTION_TYPE_LABEL_KEYS = {
  DEPOSIT: 'accounts.deposit',
  WITHDRAWAL: 'accounts.withdrawal',
  BUY: 'accounts.buy',
  SELL: 'accounts.sell',
  DIVIDEND: 'accounts.dividend',
  FEE: 'accounts.fee',
} satisfies Record<TransactionType, string>

/** Builds a localized fallback only for manual instrument rows that have no display name. */
export function transactionDescription(transaction: Transaction, translate: (key: string) => string): string {
  if (!transaction.isManual || !transaction.ticker?.trim() || transaction.name?.trim() || transaction.txType == null) {
    return transaction.description
  }

  return `${translate(TRANSACTION_TYPE_LABEL_KEYS[transaction.txType])} ${transaction.ticker}`
}
