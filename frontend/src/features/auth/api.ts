import { api } from '@/lib/api-client'

export const authApi = {
  login: (username: string, password: string) =>
    api.post<{ username: string; role: string; memberId: number; displayName: string }>('/auth/login', { username, password }).then(r => ({
      ...r.data,
      role: r.data.role as 'ADMIN' | 'MEMBER'
    })),
  // A 204 has an empty body; a 200 may carry the IdP end-session URL.
  logout: () =>
    api.post('/auth/logout').then(r => (r.data && typeof r.data === 'object' ? (r.data as { endSessionUrl?: string }) : {})),
  refresh: () =>
    api.post<{ username: string; role: string; memberId: number; displayName: string }>('/auth/refresh').then(r => ({
      ...r.data,
      role: r.data.role as 'ADMIN' | 'MEMBER'
    })),
  activate: (token: string, password: string, acknowledgedWarning: boolean) =>
    api.post(`/auth/activate/${token}`, { password, acknowledgedWarning }),
}
