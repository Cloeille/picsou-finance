import { useQuery } from '@tanstack/react-query'
import { useInvalidatingMutation } from '@/hooks/use-invalidating-mutation'
import { QUERY_STALE_TIMES } from '@/lib/constants'
import type {
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
  ['accounts', accountId],
  ['accounts'],
  ['dashboard'],
  ['history'],
  ['net-worth-intraday'],
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
