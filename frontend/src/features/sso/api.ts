import { api } from '@/lib/api-client'

export interface SsoInfo {
  enabled: boolean
  providerName: string
}

export interface LinkedIdentity {
  id: number
  email: string | null
  displayName: string | null
  createdAt: string
  /** Absent until the identity first signs in: the server omits null fields. */
  lastLoginAt?: string | null
  currentIssuer: boolean
}

export interface SignInMethods {
  hasPassword: boolean
  mfaEnabled: boolean
  identities: LinkedIdentity[]
  ssoEnabled: boolean
  providerName: string
  adminGroupManaged: boolean
}

export const ssoApi = {
  getInfo: () => api.get<SsoInfo>('/auth/sso', { skipGlobalErrorRedirect: true }).then(r => r.data),
  getSignInMethods: () => api.get<SignInMethods>('/auth/sign-in-methods').then(r => r.data),
  reauth: (body: { password: string; totpCode?: string }) => api.post('/auth/reauth', body),
  removePassword: () => api.delete('/auth/password'),
  setPassword: (newPassword: string) => api.put('/auth/password', { newPassword }),
  unlinkIdentity: (id: number) => api.delete(`/auth/identities/${id}`),
  adminUnlinkIdentities: (memberId: number) => api.delete(`/admin/members/${memberId}/identities`),
}
