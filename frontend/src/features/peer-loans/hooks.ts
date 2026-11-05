import { useQuery } from '@tanstack/react-query'
import { useInvalidatingMutation } from '@/hooks/use-invalidating-mutation'
import { QUERY_STALE_TIMES } from '@/lib/constants'
import type {
  PeerLoanFromTransactionRequest,
  PeerLoanRepaymentFromTransactionRequest,
  PeerLoanRepaymentRequest,
  PeerLoanSimulationRequest,
  PeerLoanTermsRequest,
} from '@/types/api'
import { peerLoansApi } from './api'

export const peerLoanKey = (accountId: number) => ['peer-loan', accountId] as const

/**
 * Every write moves the account's value, so it refreshes the loan, the account, the
 * accounts list, the dashboard and the history — net worth and the balance chart both
 * step with a repayment or a write-off.
 */
const invalidatesFor = (accountId: number) => [
  ['peer-loan', accountId],
  ['peer-loans', 'open'],
  ['peer-loans', 'overview'],
  ['accounts', accountId],
  ['accounts'],
  ['dashboard'],
  ['history'],
  ['net-worth-intraday'],
  // The cash leg is a budget transaction: the transaction list and cashflow follow it.
  ['budget'],
]

export function usePeerLoan(accountId: number, enabled = true) {
  return useQuery({
    queryKey: peerLoanKey(accountId),
    queryFn: () => peerLoansApi.get(accountId),
    staleTime: QUERY_STALE_TIMES.accountDetail,
    enabled: enabled && Number.isFinite(accountId),
  })
}

export function useSavePeerLoanTerms(accountId: number) {
  return useInvalidatingMutation({
    mutationFn: (data: PeerLoanTermsRequest) => peerLoansApi.saveTerms(accountId, data),
    invalidates: invalidatesFor(accountId),
  })
}

export function useAddPeerLoanRepayment(accountId: number) {
  return useInvalidatingMutation({
    mutationFn: (data: PeerLoanRepaymentRequest) => peerLoansApi.addRepayment(accountId, data),
    invalidates: invalidatesFor(accountId),
  })
}

export function useUpdatePeerLoanRepayment(accountId: number) {
  return useInvalidatingMutation({
    mutationFn: ({ repaymentId, data }: { repaymentId: number; data: PeerLoanRepaymentRequest }) =>
      peerLoansApi.updateRepayment(accountId, repaymentId, data),
    invalidates: invalidatesFor(accountId),
  })
}

export function useDeletePeerLoanRepayment(accountId: number) {
  return useInvalidatingMutation({
    mutationFn: (repaymentId: number) => peerLoansApi.deleteRepayment(accountId, repaymentId),
    invalidates: invalidatesFor(accountId),
  })
}

export function useClosePeerLoan(accountId: number) {
  return useInvalidatingMutation({
    mutationFn: (date?: string) => peerLoansApi.close(accountId, date),
    invalidates: invalidatesFor(accountId),
  })
}

/** Calculator preview; never stored. Debounced by the dialog, not by the query. */
export function usePeerLoanSimulation() {
  return useInvalidatingMutation({
    mutationFn: (data: PeerLoanSimulationRequest) => peerLoansApi.simulate(data),
    when: 'settled',
    invalidates: [],
  })
}

/**
 * Turns an existing outgoing transaction into a loan. The new account id is in the
 * response, so the caller navigates to it; every aggregate that saw the transaction
 * (accounts, dashboard, history) is refreshed.
 */
export function useCreatePeerLoanFromTransaction() {
  return useInvalidatingMutation({
    mutationFn: (data: PeerLoanFromTransactionRequest) => peerLoansApi.createFromTransaction(data),
    invalidates: [
      ['accounts'],
      ['dashboard'],
      ['history'],
      ['net-worth-intraday'],
      ['budget'],
      ['peer-loans', 'open'],
      ['peer-loans', 'overview'],
    ],
  })
}

/** The open loans a received transaction can be assigned to. */
export function useOpenPeerLoans(enabled = true) {
  return useQuery({
    queryKey: ['peer-loans', 'open'],
    queryFn: () => peerLoansApi.openLoans(),
    staleTime: QUERY_STALE_TIMES.accountDetail,
    enabled,
  })
}

/** The member-wide view: totals, one row per loan and the outstanding curve. */
export function usePeerLoansOverview(months = 12) {
  return useQuery({
    queryKey: ['peer-loans', 'overview', months],
    queryFn: () => peerLoansApi.overview(months),
    staleTime: QUERY_STALE_TIMES.accountDetail,
  })
}

/**
 * Links an existing incoming transaction to a repayment. The loan id comes from the
 * dialog, so the hook is not bound to one account; the invalidation is.
 */
export function useAssignPeerLoanRepayment() {
  return useInvalidatingMutation({
    mutationFn: ({ accountId, data }: {
      accountId: number
      data: PeerLoanRepaymentFromTransactionRequest
    }) => peerLoansApi.addRepaymentFromTransaction(accountId, data),
    invalidates: (_data, variables) => invalidatesFor(variables.accountId),
  })
}
