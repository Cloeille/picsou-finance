import { api } from '@/lib/api-client'
import type { MemberProfile, MemberProfileRequest } from '@/types/api'

export const profileApi = {
  get: () => api.get<MemberProfile>('/me/profile').then(r => r.data),

  save: (body: MemberProfileRequest) =>
    api.put<MemberProfile>('/me/profile', body).then(r => r.data),
}
