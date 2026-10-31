import { api } from '@/lib/api-client'

export interface AccountDeletionImpact {
  mode: 'DELETE_ACCOUNT' | 'RESET_LAST_ADMIN'
}

export interface DeleteMyAccountRequest {
  reAuth: { password: string } | { totpCode: string }
}

export interface DeleteMyAccountResponse {
  mode: 'DELETE_ACCOUNT' | 'RESET_LAST_ADMIN'
}

export const accountDeletionApi = {
  getDeletionImpact: () =>
    api.get<AccountDeletionImpact>('/me/deletion-impact').then(r => r.data),

  deleteMyAccount: (body: DeleteMyAccountRequest) =>
    api.post<DeleteMyAccountResponse>('/me/delete', body).then(r => r.data),
}
