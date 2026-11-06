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

const CORUM_LIFE_OPERATION_TYPES = [
  'scheduledPayment',
  'subscription',
  'couponDetachment',
  'administrativeManagementCosts',
  'profitParticipation',
  'arbitrage',
]

/** The contract's payments: the only CORUM Life operations that count in the budget. */
const CORUM_LIFE_PAYMENT_TYPES = ['scheduledPayment', 'subscription']

/** The provider the backend writes on a synced CORUM account. */
const CORUM_PROVIDER = 'CORUM'

/**
 * CORUM Life contract operations are budget-neutral rows, synced coupons carrying a
 * CORUM-LIFE-<CODE> ticker, or payments, which are neither. A payment is recognised by CORUM's own type name, which
 * another source could also use, so only on a synced row of an account known to be CORUM's:
 * `accountProvider` is passed where the account is known (its own page), and elsewhere the row
 * keeps the description the sync wrote.
 */
export function isCorumLifeOperation(transaction: Transaction, accountProvider?: string | null): boolean {
  return transaction.budgetNeutral === true
    // A manual BUY/SELL typed on a CORUM Life line keeps its own trade label.
    || (!transaction.isManual && (transaction.ticker?.startsWith('CORUM-LIFE-') ?? false))
    || (accountProvider === CORUM_PROVIDER && !transaction.isManual
      && transaction.type != null && CORUM_LIFE_PAYMENT_TYPES.includes(transaction.type))
}

/**
 * The label of a CORUM Life operation: its own translated type name, `unknown` for a type the
 * app has no wording for yet, and for a coupon the fund it detached from, so two coupons of one
 * date can be told apart.
 */
function corumLifeOperationLabel(transaction: Transaction, translate: (key: string) => string): string {
  const known = transaction.type != null && CORUM_LIFE_OPERATION_TYPES.includes(transaction.type)
  const label = translate(`accounts.corumLifeOperation.${known ? transaction.type : 'unknown'}`)
  const fund = transaction.type === 'couponDetachment' ? transaction.name?.trim() : null
  return fund ? `${label} ${fund}` : label
}

/** Builds a localized fallback only for manual instrument rows that have no display name. */
export function transactionDescription(
  transaction: Transaction,
  translate: (key: string) => string,
  accountProvider?: string | null,
): string {
  if (isCorumLifeOperation(transaction, accountProvider)) return corumLifeOperationLabel(transaction, translate)
  if (!transaction.isManual || !transaction.ticker?.trim() || transaction.name?.trim() || transaction.txType == null) {
    return transaction.description
  }

  return `${translate(TRANSACTION_TYPE_LABEL_KEYS[transaction.txType])} ${transaction.ticker}`
}
