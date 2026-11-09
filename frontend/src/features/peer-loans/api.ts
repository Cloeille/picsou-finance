import { api } from '@/lib/api-client'
import type {
  PeerLoanDetail,
  PeerLoanFromTransactionRequest,
  PeerLoanOpen,
  PeerLoanRepayment,
  PeerLoanRepaymentFromTransactionRequest,
  PeerLoanRepaymentRequest,
  PeerLoanSimulationRequest,
  PeerLoanSimulationResponse,
  PeerLoanTermsRequest,
  PeerLoansOverview,
} from '@/types/api'

export const peerLoansApi = {
  get: (accountId: number) =>
    api.get<PeerLoanDetail>(`/accounts/${accountId}/peer-loan`).then(r => r.data),
  saveTerms: (accountId: number, data: PeerLoanTermsRequest) =>
    api.put<PeerLoanDetail>(`/accounts/${accountId}/peer-loan`, data).then(r => r.data),
  addRepayment: (accountId: number, data: PeerLoanRepaymentRequest) =>
    api.post<PeerLoanRepayment>(`/accounts/${accountId}/peer-loan/repayments`, data).then(r => r.data),
  updateRepayment: (accountId: number, repaymentId: number, data: PeerLoanRepaymentRequest) =>
    api.put<PeerLoanRepayment>(`/accounts/${accountId}/peer-loan/repayments/${repaymentId}`, data)
      .then(r => r.data),
  deleteRepayment: (accountId: number, repaymentId: number) =>
    api.delete<void>(`/accounts/${accountId}/peer-loan/repayments/${repaymentId}`).then(r => r.data),
  /** Closes the loan, writing the residual off on that date. */
  close: (accountId: number, date?: string) =>
    api.post<PeerLoanDetail>(
      `/accounts/${accountId}/peer-loan/close`,
      null,
      { params: date ? { date } : undefined },
    ).then(r => r.data),
  simulate: (data: PeerLoanSimulationRequest) =>
    api.post<PeerLoanSimulationResponse>('/peer-loans/simulate', data).then(r => r.data),
  createFromTransaction: (data: PeerLoanFromTransactionRequest) =>
    api.post<PeerLoanDetail>('/peer-loans/from-transaction', data).then(r => r.data),
  openLoans: () =>
    api.get<PeerLoanOpen[]>('/peer-loans/open').then(r => r.data),
  overview: (months = 12) =>
    api.get<PeerLoansOverview>('/peer-loans/overview', { params: { months } }).then(r => r.data),
  addRepaymentFromTransaction: (accountId: number, data: PeerLoanRepaymentFromTransactionRequest) =>
    api.post<PeerLoanDetail>(`/accounts/${accountId}/peer-loan/repayments/from-transaction`, data)
      .then(r => r.data),
}
